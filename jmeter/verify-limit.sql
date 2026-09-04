-- AI-OpenPlatform 极限压测终态校验（docker exec -i token-mysql mysql ... token_platform < verify-limit.sql）
-- 用法：在 jmeter 目录执行，或用 docker exec -i token-mysql mysql -uroot -p20030226 token_platform < jmeter/verify-limit.sql
-- 说明：只输出状态与一致性检查项；期望值（订单数=发放成功数、失败单数等）由场景上下文判定，
--       库存双端一致性需配合 Redis：GET token:stock:{skuId} 与下方 sku_stock 对照。
SELECT 'orders_by_sku_status(1010/1011/1012)' AS check_name, sku_id, status, COUNT(*) AS cnt
FROM tb_token_order WHERE sku_id IN (1010,1011,1012) GROUP BY sku_id, status ORDER BY sku_id, status;

-- 限购 1 的 SKU 不应出现同用户多笔已发放订单
SELECT 'dup_granted_sku1010_1011' AS check_name, COUNT(*) AS cnt FROM (
    SELECT user_id FROM tb_token_order
    WHERE sku_id IN (1010,1011) AND status = 1
    GROUP BY user_id, sku_id HAVING COUNT(*) > 1) t;

-- 每笔已发放订单必须有且仅有一笔 ACTIVITY_GRANT 账本（行数与金额对账）
SELECT 'granted_order_without_ledger' AS check_name, COUNT(*) AS cnt
FROM tb_token_order o LEFT JOIN tb_credit_ledger l
  ON l.reference_no = o.id AND l.change_type = 'ACTIVITY_GRANT'
WHERE o.sku_id IN (1010,1011,1012) AND o.status = 1 AND l.id IS NULL;

SELECT 'ledger_amount_mismatch' AS check_name, COUNT(*) AS cnt
FROM tb_token_order o JOIN tb_credit_ledger l
  ON l.reference_no = o.id AND l.change_type = 'ACTIVITY_GRANT'
WHERE o.sku_id IN (1010,1011,1012) AND o.status = 1 AND l.change_amount <> o.token_amount;

-- 本段用户不应存在冻结资金泄漏
SELECT 'frozen_balance' AS check_name, IFNULL(SUM(frozen_balance), 0) AS cnt
FROM tb_credit_account WHERE user_id BETWEEN 5010001 AND 5022500;

-- DB 库存终值（与 Redis token:stock:{skuId} 对照）
SELECT 'sku_db_stock' AS check_name, id AS sku_id, stock FROM tb_token_sku
WHERE id IN (1010,1011,1012) ORDER BY id;
