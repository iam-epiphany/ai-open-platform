#!/usr/bin/env bash
# =============================================================================
# AI-OpenPlatform 压测数据准备脚本（一次性幂等执行，重复执行会重置压测数据）
#
# 环境前提（默认值可按需覆盖）：
#   MYSQL_CONT=token-mysql   REDIS_CONT=token-redis
#   MYSQL_PWD=20030226       REDIS_PWD=123456
# 用法：bash jmeter/setup/prepare-stress-data.sh
#
# 产出：
#   抢购 SKU/活动（压测专用固定 id）
#     1001: S2 稀缺一致性场景 stock=100     1002: S3 容量/积压 stock=1000
#     1004: S7 稳定性混合    stock=500
#   tb_user 压测用户 5000001~5003000；其中 5000001~5000800 为 /v1 网关用户，
#     每人 tb_app+tb_api_key(tok_ 明文可查 v1-keys.csv)+deepseek 授权+100 万 Credits；
#     5001001~5003000 为抢购用户（s2/s3/s7 分段），与网关用户不重叠
#   Redis：login:token:*（等价登录态，TTL 2h）；token:stock:* 预热；清理 granted/count
#   JMeter 数据文件 jmeter/data/：grant-s2.csv(500) / grant-s3.csv(1000)
#     / grant-s7.csv(500) / v1-keys.csv(800)
# =============================================================================
set -euo pipefail

MYSQL_CONT="${MYSQL_CONT:-token-mysql}"
REDIS_CONT="${REDIS_CONT:-token-redis}"
MYSQL_PWD="${MYSQL_PWD:-20030226}"
REDIS_PWD="${REDIS_PWD:-123456}"
DB=token_platform
TOTAL_USERS=3000   # id 5000001..5003000
V1_USERS=800       # 前 800 个网关用户造 key
HERE="$(cd "$(dirname "$0")" && pwd)"
DATA_DIR="$HERE/../data"
mkdir -p "$DATA_DIR"
SCRATCH="$(mktemp)"

rndhex() { od -An -N16 -tx1 /dev/urandom | tr -d ' \n'; }

echo "[1/6] 清理压测残留 + SKU 与活动（重建 1001/1002/1004）"
docker exec -i "$MYSQL_CONT" mysql -uroot -p"$MYSQL_PWD" --default-character-set=utf8mb4 "$DB" <<'SQL'
-- 清掉上次压测产生的：发放账本（按订单号关联）、订单、账户、调用审计
DELETE l FROM tb_credit_ledger l JOIN tb_token_order o ON l.reference_no=o.id
  WHERE o.sku_id IN (1001,1002,1004) AND l.change_type='ACTIVITY_GRANT';
DELETE FROM tb_token_order WHERE sku_id IN (1001,1002,1004);
DELETE FROM tb_credit_account WHERE user_id BETWEEN 5000001 AND 5003000;
DELETE FROM tb_ai_call_log WHERE user_id BETWEEN 5000001 AND 5003000;
DELETE FROM tb_token_sku WHERE id IN (1001,1002,1003,1004);
DELETE FROM tb_token_activity WHERE id IN (1001,1002,1004);
INSERT INTO tb_token_sku(id,model_name,model_id,package_name,token_amount,type,stock,limit_count,status,begin_time,end_time,create_time,update_time) VALUES
(1001,'',0,'PT-S2-scarce-100',100000,1,100,1,1,'2026-01-01 00:00:00','2026-12-31 23:59:59',NOW(),NOW()),
(1002,'',0,'PT-S3-capacity-1000',100000,1,1000,1,1,'2026-01-01 00:00:00','2026-12-31 23:59:59',NOW(),NOW()),
(1004,'',0,'PT-S7-mixed-500',100000,1,500,1,1,'2026-01-01 00:00:00','2026-12-31 23:59:59',NOW(),NOW())
ON DUPLICATE KEY UPDATE stock=VALUES(stock),status=VALUES(status),update_time=NOW();
INSERT INTO tb_token_activity(id,title,banner,sku_ids,start_time,end_time,status,create_time,update_time) VALUES
(1001,'PT-S2','','1001','2026-01-01 00:00:00','2026-12-31 23:59:59',1,NOW(),NOW()),
(1002,'PT-S3','','1002','2026-01-01 00:00:00','2026-12-31 23:59:59',1,NOW(),NOW()),
(1004,'PT-S7','','1004','2026-01-01 00:00:00','2026-12-31 23:59:59',1,NOW(),NOW())
ON DUPLICATE KEY UPDATE status=VALUES(status),update_time=NOW();
SQL

