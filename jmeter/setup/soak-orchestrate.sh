#!/usr/bin/env bash
# =============================================================================
# S7 稳定性 soak 一键编排（约 36 分钟），复现 2026-09-03 压测报告 docs/压测报告-2026-09-03.md
#
# 流程：
#   1) 重置 sku 1006（DB+Redis 库存 1000，清理订单/账本/余额）
#   2) mixed-scenario.jmx 持续 1800s：TG1 v1 fast-path 100 线程 + TG3 活动页读
#   3) 每 60s 采样：Stream pending / sku1006 订单 / ai 日志总量 / DB 连接 / 容器 CPU
#   4) t≈600s：1000 用户抢 sku 1006（ramp 8s）→ kill 应用 60s → 重启（无 key）
#      → 验证 XCLAIM 补偿器对 pending 消息的重投递与最终一致性
#
# 前置：已执行 jmeter/setup/prepare-stress-data.sh（生成 jmeter/data/*.csv 与用户/Key）
# 用法：bash jmeter/setup/soak-orchestrate.sh
# 输出：tmp/soak-metrics.log（采样）、tmp/soak-jmeter.log、tmp/results/s7*.jtl
# =============================================================================
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
cd "$ROOT"

MYSQL_CONT="${MYSQL_CONT:-token-mysql}"
REDIS_CONT="${REDIS_CONT:-token-redis}"
MYSQL_PWD="${MYSQL_PWD:-20030226}"
REDIS_PWD="${REDIS_PWD:-123456}"
JAVA_BIN="${JAVA_BIN:-/d/Java/jdk-17.0.12/bin/java}"   # JDK17 路径按环境修改
JAR="$ROOT/target/ai-open-platform-0.0.1-SNAPSHOT.jar"
SKU=1006
STOCK=1000

MYSQL="docker exec $MYSQL_CONT mysql -uroot -p$MYSQL_PWD"
REDIS="docker exec $REDIS_CONT redis-cli -a $REDIS_PWD --no-auth-warning"
METRICS=tmp/soak-metrics.log
: > "$METRICS"

echo "[1/5] reset sku $SKU（库存 $STOCK）"
$MYSQL token_platform <<SQL 2>/dev/null
DELETE l FROM tb_credit_ledger l JOIN tb_token_order o ON l.reference_no=o.id
  WHERE o.sku_id=$SKU AND l.change_type='ACTIVITY_GRANT';
UPDATE tb_credit_account a
  JOIN (SELECT DISTINCT user_id FROM tb_token_order WHERE sku_id=$SKU) u ON u.user_id=a.user_id
  SET a.balance=a.balance-100000, a.update_time=NOW() WHERE a.balance>=100000;
DELETE FROM tb_token_order WHERE sku_id=$SKU;
UPDATE tb_token_sku SET stock=$STOCK, update_time=NOW() WHERE id=$SKU;
SQL
$REDIS DEL "token:granted:$SKU" >/dev/null
$REDIS SET "token:stock:$SKU" $STOCK >/dev/null

echo "[2/5] start soak jmeter (1800s)"
cd jmeter
JVM_ARGS='-Xms1g -Xmx1g' jmeter -n -t mixed-scenario.jmx -l ../tmp/results/s7.jtl \
  -Jduration=1800 -Jv1Threads=100 -JclaimThreads=0 -JkeysCsv=data/v1-keys.csv \
  > ../tmp/soak-jmeter.log 2>&1 &
JM_PID=$!
cd ..
echo "jmeter pid=$JM_PID"

burst_done=0
for tick in $(seq 1 36); do
  sleep 55
  TS=$(date +%T)
  P=$($REDIS XPENDING token:grant:stream token-grant-group 2>/dev/null | head -1 | awk '{print $1}')
  O=$($MYSQL -N -e "SELECT COUNT(*) FROM token_platform.tb_token_order WHERE sku_id=$SKU" 2>/dev/null)
  A=$($MYSQL -N -e "SELECT COUNT(*) FROM token_platform.tb_ai_call_log" 2>/dev/null)
  TC=$($MYSQL -N -e "SHOW STATUS LIKE 'Threads_connected'" 2>/dev/null | awk '{print $2}')
  STATS=$(docker stats --no-stream --format '{{.Name}} cpu={{.CPUPerc}} mem={{.MemUsage}}' "$MYSQL_CONT" "$REDIS_CONT" 2>/dev/null | tr '\n' ' ')
  echo "$TS tick=$tick pending=${P:-?} orders${SKU}=${O:-?} ai_total=${A:-?} threads_conn=${TC:-?} $STATS" | tee -a "$METRICS"

  # t≈600s：抢购突发 → 杀应用 60s → 重启（无 key）
  if [ "$burst_done" = "0" ] && [ "$tick" -ge 10 ]; then
    burst_done=1
    echo "$(date +%T) == burst 1000 users on sku $SKU ==" | tee -a "$METRICS"
    cd jmeter
    JVM_ARGS='-Xms1g -Xmx1g' jmeter -n -t token-grant-ramp.jmx -l ../tmp/results/s7-burst.jtl \
      -Jhost=127.0.0.1 -Jport=8081 -JskuId=$SKU -Jthreads=1000 -JrampUp=8 \
      -JtokensCsv=data/grant-soak.csv 2>&1 | grep -E "summary ="
    cd ..
    echo "$(date +%T) == kill app for 60s ==" | tee -a "$METRICS"
    PID=$(netstat -ano 2>/dev/null | grep ':8081' | grep LISTEN | head -1 | awk '{print $NF}')
    [ -n "$PID" ] && taskkill //PID "$PID" //F >/dev/null 2>&1
    sleep 60
    echo "$(date +%T) == restart app (keyless fast-path) ==" | tee -a "$METRICS"
    "$JAVA_BIN" -jar "$JAR" --spring.profiles.active=demo --ai.deepseek.api-key= \
      --logging.level.com.aiopenplatform=INFO --logging.level.org.springframework=WARN \
      >> tmp/backend-stress-run.log 2>&1 &
    echo "app restarted pid=$!"
    for i in $(seq 1 20); do sleep 3; curl -s -o /dev/null -w '%{http_code}' http://localhost:8081/credit-activities/list 2>/dev/null | grep -q 200 && { echo "$(date +%T) app healthy" | tee -a "$METRICS"; break; }; done
  fi

  kill -0 $JM_PID 2>/dev/null || { echo "jmeter exited early"; break; }
done
echo "$(date +%T) == orchestration done, waiting jmeter ==" | tee -a "$METRICS"
wait $JM_PID 2>/dev/null
echo "$(date +%T) == DONE ==" | tee -a "$METRICS"
