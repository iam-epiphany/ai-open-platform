-- 在 Stream 消费完成后执行。将 :sku_id 替换为本次压测 SKU ID。

-- 1. 成功订单总数不得超过压测前库存；同一用户不得超过 SKU 的 limit_count。
SELECT sku_id, COUNT(*) AS successful_orders, COUNT(DISTINCT user_id) AS distinct_users
FROM tb_token_order
WHERE sku_id = :sku_id
GROUP BY sku_id;

SELECT user_id, COUNT(*) AS orders_per_user
FROM tb_token_order
WHERE sku_id = :sku_id
GROUP BY user_id
HAVING COUNT(*) > 1;

-- 2. MySQL 事实库存与 Redis 预扣库存必须相同。
SELECT id, stock AS mysql_stock
FROM tb_token_sku
WHERE id = :sku_id;

-- Redis 中执行：GET token:stock:<sku_id>
-- Redis 中执行：XPENDING token:grant:stream token-grant-group