echo "[2/6] 批量压测用户 5000001~5003000"
{
  echo "DELETE FROM tb_user WHERE id BETWEEN 5000001 AND 5003000;"
  echo "INSERT INTO tb_user(id,phone,password,nick_name,icon,create_time,update_time) VALUES"
  for i in $(seq 1 "$TOTAL_USERS"); do
    uid=$((5000000 + i)); phone=$((19900000000 + i))
    printf "(%d,'%d','','pt-user-%d','',NOW(),NOW())%s\n" "$uid" "$phone" "$uid" "$([ "$i" -lt "$TOTAL_USERS" ] && echo , || echo ';')"
  done
} | docker exec -i "$MYSQL_CONT" mysql -uroot -p"$MYSQL_PWD" --default-character-set=utf8mb4 "$DB"

echo "[3/6] /v1 网关用户造数：app+api_key+授权+100 万 Credits"
KEY_SQL="$(mktemp)"
{
  echo "DELETE FROM tb_api_key WHERE app_id BETWEEN 1000001 AND 1000800;"
  echo "DELETE FROM tb_app_model WHERE app_id BETWEEN 1000001 AND 1000800;"
  echo "DELETE FROM tb_app WHERE id BETWEEN 1000001 AND 1000800;"
} > "$KEY_SQL"
for i in $(seq 1 "$V1_USERS"); do
  uid=$((5000000 + i)); app_id=$((1000000 + i))
  raw="tok_$(rndhex)"
  hash="$(printf '%s' "$raw" | sha256sum | cut -d' ' -f1)"
  {
    printf "INSERT INTO tb_app(id,user_id,app_name,description,status,create_time,update_time) VALUES(%d,%d,'pt-app-%d','',1,NOW(),NOW()) ON DUPLICATE KEY UPDATE update_time=NOW();\n" "$app_id" "$uid" "$uid"
    printf "INSERT INTO tb_api_key(app_id,user_id,key_hash,prefix,status,create_time,update_time) VALUES(%d,%d,'%s','%s',1,NOW(),NOW());\n" "$app_id" "$uid" "$hash" "${raw:0:12}"
    printf "INSERT IGNORE INTO tb_app_model(app_id,model_id) VALUES(%d,1);\n" "$app_id"
    printf "INSERT INTO tb_credit_account(user_id,balance,frozen_balance,update_time) VALUES(%d,1000000,0,NOW()) ON DUPLICATE KEY UPDATE balance=1000000,frozen_balance=0,update_time=NOW();\n" "$uid"
  } >> "$KEY_SQL"
  printf "%s,10.201.%d.%d\n" "$raw" "$(( (i-1)/250 + 1 ))" "$(( (i-1)%250 + 1 ))"
done > "$DATA_DIR/v1-keys.csv"
docker exec -i "$MYSQL_CONT" mysql -uroot -p"$MYSQL_PWD" --default-character-set=utf8mb4 "$DB" < "$KEY_SQL"
rm -f "$KEY_SQL"

echo "[4/6] Redis：登录 token（逐行命令单连接执行）+ 库存预热"
CMD_FILE="$(mktemp)"
for i in $(seq 1 "$TOTAL_USERS"); do
  uid=$((5000000 + i))
  token="$(rndhex)"
  printf 'HSET login:token:%s id %s nickName pt-user-%s\n' "$token" "$uid" "$uid" >> "$CMD_FILE"
  printf 'EXPIRE login:token:%s 7200\n' "$token" >> "$CMD_FILE"
  printf '%d %s\n' "$uid" "$token" >> "$SCRATCH"
