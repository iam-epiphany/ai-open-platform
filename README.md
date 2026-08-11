# AI-OpenPlatform · AI 开放平台

> 大模型平台的拉新/运营活动场景：给用户限量发放 **10 万 Tokens 免费体验包、指定模型试用额度包、企业团队共享 Token 池**。
> 读链路五级缓存 + Canal binlog 驱动一致性；写链路 Redis Lua 原子预扣 + Stream 异步发放，三层防超卖、三重防重复、补偿闭环不丢不重。

## 要解决的四个问题

| 问题 | 方案 |
| --- | --- |
| 活动开始瞬间流量高，不能打穿 MySQL | 读链路五级缓存 + 写链路 Redis Lua 预扣 + Stream 异步削峰 |
| Token 包库存有限，不能超卖 | Redis Lua 原子预扣（流量拦截）+ MySQL 乐观锁 `stock > 0`（事实源）双保险 |
| 同一个用户不能重复领取 | Lua 内 `sismember` 一人一份 / `incr` 限购 N 份 + DB 幂等校验 + Redisson 用户级锁 |
| 抢购成功后要创建订单、刷新用户权益 | Redis Stream 异步发放：订单 + token 账本 + 用户权益同事务落库 |

## 整体架构

```
┌───────────────────────────── 读链路（热点数据：活动页 / SKU 详情 / 用户权益） ─────────────────────────────┐
│                                                                                                           │
│  请求 → L0 ScopeCaching(请求内) → L1 JVM Caffeine → L2 Memcache → L3 Redis → L4 MySQL                     │
│         └────────── 逐级回源，回源后逐级写回；Redis 层 SETNX 互斥锁防击穿、空值短 TTL 防穿透 ────────────────│
│                                                                                                           │
│  缓存一致性（MySQL 为事实源，binlog 驱动）                                                                │
│  业务代码只写 MySQL → MySQL binlog → Canal-server → BinlogCacheSyncListener：                              │
│    · tb_token_sku 详情变更   → 写透传 Memcache/Redis + 广播 JVM 缓存失效（Redis Pub/Sub）                   │
│    · 活动页聚合数据变更       → 删除聚合 key，下一次读请求重建                                             │
│    · tb_user_quota 变更      → 删除权益缓存 key                                                            │
└───────────────────────────────────────────────────────────────────────────────────────────────────────────┘

┌───────────────────────────── 写链路（抢购 / 发放） ────────────────────────────────────────────────────────┐
│                                                                                                           │
│  POST /token-order/grant/{skuId}                                                                          │
│      │ ① Lua 原子（grant.lua）：库存校验 → 防重复领取 → 预扣库存 → XADD 写 Redis Stream                    │
│      ▼ 返回订单 id + 剩余库存                                                                              │
│  Redis Stream（token:grant:stream）                                                                        │
│      │ ② TokenGrantConsumer 轮询 XREADGROUP                                                               │
│      │ ③ Redisson 锁 lock:token:grant:{userId}（同用户发放串行）                                            │
│      │ ④ 订单幂等校验（重复消息跳过）                                                                       │
│      │ ⑤ MySQL 乐观锁 stock>0 扣库存（失败 → 回滚 Redis 预扣）                                              │
│      │ ⑥ 同事务：创建发放订单 + 写 token 账本 + 原子 upsert 用户权益                                        │
│      │ ⑦ XACK                                                                                              │
│      ▼                                                                                                     │
│  失败不 ACK → pending-list → GrantPendingCompensator 定时 XCLAIM 重放；投递 3 次仍失败 ACK 丢弃（死信留痕）  │
└───────────────────────────────────────────────────────────────────────────────────────────────────────────┘
```

## 业务与接口

