/*
 Token 包秒杀平台业务表（在 dawang-dianping 库中执行）
 前置：先导入 hmdp.sql（基础库，含 tb_user 等）
 说明：本文件中的表由 Canal 订阅 binlog（dawang-dianping\.tb_token_.*），
       表结构变更后缓存同步策略见 README「binlog 驱动缓存同步」
*/

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- ----------------------------
-- Table structure for tb_token_sku（Token 包 SKU）
-- ----------------------------
DROP TABLE IF EXISTS `tb_token_sku`;
CREATE TABLE `tb_token_sku` (
  `id` bigint(20) UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `model_name` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL DEFAULT '' COMMENT '模型名称，如 deepseek-r1 / qwen-plus',
  `model_id` bigint(20) NOT NULL DEFAULT 0 COMMENT '模型id：0=通用额度池；>0=指定模型额度',
  `package_name` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT 'Token 包名称',
  `token_amount` bigint(20) NOT NULL COMMENT 'Token 额度（个）',
  `type` tinyint(4) NOT NULL DEFAULT 1 COMMENT '包类型：1=限时体验包（一人一份）；2=企业团队共享池（限购 N 份）',
  `stock` int(11) NOT NULL DEFAULT 0 COMMENT '库存',
  `limit_count` int(11) NOT NULL DEFAULT 1 COMMENT '每人限购数量：type=1 恒为 1；type=2 为 N',
  `status` tinyint(4) NOT NULL DEFAULT 1 COMMENT '状态：0=下架；1=上架',
  `begin_time` datetime DEFAULT NULL COMMENT '领取开始时间',
  `end_time` datetime DEFAULT NULL COMMENT '领取结束时间',
  `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 4 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci ROW_FORMAT = Compact;

-- ----------------------------
-- Records of tb_token_sku（种子数据）
-- ----------------------------
INSERT INTO `tb_token_sku` VALUES
(1, 'deepseek-r1', 1, '10万 Tokens 免费体验包（新用户拉新）', 100000, 1, 1000, 1, 1, '2026-01-01 00:00:00', '2026-12-31 23:59:59', '2026-08-01 10:00:00', '2026-08-01 10:00:00'),
(2, 'qwen-plus', 2, '指定模型试用额度包（500k Tokens）', 500000, 1, 500, 1, 1, '2026-01-01 00:00:00', '2026-12-31 23:59:59', '2026-08-01 10:00:00', '2026-08-01 10:00:00'),
(3, '', 0, '企业团队共享 Token 池（2M Tokens，限购 10 份）', 2000000, 2, 300, 10, 1, '2026-01-01 00:00:00', '2026-12-31 23:59:59', '2026-08-01 10:00:00', '2026-08-01 10:00:00');

-- ----------------------------
-- Table structure for tb_token_activity（平台活动页聚合）
-- ----------------------------
DROP TABLE IF EXISTS `tb_token_activity`;
CREATE TABLE `tb_token_activity` (
  `id` bigint(20) UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `title` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '活动标题',
  `banner` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT 'Banner 图片地址',
  `sku_ids` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '活动包含的 SKU id 列表，逗号分隔，如 "1,2,3"',
  `start_time` datetime DEFAULT NULL COMMENT '活动开始时间',
  `end_time` datetime DEFAULT NULL COMMENT '活动结束时间',
  `status` tinyint(4) NOT NULL DEFAULT 1 COMMENT '状态：0=下线；1=上线',
  `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 3 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci ROW_FORMAT = Compact;

-- ----------------------------
-- Records of tb_token_activity（种子数据）
-- ----------------------------
INSERT INTO `tb_token_activity` VALUES
(1, '新用户注册拉新 · 限量免费体验包', '/imgs/icons/activity-laxin.png', '1,2', '2026-01-01 00:00:00', '2026-12-31 23:59:59', 1, '2026-08-01 10:00:00', '2026-08-01 10:00:00'),
(2, '企业团队共享 Token 池', '/imgs/icons/activity-team.png', '3', '2026-01-01 00:00:00', '2026-12-31 23:59:59', 1, '2026-08-01 10:00:00', '2026-08-01 10:00:00');

-- ----------------------------
-- Table structure for tb_token_order（Token 发放订单，订单号=雪花 id）
-- ----------------------------
DROP TABLE IF EXISTS `tb_token_order`;
CREATE TABLE `tb_token_order` (
  `id` bigint(20) NOT NULL COMMENT '主键（RedisIdWorker 雪花号，即订单号）',
  `user_id` bigint(20) UNSIGNED NOT NULL COMMENT '领取用户 id',
  `sku_id` bigint(20) UNSIGNED NOT NULL COMMENT 'Token 包 SKU id',
  `token_amount` bigint(20) NOT NULL COMMENT '发放的 Token 额度（个），下单时快照',
  `status` tinyint(4) NOT NULL DEFAULT 1 COMMENT '订单状态：0=待发放；1=已发放',
  `channel` tinyint(4) NOT NULL DEFAULT 1 COMMENT '发放渠道：1=拉新活动；2=企业团队共享池',
  `create_time` datetime DEFAULT NULL COMMENT '下单（抢购成功）时间',
  `grant_time` datetime DEFAULT NULL COMMENT '实际发放时间',
  `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_user_id`(`user_id`) USING BTREE,
  INDEX `idx_sku_id`(`sku_id`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci ROW_FORMAT = Compact;

-- ----------------------------
-- Table structure for tb_token_ledger（Token 账本流水）
-- ----------------------------
DROP TABLE IF EXISTS `tb_token_ledger`;
CREATE TABLE `tb_token_ledger` (
  `id` bigint(20) UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id` bigint(20) UNSIGNED NOT NULL COMMENT '用户 id',
  `order_id` bigint(20) NOT NULL COMMENT '关联订单 id',
  `change_type` tinyint(4) NOT NULL DEFAULT 1 COMMENT '变动类型：1=发放；2=消耗',
  `change_amount` bigint(20) NOT NULL COMMENT '变动额度（个）',
  `balance_after` bigint(20) NOT NULL DEFAULT 0 COMMENT '变动后余额（个）',
  `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_user_id`(`user_id`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 1 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci ROW_FORMAT = Compact;

-- ----------------------------
-- Table structure for tb_user_quota（用户 Token 权益）
-- ----------------------------
DROP TABLE IF EXISTS `tb_user_quota`;
CREATE TABLE `tb_user_quota` (
  `id` bigint(20) UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id` bigint(20) UNSIGNED NOT NULL COMMENT '用户 id',
  `model_id` bigint(20) NOT NULL DEFAULT 0 COMMENT '模型 id：0=通用额度池；>0=指定模型额度',
  `total_tokens` bigint(20) NOT NULL DEFAULT 0 COMMENT '累计发放额度（个）',
  `used_tokens` bigint(20) NOT NULL DEFAULT 0 COMMENT '已消耗额度（个）',
  `balance` bigint(20) NOT NULL DEFAULT 0 COMMENT '可用余额（个）= total - used',
  `version` int(11) NOT NULL DEFAULT 1 COMMENT '乐观锁版本号',
  `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_user_model`(`user_id`, `model_id`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 1 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci ROW_FORMAT = Compact;

SET FOREIGN_KEY_CHECKS = 1;
