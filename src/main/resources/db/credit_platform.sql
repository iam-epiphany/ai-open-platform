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
  `change_type` varchar(24) NOT NULL COMMENT 'RECHARGE, ACTIVITY_GRANT, CONSUME, REFUND',
  `change_amount` bigint NOT NULL,
  `balance_after` bigint NOT NULL,
  `reference_no` varchar(64) NOT NULL,
  `remark` varchar(255) NOT NULL DEFAULT '',
  `create_time` datetime NOT NULL,
  PRIMARY KEY (`id`), KEY `idx_ledger_user_time` (`user_id`,`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `tb_recharge_order` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `order_no` varchar(64) NOT NULL,
  `user_id` bigint unsigned NOT NULL,
  `credits` bigint NOT NULL,
  `amount` decimal(12,2) NOT NULL,
  `status` tinyint NOT NULL COMMENT '1=paid (simulated)',
  `paid_time` datetime DEFAULT NULL,
  `create_time` datetime NOT NULL,
  PRIMARY KEY (`id`), UNIQUE KEY `uk_recharge_order` (`order_no`), KEY `idx_recharge_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `tb_credit_activity` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `title` varchar(128) NOT NULL,
  `begin_time` datetime NOT NULL,
  `end_time` datetime NOT NULL,
  `status` tinyint NOT NULL DEFAULT 1,
  `create_time` datetime NOT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `tb_credit_package` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `activity_id` bigint unsigned NOT NULL,
  `package_name` varchar(128) NOT NULL,
  `credit_amount` bigint NOT NULL,
  `stock` int NOT NULL,
  `limit_count` int NOT NULL DEFAULT 1,
  `status` tinyint NOT NULL DEFAULT 1,
  PRIMARY KEY (`id`), KEY `idx_package_activity` (`activity_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `tb_credit_order` (
  `id` bigint unsigned NOT NULL,
  `user_id` bigint unsigned NOT NULL,
  `package_id` bigint unsigned NOT NULL,
  `credit_amount` bigint NOT NULL,
  `status` tinyint NOT NULL DEFAULT 1,
  `create_time` datetime NOT NULL,
  `grant_time` datetime DEFAULT NULL,
  PRIMARY KEY (`id`), KEY `idx_credit_order_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

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

INSERT INTO `tb_credit_activity` (`id`,`title`,`begin_time`,`end_time`,`status`,`create_time`)
VALUES (1,'新人 Credits 体验活动','2026-01-01 00:00:00','2030-12-31 23:59:59',1,NOW())
ON DUPLICATE KEY UPDATE title=VALUES(title),status=VALUES(status);
INSERT INTO `tb_credit_package` (`id`,`activity_id`,`package_name`,`credit_amount`,`stock`,`limit_count`,`status`)
VALUES (1,1,'1000 Credits 新人体验包',1000,100,1,1)
ON DUPLICATE KEY UPDATE package_name=VALUES(package_name),credit_amount=VALUES(credit_amount),status=VALUES(status);
