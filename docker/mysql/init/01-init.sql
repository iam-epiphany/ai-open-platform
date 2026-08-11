-- 演示环境初始化：建库 + 创建 canal 账号（docker-entrypoint-initdb.d 自动执行）
CREATE DATABASE IF NOT EXISTS `token_platform` DEFAULT CHARACTER SET utf8mb4;

CREATE USER 'canal'@'%' IDENTIFIED BY 'canal';
GRANT SELECT, REPLICATION SLAVE, REPLICATION CLIENT ON *.* TO 'canal'@'%';
FLUSH PRIVILEGES;
