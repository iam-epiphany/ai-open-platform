# High-Concurrency Credits Platform

一个以“高并发限量 Credits 抢购”为主题的 AI 开放平台。主链路展示 Redis Lua、Redis Stream、异步消费、幂等、超卖防护、失败补偿与多级缓存；API Key 和真实模型调用是 Credits 的消费出口，用于构成完整业务闭环。

## 业务闭环

```text
运营后台配置活动 / Credits 包
          ↓
用户限量抢购 → Redis Lua 原子校验库存与限领 → XADD
          ↓
Redis Stream Consumer → MySQL 条件扣库存 → 订单与 Credits 账本同事务入账
          ↓
创建 App / tok_ API Key → OpenAI-compatible API → DeepSeek 真实 usage
          ↓
Credits 预占 → 调用 → 多退少补结算 → Ledger + CallLog
```

系统只有一种业务余额：`Credits`。LLM 返回的 `prompt_tokens` / `completion_tokens` 只是计费依据，不是第二种用户余额。

## 关键设计

- Lua 在 Redis 内原子完成库存、限领和入队，热点请求不直接冲击 MySQL。
- Stream Consumer Group 异步落单，结合用户级锁、数据库幂等判断与 Pending List 补偿。
- 库存更新使用条件 SQL，订单和 Credits 发放位于同一事务。
- Credits 消费采用 `balance → frozen_balance → settle/release` 预占结算，供应商失败会释放冻结额度。
- API Key 明文只显示一次，数据库仅保存 SHA-256 哈希和识别前缀。
- 活动聚合数据经请求级、Caffeine、Redis、MySQL 多级缓存；后台更新会立即失效热点缓存。

> 为兼容已有数据，高并发活动的物理表和部分 Java 实体仍保留 `token_*` / `Token*` 历史名称；对外 API、前端产品语义和账本均已统一为 Credits。

## 启动

需要 JDK 17 或更高版本；构建统一输出 Java 17 字节码。

```powershell
docker compose up -d
$env:DEEPSEEK_API_KEY = 'sk-...'
mvn spring-boot:run -Dspring-boot.run.profiles=demo
```

Docker 演示环境使用 MySQL `3307`、Redis `6370`，后端默认为 `8081`。静态页经 Nginx 访问时，对外 Base URL 为 `http://<host>/api/v1`；直连 Spring Boot 时为 `http://<host>:8081/v1`。

前端静态文件保存后刷新浏览器即可生效；Java Controller 或 Service 修改后必须停止旧的 `8081` 进程并重新启动，否则页面会调用到旧路由并返回 404。确认后端启动完成后，再访问 `http://localhost:8080`。

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

## 对外调用

```powershell
curl http://localhost:8081/v1/chat/completions `
  -Method POST `
  -Headers @{ Authorization = 'Bearer tok_your_api_key'; 'Content-Type' = 'application/json' } `
  -Body '{"model":"deepseek-chat","messages":[{"role":"user","content":"你好"}],"max_tokens":128}'
```

当前实现 OpenAI Chat Completions 兼容协议，可用于 curl、Python SDK 及支持自定义 Base URL 的普通 Agent。项目未实现 Responses API。

## 验证

```powershell
mvn -q test
mvn -q -DskipTests package
git diff --check
```

调用真实 DeepSeek 前必须配置 `DEEPSEEK_API_KEY`。未配置或供应商调用失败时，已预占的 Credits 会被释放并保留失败审计日志。