| 模块 | 接口 | 说明 |
| --- | --- | --- |
| Token 包 SKU | `POST /token-sku` | 管理端新增 Token 包（预热 Redis 库存） |
| | `PUT /token-sku` | 管理端更新 SKU（只写 DB，缓存同步交给 binlog） |
| | `GET /token-sku/{id}` | SKU 详情（五级缓存热点读） |
| 活动页 | `GET /token-activity/list` | 在售活动列表（活动 + 各自 SKU 聚合），供前端列表页 |
| | `GET /token-activity/{id}` | 活动页聚合数据：活动信息 + SKU 列表（五级缓存热点读） |
| 抢购 | `POST /token-order/grant/{skuId}` | Lua 原子预扣 + Stream 异步发放，直接返回订单 id |
| 订单 | `GET /token-order/user` | 我的发放订单 |
| 权益 | `GET /user-quota/me?modelId=0` | 我的 Token 权益（五级缓存热点读） |
| AI 调用 | `GET /ai/models` | 模型目录（在售 SKU 按 modelId 去重，公开放行） |
| | `POST /ai/chat` | 模型调用（模拟计费）：幂等 → 余额预检 → 乐观锁扣减 → 账本(2) + 调用日志同事务 |
| 应用/密钥 | `POST /apps`、`GET /apps`、`POST /apps/{id}/keys`、`PUT /apps/keys/{keyId}`、`DELETE /apps/{id}` | 应用与 API Key 管理（明文仅创建时返回一次，库中存 SHA-256 哈希） |
| 账单 | `GET /billing/summary`、`/billing/records`、`/billing/daily` | 余额池/流水/每日消耗统计 |
| 管理后台 | `GET /admin/overview`、`/admin/call-logs`、`/admin/skus`、`PUT /admin/quota` | 数据总览/调用日志/SKU 管理/额度调整（admin.phones 白名单鉴权，调额写账本=审计） |

## 部署步骤（Demo 环境，一键起全套中间件）

```bash
# 1. 启动 MySQL(5.7, 开 binlog, 自动导入建表+种子数据) + Redis + Memcached + Canal-server
docker compose up -d

# 2. 启动应用（demo profile 连接 3307 的 MySQL）
mvn spring-boot:run -Dspring-boot.run.profiles=demo
```

启动日志应依次出现：库存预热（`token:stock:1` 等）→ Stream 消费组创建 → `Canal binlog 监听已连接`。

### 前端（nginx 静态托管）

页面在 `nginx-1.18.0/html/token/`，由 nginx（8080）托管，请求统一加 `/api` 前缀、由 nginx 剥离后转发到后端 8081：

```bash
# 3. 启动前端（nginx-1.18.0/conf/nginx.conf 已配置 root html/token 与 /api 转发）
nginx-1.18.0/nginx.exe
# 浏览器访问 http://localhost:8080
```

| 页面 | 说明 |
| --- | --- |
| `index.html` | 活动列表（在售活动 + Token 包聚合） |
| `detail.html` | 活动详情：倒计时、库存、抢购（`POST /token-order/grant/{skuId}`） |
| `orders.html` | 我的发放订单 |
| `me.html` | 个人中心：Token 余额（`GET /user-quota/me`） |
| `login.html` | 手机验证码登录 |

前端为纯静态 Vue2 + Element UI（库文件本地化，离线可用），无构建步骤。

### 运行环境要求

- **JDK 8 或 17+ 均可**。JDK 17+ 运行 MyBatis-Plus 3.4.3 需要放开模块访问（IDE 的 VM options 或命令行加）：

```
--add-opens java.base/java.lang=ALL-UNNAMED --add-opens java.base/java.lang.invoke=ALL-UNNAMED --add-opens java.base/java.util=ALL-UNNAMED
```

- **为什么用 Docker MySQL 5.7（3307）**：Canal 1.1.7 客户端不兼容 MySQL 9.x（`mysql_native_password` 认证插件被 9.0 移除）。
  若你本机 MySQL 是 5.7/8.0 且已开 binlog（`log-bin` + `binlog_format=ROW` + `binlog_row_image=FULL`），
  可把 `application-demo.yaml` 的 datasource 改回 3306 并使用本机库（先导入 `db/token_base.sql` + `db/token_platform.sql`，建 canal 账号）。
- **本机 MySQL 无法开 binlog 时**：`application.yaml` 中 `canal.enabled: false`，缓存同步自动降级为 Cache Aside（手动删缓存），读/写链路其余功能不受影响。

## 核心机制说明

### 读链路（五级缓存）

`MultiLevelCacheService.get()` 逐级查询：ScopeCaching（请求内）→ Caffeine（JVM）→ Memcache → Redis → MySQL 回源；
命中低层后逐级写回，DEBUG 日志打印各级命中（`【缓存命中 L1 Caffeine】` 等）。

- **防击穿**：Redis 层 SETNX 互斥锁，回源期间其他请求休眠重试
- **防穿透**：DB 不存在时写入空值短 TTL 缓存
- **请求内缓存**：`ScopeCacheInterceptor` 在请求开始/结束时清理 ThreadLocal，同一请求多次读取零开销

