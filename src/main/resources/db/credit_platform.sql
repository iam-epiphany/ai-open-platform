/*
 AI Open Platform canonical schema. Run after token_base.sql.
 This migration is additive, so existing demo data remains available.
*/
USE `token_platform`;
SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS `tb_model` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `code` varchar(64) NOT NULL,
  `display_name` varchar(96) NOT NULL,
  `provider` varchar(32) NOT NULL,
  `status` tinyint NOT NULL DEFAULT 1,
  `create_time` datetime NOT NULL,
  `update_time` datetime NOT NULL,
  PRIMARY KEY (`id`), UNIQUE KEY `uk_model_code` (`code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `tb_model_price` (
  `model_id` bigint unsigned NOT NULL,
  `input_credit_per_1k` decimal(12,2) NOT NULL,
  `output_credit_per_1k` decimal(12,2) NOT NULL,
  `update_time` datetime NOT NULL,
  PRIMARY KEY (`model_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `tb_app` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `user_id` bigint unsigned NOT NULL,
  `app_name` varchar(64) NOT NULL,
  `description` varchar(255) NOT NULL DEFAULT '',
  `status` tinyint NOT NULL DEFAULT 1,
  `create_time` datetime NOT NULL,
  `update_time` datetime NOT NULL,
  PRIMARY KEY (`id`), KEY `idx_app_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `tb_api_key` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `app_id` bigint unsigned NOT NULL,
  `user_id` bigint unsigned NOT NULL,
  `key_hash` char(64) NOT NULL,
  `prefix` varchar(16) NOT NULL,
  `status` tinyint NOT NULL DEFAULT 1,
  `expire_time` datetime DEFAULT NULL,
  `last_used_time` datetime DEFAULT NULL,
  `create_time` datetime NOT NULL,
  `update_time` datetime NOT NULL,
  PRIMARY KEY (`id`), UNIQUE KEY `uk_key_hash` (`key_hash`), KEY `idx_key_app` (`app_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `tb_app_model` (
  `app_id` bigint unsigned NOT NULL,
  `model_id` bigint unsigned NOT NULL,
  PRIMARY KEY (`app_id`,`model_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `tb_credit_account` (
  `user_id` bigint unsigned NOT NULL,
  `balance` bigint NOT NULL DEFAULT 0,
  `frozen_balance` bigint NOT NULL DEFAULT 0,
  `update_time` datetime NOT NULL,
  PRIMARY KEY (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `tb_credit_ledger` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `user_id` bigint unsigned NOT NULL,
  `change_type` varchar(24) NOT NULL COMMENT 'ACTIVITY_GRANT, CONSUME, ADMIN_GRANT, ADMIN_DEDUCT, REFUND',
  `change_amount` bigint NOT NULL,
  `balance_after` bigint NOT NULL,
  `reference_no` varchar(64) NOT NULL,
  `remark` varchar(255) NOT NULL DEFAULT '',
  `create_time` datetime NOT NULL,
  PRIMARY KEY (`id`), KEY `idx_ledger_user_time` (`user_id`,`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `tb_credit_purchase_order` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `order_no` varchar(64) NOT NULL,
  `user_id` bigint unsigned NOT NULL,
  `credit_amount` bigint NOT NULL,
  `payment_amount` decimal(10,2) NOT NULL,
  `status` tinyint NOT NULL COMMENT '1=mock paid',
  `paid_time` datetime DEFAULT NULL,
  `create_time` datetime NOT NULL,
  PRIMARY KEY (`id`), UNIQUE KEY `uk_credit_purchase_order` (`order_no`), KEY `idx_credit_purchase_user` (`user_id`,`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 无损将旧模型额度池并入统一 Credits 账户；参考号保证重复执行不会二次入账。
INSERT IGNORE INTO `tb_credit_account` (`user_id`,`balance`,`frozen_balance`,`update_time`)
SELECT `user_id`,0,0,NOW() FROM `tb_user_quota` GROUP BY `user_id`;
UPDATE `tb_credit_account` a
JOIN (
  SELECT q.`user_id`,SUM(q.`balance`) legacy_balance
  FROM `tb_user_quota` q
  WHERE q.`balance`>0 AND NOT EXISTS (
    SELECT 1 FROM `tb_credit_ledger` l
    WHERE l.`user_id`=q.`user_id` AND l.`reference_no`=CONCAT('LEGACY_QUOTA:',q.`user_id`)
  ) GROUP BY q.`user_id`
) x ON x.`user_id`=a.`user_id`
SET a.`balance`=a.`balance`+x.legacy_balance,a.`update_time`=NOW();
INSERT INTO `tb_credit_ledger` (`user_id`,`change_type`,`change_amount`,`balance_after`,`reference_no`,`remark`,`create_time`)
SELECT q.`user_id`,'ACTIVITY_GRANT',SUM(q.`balance`),a.`balance`,CONCAT('LEGACY_QUOTA:',q.`user_id`),'历史额度迁移为 Credits',NOW()
FROM `tb_user_quota` q JOIN `tb_credit_account` a ON a.`user_id`=q.`user_id`
WHERE q.`balance`>0 AND NOT EXISTS (
  SELECT 1 FROM `tb_credit_ledger` l
  WHERE l.`user_id`=q.`user_id` AND l.`reference_no`=CONCAT('LEGACY_QUOTA:',q.`user_id`)
) GROUP BY q.`user_id`,a.`balance`;

CREATE TABLE IF NOT EXISTS `tb_ai_call_log` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `request_id` varchar(64) NOT NULL,
  `user_id` bigint unsigned NOT NULL,
  `app_id` bigint unsigned NOT NULL,
  `model` varchar(64) NOT NULL,
  `prompt_tokens` int NOT NULL DEFAULT 0,
  `completion_tokens` int NOT NULL DEFAULT 0,
  `credit_cost` bigint NOT NULL DEFAULT 0,
  `latency` bigint NOT NULL DEFAULT 0,
  `status` tinyint NOT NULL COMMENT '1=success,0=failed',
  `error_message` varchar(500) NOT NULL DEFAULT '',
  `create_time` datetime NOT NULL,
  PRIMARY KEY (`id`), UNIQUE KEY `uk_call_request` (`request_id`), KEY `idx_call_user_time` (`user_id`,`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO `tb_model` (`id`,`code`,`display_name`,`provider`,`status`,`create_time`,`update_time`)
VALUES (1,'deepseek-chat','DeepSeek Chat','deepseek',1,NOW(),NOW())
ON DUPLICATE KEY UPDATE display_name=VALUES(display_name),provider=VALUES(provider),status=VALUES(status),update_time=NOW();
INSERT INTO `tb_model_price` (`model_id`,`input_credit_per_1k`,`output_credit_per_1k`,`update_time`)
VALUES (1,10,20,NOW())
ON DUPLICATE KEY UPDATE input_credit_per_1k=VALUES(input_credit_per_1k),output_credit_per_1k=VALUES(output_credit_per_1k),update_time=NOW();
