#!/usr/bin/env bash
# =============================================================================
# AI-OpenPlatform 极限压测数据准备（幂等，重复执行会重置本脚本范围的压测数据）
#
# 环境前提：docker compose up -d（token-mysql / token-redis），应用需先停止
# 用法：bash jmeter/setup/prepare-limit-data.sh
#
# 产出（仅限本脚本数据段，不触碰 1001/1002/1004 等既有压测数据）：
#   SKU 1010: 端到端 10k 场景   stock=10000, limit_count=1    用户 5010001~5020000（10000）
#   SKU 1011: 漂移/失败留痕验证 stock=5000,  limit_count=1    用户 5022001~5022500（500）
#   SKU 1012: 入口容量平台场景  stock=20000, limit_count=1000 用户 5020001~5022000（2000）
#   Redis：login:token:* 登录态（TTL 2h）、token:stock:* 预热、清理 granted/count/回滚标记/Stream
#   JMeter CSV：jmeter/data/grant-limit-1010.csv / 1011.csv / 1012.csv（token,clientIp）
#   应用重启后自动重建 Stream 消费组（脚本已 DEL 流）
# =============================================================================
set -euo pipefail

MYSQL_CONT="${MYSQL_CONT:-token-mysql}"
REDIS_CONT="${REDIS_CONT:-token-redis}"
MYSQL_PWD="${MYSQL_PWD:-20030226}"
REDIS_PWD="${REDIS_PWD:-123456}"
DB=token_platform
E2E_USERS=10000   # 5010001..5020000
CAP_USERS=2000    # 5020001..5022000
DRIFT_USERS=500   # 5022001..5022500
TOTAL_USERS=$((E2E_USERS + CAP_USERS + DRIFT_USERS))
HERE="$(cd "$(dirname "$0")" && pwd)"
DATA_DIR="$HERE/../data"
mkdir -p "$DATA_DIR"
SCRATCH="$(mktemp)"

echo "[1/7] 清理本段压测残留 + 重建 SKU 1010/1011/1012 与活动"
docker exec -i "$MYSQL_CONT" mysql -uroot -p"$MYSQL_PWD" --default-character-set=utf8mb4 "$DB" <<'SQL'
DELETE l FROM tb_credit_ledger l JOIN tb_token_order o ON l.reference_no = CAST(o.id AS CHAR)
  WHERE o.sku_id IN (1010,1011,1012) AND l.change_type='ACTIVITY_GRANT';
DELETE FROM tb_token_order WHERE sku_id IN (1010,1011,1012);
DELETE FROM tb_credit_account WHERE user_id BETWEEN 5010001 AND 5022500;
DELETE FROM tb_user WHERE id BETWEEN 5010001 AND 5022500;
DELETE FROM tb_token_sku WHERE id IN (1010,1011,1012);
DELETE FROM tb_token_activity WHERE id IN (1010,1011,1012);
INSERT INTO tb_token_sku(id,model_name,model_id,package_name,token_amount,type,stock,limit_count,status,begin_time,end_time,create_time,update_time) VALUES
(1010,'','0','PT-L1-e2e-10000',100000,1,10000,1,1,'2026-01-01 00:00:00','2026-12-31 23:59:59',NOW(),NOW()),
(1011,'','0','PT-L2-drift-5000',100000,1,5000,1,1,'2026-01-01 00:00:00','2026-12-31 23:59:59',NOW(),NOW()),
(1012,'','0','PT-L3-capacity-20000',100000,1,20000,1000,1,'2026-01-01 00:00:00','2026-12-31 23:59:59',NOW(),NOW())
ON DUPLICATE KEY UPDATE stock=VALUES(stock),limit_count=VALUES(limit_count),status=VALUES(status),update_time=NOW();
INSERT INTO tb_token_activity(id,title,banner,sku_ids,start_time,end_time,status,create_time,update_time) VALUES
(1010,'PT-L1','','1010','2026-01-01 00:00:00','2026-12-31 23:59:59',1,NOW(),NOW()),
(1011,'PT-L2','','1011','2026-01-01 00:00:00','2026-12-31 23:59:59',1,NOW(),NOW()),
(1012,'PT-L3','','1012','2026-01-01 00:00:00','2026-12-31 23:59:59',1,NOW(),NOW())
ON DUPLICATE KEY UPDATE status=VALUES(status),update_time=NOW();
SQL

echo "[2/7] 批量用户 5010001~5022500（${TOTAL_USERS} 个）"
{
  echo "INSERT INTO tb_user(id,phone,password,nick_name,icon,create_time,update_time) VALUES"
  for i in $(seq 1 "$TOTAL_USERS"); do
    uid=$((5010000 + i)); phone=$((29900000000 + i))
    printf "(%d,'%d','','limit-user-%d','',NOW(),NOW())%s\n" "$uid" "$phone" "$uid" "$([ "$i" -lt "$TOTAL_USERS" ] && echo , || echo ';')"
  done
} | docker exec -i "$MYSQL_CONT" mysql -uroot -p"$MYSQL_PWD" --default-character-set=utf8mb4 "$DB"