done
docker exec -i "$REDIS_CONT" redis-cli -a "$REDIS_PWD" --no-auth-warning >/dev/null < "$CMD_FILE"
rm -f "$CMD_FILE"
docker exec "$REDIS_CONT" redis-cli -a "$REDIS_PWD" --no-auth-warning DEL token:stock:1001 token:stock:1002 token:stock:1004 token:granted:1001 token:granted:1002 token:granted:1004 >/dev/null
docker exec "$REDIS_CONT" redis-cli -a "$REDIS_PWD" --no-auth-warning SET token:stock:1001 100 >/dev/null
docker exec "$REDIS_CONT" redis-cli -a "$REDIS_PWD" --no-auth-warning SET token:stock:1002 1000 >/dev/null
docker exec "$REDIS_CONT" redis-cli -a "$REDIS_PWD" --no-auth-warning SET token:stock:1004 500 >/dev/null

echo "[5/6] JMeter CSV（token,clientIp）——用户段互不重叠：
#   /v1 网关用户 5000001~5000800（v1-keys.csv）
#   抢购 S2 5001001~5001500 / S3 5001501~5002500 / S7 5002501~5003000"
: > "$DATA_DIR/grant-s2.csv"; echo "token,clientIp" >> "$DATA_DIR/grant-s2.csv"
: > "$DATA_DIR/grant-s3.csv"; echo "token,clientIp" >> "$DATA_DIR/grant-s3.csv"
: > "$DATA_DIR/grant-s7.csv"; echo "token,clientIp" >> "$DATA_DIR/grant-s7.csv"
while read -r uid token; do
  i=$((uid - 5000000))
  if [ "$i" -ge 1001 ] && [ "$i" -le 1500 ]; then
    j=$((i - 1000))
    ip="10.200.$(( (j-1)/250 + 1 )).$(( (j-1)%250 + 1 ))"; dest=grant-s2.csv
  elif [ "$i" -ge 1501 ] && [ "$i" -le 2500 ]; then
    j=$((i - 1500))
    ip="10.200.$(( (j-1)/250 + 3 )).$(( (j-1)%250 + 1 ))"; dest=grant-s3.csv
  elif [ "$i" -ge 2501 ] && [ "$i" -le 3000 ]; then
    j=$((i - 2500))
    ip="10.200.$(( (j-1)/250 + 7 )).$(( (j-1)%250 + 1 ))"; dest=grant-s7.csv
  else
    continue
  fi
  printf '%s,%s\n' "$token" "$ip" >> "$DATA_DIR/$dest"
done < "$SCRATCH"
rm -f "$SCRATCH"

echo "[6/6] 数据自检"
docker exec "$MYSQL_CONT" mysql -uroot -p"$MYSQL_PWD" -N -e \
  "SELECT CONCAT('users:',COUNT(*)) FROM $DB.tb_user WHERE id BETWEEN 5000001 AND 5003000 UNION ALL \
   SELECT CONCAT('apps:',COUNT(*)) FROM $DB.tb_app WHERE id BETWEEN 1000001 AND 1000800 UNION ALL \
   SELECT CONCAT('api_keys:',COUNT(*)) FROM $DB.tb_api_key WHERE app_id BETWEEN 1000001 AND 1000800 UNION ALL \
   SELECT CONCAT('credit_accounts:',COUNT(*)) FROM $DB.tb_credit_account WHERE user_id BETWEEN 5000001 AND 5000800;" 2>/dev/null
docker exec "$REDIS_CONT" redis-cli -a "$REDIS_PWD" --no-auth-warning GET token:stock:1001 | tr -d '\r' | sed 's/^/stock1001:/'
wc -l "$DATA_DIR"/grant-s2.csv "$DATA_DIR"/grant-s3.csv "$DATA_DIR"/grant-s7.csv "$DATA_DIR"/v1-keys.csv
echo "DONE"
