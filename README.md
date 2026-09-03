<div align="center">

# AI-OpenPlatform

### 高并发 Credits 限量抢购 + OpenAI 兼容 AI 网关

**Spring Boot · Redis Lua / Stream · 多级缓存 · Canal binlog 同步 · MyBatis-Plus**

[![Java](https://img.shields.io/badge/Java-17-007396?logo=openjdk&logoColor=white)](pom.xml)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-2.7.18-6DB33F?logo=spring&logoColor=white)](pom.xml)
[![MySQL](https://img.shields.io/badge/MySQL-5.7+-4479A1?logo=mysql&logoColor=white)](docker/mysql/init)
[![Redis](https://img.shields.io/badge/Redis-7-DC382D?logo=redis&logoColor=white)](docker-compose.yml)
[![License](https://img.shields.io/badge/License-MIT-blue)](LICENSE)

[本地开发](#本地开发) · [Docker 部署](#docker-部署上线) · [核心接口](#核心接口) · [关键设计](#关键设计)

</div>

> AI-OpenPlatform 是一个以「高并发限量 Credits 抢购」为主题的 AI 开放平台：活动领取、模拟购买、管理员调整和 AI 模型调用，全部汇入**同一个 Credits 账户**与不可变账本，构成完整业务闭环。

系统只有一种业务余额：**Credits**。LLM 返回的 `prompt_tokens` / `completion_tokens` 只是计费依据，不是第二种用户余额。

## 业务闭环

```mermaid
flowchart LR
    A[运营后台配置活动 / Credits 包] --> B[用户限量抢购]
    B --> C[Redis Lua 原子校验库存与限领 → XADD]
    C --> D[Redis Stream Consumer → MySQL 条件扣库存]
    D --> E[订单与 Credits 账本同事务入账]
    E --> F[创建 App / tok_ API Key]
    F --> G[OpenAI-compatible API → DeepSeek 真实 usage]
    G --> H[Credits 预占 → 调用 → 多退少补结算]
    H --> I[Ledger + CallLog 审计]
```

| 环节 | 实现 |
| --- | --- |
| 抢购入口 | Redis Lua 原子完成「库存校验 + 防重复领取/限购 + 预扣 + XADD 写 Stream」，热点请求不冲击 MySQL |
| 异步落单 | Stream Consumer Group + 用户级 Redisson 锁 + DB 幂等判断 + Pending List 死信补偿 |
| 库存事实源 | 条件 SQL（`stock > 0`）扣库存，订单与 Credits 发放同一事务；Redis 预扣仅作流量拦截与削峰 |
| Credits 消费 | `balance → frozen_balance → settle/release` 预占结算，供应商失败自动释放冻结额度，结算带余额不为负守卫 |
| API Key 安全 | 明文只显示一次，数据库仅存 SHA-256 哈希与识别前缀 |
| 热点读 | 请求级 ScopeCaching → Caffeine → Redis → MySQL 四级缓存；binlog（Canal）驱动写透传与跨节点失效 |

> 为兼容已有数据，高并发活动的物理表和部分 Java 实体仍保留 `token_*` / `Token*` 历史名称；对外 API、前端产品语义和账本均已统一为 Credits。

## 快速开始

### 本地开发（源码运行）

需要 **JDK 17+、Maven 3.x、Docker**。中间件（MySQL / Redis / Canal）跑在 Docker 里，后端用 Maven 直接跑，前端为纯静态页由本机 Nginx 提供。

**第 1 步：启动中间件**

```powershell
docker compose up -d
docker compose ps   # 确认 mysql(3307, healthy) / redis(6370) / canal-server(11111)
```

**第 2 步：启动后端（终端 1）**

```powershell
# 可选：配置真实模型调用密钥
$env:DEEPSEEK_API_KEY = 'sk-...'
mvn spring-boot:run -Dspring-boot.run.profiles=demo
```

启动成功的标志：`Tomcat started on port(s): 8081`、Redis 库存预热日志、`Stream 消费组已存在`（首次为已创建）、Canal 监听已连接。

**第 3 步：启动前端（终端 2）**

前端是 Nginx 托管的静态页面（无构建步骤，改完 JS 刷新浏览器即可生效）：

```powershell
cd nginx-1.18.0
.\nginx.exe        # 首次启动；重启配置用 .\nginx.exe -s reload
```

打开 [http://localhost:8080](http://localhost:8080)。

**验证码登录**：`POST /user/code?phone=13686869696` 后，验证码打印在后端终端日志（`验证码：xxxxxx`）——演示环境不接真实短信。

**改代码后重启**：Java Controller / Service 修改后，必须停止旧的 8081 进程再 `mvn spring-boot:run`，否则页面会请求到旧路由返回 404；前端静态文件无需重启。日志中反复出现无意义的 404 时先确认这一步。

**中间件端口与直连**：Demo 的 Docker MySQL 映射为 `3307`、Redis 为 `6370`。静态页经 Nginx 访问时对外 Base URL 为 `http://<host>/api/v1`；直连 Spring Boot 为 `http://<host>:8081/v1`。

### Docker 部署上线

单命令部署全部五个容器：MySQL（带 binlog，首次启动自动建库建表与种子数据）、Redis、Canal、后端 App、前端 Nginx。数据写入命名卷，重建容器不丢失。

```powershell
git clone <你的仓库地址> && cd AI-OpenPlatform
Copy-Item .env.example .env   # 可选；至少确认下面的环境变量
docker compose -f docker-compose.deploy.yml up -d --build
docker compose -f docker-compose.deploy.yml ps
```

打开：

- 产品入口：<http://localhost:8080>
- 后端直连：<http://localhost:8081>
- 健康检查：`curl http://localhost:8080/api/credit-activities/list`

| 环境变量 | 默认值 | 说明 |
| --- | --- | --- |
| `DEEPSEEK_API_KEY` | 空 | 配置后 `/v1/chat/completions` 才能调用真实 DeepSeek；未配置时预占 Credits 会释放并保留失败审计 |
| `ADMIN_PASSWORD` | `123456` | 管理后台账号密码（`admin.username` 固定为 `admin`） |
| `MYSQL_PASSWORD` | `20030226` | MySQL root 密码，需与 `docker/mysql/init/01-init.sql` 一致 |

停止与更新：

```powershell
docker compose -f docker-compose.deploy.yml down          # 停服务（-v 会连数据卷一起删，慎用）
docker compose -f docker-compose.deploy.yml up -d --build # 拉新代码后重新构建并滚动更新
```

## 核心接口

| 场景 | 接口 | 鉴权 |
| --- | --- | --- |
| 活动列表 / 详情 | `GET /credit-activities/list`, `GET /credit-activities/{id}` | 公开 |
| 抢购 Credits 包 | `POST /credit-orders/claim/{skuId}` | 登录态 |
| Credits 账户 / 账本 | `GET /credits/account`, `/summary`, `/daily`, `/records` | 登录态 |
| 购买 Credits（模拟支付） | `POST /credits/purchase` | 登录态 |
| App / API Key | `POST /apps`, `POST /apps/{id}/keys` | 登录态 |
| 模型列表 | `GET /v1/models` | Bearer API Key |
| 模型调用 | `POST /v1/chat/completions` | Bearer API Key |
| 活动 / Credits 包运营 | `/admin/credit-activities`, `/admin/credit-packages` | 管理员 |
| 用户 Credits 调整 | `PUT /admin/credits` | 管理员 |

### 对外调用示例

```powershell
# 1. 登录拿 token（验证码见后端日志）
curl http://localhost:8081/user/code?phone=13686869696 -Method POST
curl http://localhost:8081/user/login -Method POST -ContentType "application/json" `
  -Body '{"phone":"13686869696","code":"<验证码>"}'

# 2. 创建应用拿到 API Key（明文只显示一次）
curl http://localhost:8081/apps -Method POST -ContentType "application/json" `
  -Headers @{ Authorization = '<登录token>' } -Body '{"appName":"demo-client"}'

# 3. 调用 OpenAI 兼容网关（登录 token 不能用于 /v1/**）
curl http://localhost:8081/v1/chat/completions -Method POST `
  -Headers @{ Authorization = 'Bearer tok_your_api_key'; 'Content-Type' = 'application/json' } `
  -Body '{"model":"deepseek-chat","messages":[{"role":"user","content":"你好"}],"max_tokens":128}'
```

当前实现 OpenAI Chat Completions 兼容协议，可用于 curl、Python SDK 及支持自定义 Base URL 的普通 Agent；未实现 Responses API，也暂不支持 `stream=true`。

## 技术栈

| 层 | 选型 | 作用 |
| --- | --- | --- |
| 语言 / 构建 | Java 17 · Maven（统一输出 17 字节码） | 兼容新旧 JDK |
| Web | Spring Boot 2.7.18 · Tomcat | REST 接口与拦截器链 |
| 持久层 | MyBatis-Plus 3.5.9 · MySQL 5.7（ROW binlog） | ORM、分页与条件更新 |
| 缓存 | Caffeine（L1）· Redis（L2/L3）· Canal（binlog 驱动） | 四级读缓存与跨节点失效 |
| 高并发链路 | Redis Lua · Redis Stream + Consumer Group | 原子预扣、异步落单与补偿 |
| 锁 | Redisson | 用户级发放串行化 |
| 网关 | 自研 OpenAI 兼容层 · RestTemplate → DeepSeek | API Key 鉴权、预占结算 |
| 前端 | 原生 JS + Vue 2（CDN）· Nginx | 纯静态页，改完即刷新 |
| 部署 | Dockerfile · Docker Compose · Nginx | 一键构建与上线 |

## 关键设计

<details>
<summary><strong>高并发抢购：为什么不会超卖，也不会击穿数据库</strong></summary>

<br />

- **Redis Lua 单点原子**：库存校验、限购判断、预扣、XADD 在同一个 Lua 脚本内完成；脚本按 orderId 生成的 id 由 RedisIdWorker 保证全局唯一，天然成为订单号。
- **DB 是事实源**：Redis 预扣只做流量拦截；消费者在事务内以 `stock > 0` 条件扣减，扣不到就回滚 Redis 预扣（按 orderId 幂等，不重复恢复库存）。
- **失败补偿闭环**：处理失败不 ACK 进入 Pending List，补偿任务 XCLAIM 重试；投递超过 3 次的死信先确认订单未落库、回滚预扣后再 ACK 丢弃。
- **请求限流与黑名单**：IP × 接口维度固定窗口限流（/v1 网关放宽阈值）；登录失败计数拉黑手机号与真实 IP（Nginx 追加 X-Forwarded-For，客户端伪造段被忽略）。

</details>

<details>
<summary><strong>Credits 账户：预占结算与不可变账本</strong></summary>

<br />

- 每次调用先按请求字符数与 max_tokens 保守估算预占：`balance -= reserve`、`frozen_balance += reserve`。
- 供应商返回真实 usage 后结算：`frozen_balance -= reserve`、`balance += reserve - actual`，条件 SQL 保证冻结额与余额永不为负；余额不足的结算直接失败并保留现场。
- 调用失败（网络、HTTP 错误、无 API Key）释放冻结额，并写 `status=0` 的审计日志；成功写 `CONSUME` 流水 + `tb_ai_call_log`，同一事务。
- 所有获得 Credits 的路径（`ACTIVITY_GRANT` / `PURCHASE` / `ADMIN_GRANT`）与消费路径都写 `tb_credit_ledger`，`reference_no` 可追溯、可幂等。

</details>

<details>
<summary><strong>多级缓存与 binlog 同步</strong></summary>

<br />

- 读链路：ScopeCaching（请求内）→ Caffeine（JVM）→ Redis → MySQL；Redis 回源带互斥锁（持有者令牌 + Lua 释放）防击穿，空值短 TTL 防穿透。
- 写链路：业务代码只写 MySQL；Canal 订阅 `tb_token_.*` 的 ROW binlog，SKU 变更写透传 Redis 并广播 `cache:invalidate` 使各节点 Caffeine 失效，活动页聚合 key 删除后由读请求重建。
- `canal.enabled: false` 时降级为 Cache Aside：业务代码在写后手动删缓存，领取与计费链路不受影响。

</details>

## 验证

```powershell
mvn -q test
mvn -q -DskipTests package
git diff --check
```

调用真实 DeepSeek 前必须配置 `DEEPSEEK_API_KEY`。未配置或供应商调用失败时，已预占的 Credits 会被释放并保留失败审计日志。

## 目录结构

```text
AI-OpenPlatform/
├── src/main/java/...          # 后端：cache/ consumer/ gateway/ controller/ service/
├── src/main/resources/
│   ├── application.yaml       # 默认配置（密码走环境变量占位）
│   ├── application-demo.yaml  # 本地开发（docker compose 中间件 3307/6370）
│   ├── application-docker.yaml# Docker 部署（容器内网服务名）
│   ├── lua/                   # grant.lua / rollback_grant.lua
│   └── db/credit_platform.sql # Credits 事实表建表 + 旧额度迁移
├── docker/
│   ├── mysql/init/            # 建库 + token 基础表（01~03）
│   ├── canal/conf/            # canal 实例配置（订阅 tb_token_.*）
│   └── deploy/nginx.conf      # 部署版前端反代配置
├── nginx-1.18.0/              # 本地前端静态页 + nginx.conf（8080）
├── Dockerfile                 # 后端镜像（多阶段构建）
├── docker-compose.yml         # 本地开发：MySQL/Redis/Canal
└── docker-compose.deploy.yml  # 一键部署：中间件 + App + 前端
```