echo "[3/7] Redis：登录 token + 清理旧预扣痕迹 + 库存预热 + 清空发放流"
CMD_FILE="$(mktemp)"
for i in $(seq 1 "$TOTAL_USERS"); do
  uid=$((5010000 + i))
  # uid 前缀保证唯一（msys $RANDOM 存在低概率碰撞）；128bit 随机尾无实际碰撞风险
  token="u${uid}$(printf '%04x%04x%04x%04x%04x%04x%04x%04x' $RANDOM $RANDOM $RANDOM $RANDOM $RANDOM $RANDOM $RANDOM $RANDOM)"
  printf 'HSET login:token:%s id %s nickName limit-user-%s\n' "$token" "$uid" "$uid" >> "$CMD_FILE"
  printf 'EXPIRE login:token:%s 7200\n' "$token" >> "$CMD_FILE"
  printf '%s %s\n' "$uid" "$token" >> "$SCRATCH"
done
docker exec -i "$REDIS_CONT" redis-cli -a "$REDIS_PWD" --no-auth-warning >/dev/null < "$CMD_FILE"
rm -f "$CMD_FILE"
# 清理 granted 集合 / 限购计数 / 回滚标记 / 发放流（应用重启时重建消费组）
for sku in 1010 1011 1012; do
  docker exec "$REDIS_CONT" redis-cli -a "$REDIS_PWD" --no-auth-warning DEL "token:stock:${sku}" "token:granted:${sku}" >/dev/null
  docker exec "$REDIS_CONT" redis-cli -a "$REDIS_PWD" --no-auth-warning --scan --pattern "token:count:${sku}:*" | \
    xargs -r docker exec "$REDIS_CONT" redis-cli -a "$REDIS_PWD" --no-auth-warning DEL >/dev/null 2>&1 || true
done
docker exec "$REDIS_CONT" redis-cli -a "$REDIS_PWD" --no-auth-warning --scan --pattern 'token:rb:*' | \
  xargs -r docker exec "$REDIS_CONT" redis-cli -a "$REDIS_PWD" --no-auth-warning DEL >/dev/null 2>&1 || true
docker exec "$REDIS_CONT" redis-cli -a "$REDIS_PWD" --no-auth-warning DEL token:grant:stream >/dev/null 2>&1 || true
docker exec "$REDIS_CONT" redis-cli -a "$REDIS_PWD" --no-auth-warning SET token:stock:1010 10000 >/dev/null
docker exec "$REDIS_CONT" redis-cli -a "$REDIS_PWD" --no-auth-warning SET token:stock:1011 5000 >/dev/null
docker exec "$REDIS_CONT" redis-cli -a "$REDIS_PWD" --no-auth-warning SET token:stock:1012 20000 >/dev/null

echo "[4/7] 生成 CSV（token,clientIp，用户段互不重叠，每用户独立测试源 IP）"
gen_csv() { # $1=起始序数 $2=用户数 $3=目标文件 $4=IP 段
  : > "$3"; echo "token,clientIp" >> "$3"
  while read -r uid token; do
    i=$((uid - 5010000))
    if [ "$i" -ge "$1" ] && [ "$i" -lt "$(( $1 + $2 ))" ]; then
      j=$((i - $1 + 1))
      printf '%s,10.%s.%d.%d\n' "$token" "$4" "$(( (j-1)/250 + 1 ))" "$(( (j-1)%250 + 1 ))" >> "$3"
    fi
  done < "$SCRATCH"
}
gen_csv 1      "$E2E_USERS" "$DATA_DIR/grant-limit-1010.csv" "211"
gen_csv 10001  "$CAP_USERS" "$DATA_DIR/grant-limit-1012.csv" "212"
gen_csv 12001  "$DRIFT_USERS" "$DATA_DIR/grant-limit-1011.csv" "213"

echo "[5/7] 数据自检"
docker exec "$MYSQL_CONT" mysql -uroot -p"$MYSQL_PWD" -N -e \
  "SELECT CONCAT('limit_users:',COUNT(*)) FROM $DB.tb_user WHERE id BETWEEN 5010001 AND 5022500; \
   SELECT CONCAT('stock_db_1010:',stock) FROM $DB.tb_token_sku WHERE id=1010; \
   SELECT CONCAT('stock_db_1011:',stock) FROM $DB.tb_token_sku WHERE id=1011; \
   SELECT CONCAT('stock_db_1012:',stock) FROM $DB.tb_token_sku WHERE id=1012;" 2>/dev/null
for sku in 1010 1011 1012; do
  echo -n "stock_redis_${sku}:"
  docker exec "$REDIS_CONT" redis-cli -a "$REDIS_PWD" --no-auth-warning GET "token:stock:${sku}" | tr -d '\r'
done
wc -l "$DATA_DIR"/grant-limit-1010.csv "$DATA_DIR"/grant-limit-1011.csv "$DATA_DIR"/grant-limit-1012.csv
rm -f "$SCRATCH"
echo "DONE"