### 写链路（Redis Lua + Stream 异步发放）

`grant.lua` 在单个 Redis 命令内原子完成：

```
库存校验(get stockKey) → 防重复(sismember 一人一份 / incr 限购 N 份) → 预扣(incrby -1) → XADD 写 Stream
```

XADD 与预扣同一原子操作，杜绝「预扣成功但消息丢失」；XADD 失败自动回滚预扣。

### 异步发放（消费者）

`TokenGrantConsumer` 轮询 XREADGROUP 新消息：Redisson 用户级锁 → 订单幂等 → 乐观锁扣库存 → 创建订单 + 写账本 + 更新权益（同事务）→ XACK。

**失败语义**：处理异常不 ACK → 消息滞留 pending-list → `GrantPendingCompensator` 每 30s 扫描，XCLAIM 认领闲置 >60s 的消息重放；投递超 3 次 ACK 丢弃并记错误日志（死信留痕）。

**防重复发放三重保证**：Redis Lua `sismember` 预扣去重 + Redisson 用户级锁 + DB 幂等校验（订单 id 唯一 / 一人一份计数）。

### binlog 驱动缓存同步（Canal）

`BinlogCacheSyncListener` 用官方 canal-client（手动客户端模式）连接 canal-server（11111），订阅 `token_platform\.(tb_token_.*|tb_user_quota)`：

- `tb_token_sku` 变更 → 写透传 Memcache/Redis + Redis Pub/Sub 广播 JVM 缓存失效（跨节点），并删除包含该 SKU 的活动聚合 key
- `tb_token_activity` / `tb_user_quota` 变更 → 删除缓存 key，下一次读请求重建

> ⚠️ 配置注意：`application.yaml` 的 `canal.filter` 是 YAML plain scalar（不做转义），**写单反斜杠** `token_platform\.(tb_token_.*|tb_user_quota)`；
> 若写成 `\\.` 会把正则变成「匹配字面反斜杠」，导致订阅永远过滤不到事件。

### 依赖说明（重要）

Canal 官方 jar（1.1.7）未发布到 Maven 中央仓库/阿里云镜像，故从官方 GitHub Release `canal.example-1.1.7.tar.gz` 提取，
以**项目内 file:// 仓库**形式自包含引入：

- `libs/repo/`：`canal.client / canal.protocol / canal.common` 三个 jar + 最小 pom（pom.xml 中 `<repositories>` 声明 `project-libs` 仓库）
- 其余（protobuf-java、fastjson、commons-lang）从阿里云镜像正常拉取
- 仓库自包含，任何机器 clone 后可直接 `mvn package`

## 验证建议

```bash
# 1. 活动页/详情/权益读取（看 DEBUG 日志各级缓存命中）
curl http://localhost:8081/token-activity/1
curl http://localhost:8081/token-sku/1

# 2. 登录后抢购（验证无超卖、不重复领取；防刷频控：同 IP 同接口 60s 限 30 次）
curl -X POST http://localhost:8081/user/code?phone=13686869696
# 验证码在 Redis：docker exec token-redis redis-cli -a 123456 GET "login:code:13686869696"
curl -X POST http://localhost:8081/user/login -H "Content-Type: application/json" -d '{"phone":"13686869696","code":"479204"}'
curl -X POST http://localhost:8081/token-order/grant/1 -H "authorization: <token>"

# 3. 观察异步发放：订单/账本/权益落库 + Stream ACK 日志
# 4. 管理端改 SKU（只写 DB）→ 看「binlog 写透传」日志 + 接口立即读到新值（Canal 驱动同步）
curl -X PUT http://localhost:8081/token-sku -H "Content-Type: application/json" -d '{"id":1,"stock":999}'
```

已实测验证（Demo 环境）：

- 10 用户并发抢购同一 SKU：10 单成功、DB 库存 = Redis 库存（无超卖）
- 同一新用户并发 10 次抢购限购 1 份的 SKU：恰好 1 次成功，其余「不能重复领取」（Lua 原子性）
- 企业共享池（限购 10 份）并发 10 次：恰好 10 单（限购计数原子）
- 重复领取拦截：已领取用户再抢全部拒绝，订单不重复
- 防刷频控：同 IP 同接口超限后返回「请求过于频繁，请稍后再试」
- SKU 变更（PUT）→ binlog → Canal → 写透传 Memcache/Redis + 广播 JVM 失效 + 活动聚合重建，接口即时读到新值
