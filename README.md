# AI Open Platform

轻量级 AI 开放平台示例：开发者创建应用和 API Key 后，可通过 OpenAI-compatible API 调用 DeepSeek；平台按真实 usage 计费、扣减 Credits，并保留调用审计。Credits 活动继续采用 Redis Lua + Redis Stream 支撑高并发限量发放。

## 能力闭环

```text
注册登录 → 模拟充值 Credits → 创建 App / API Key → 绑定模型权限
→ POST /v1/chat/completions → DeepSeek → usage 计费 → Credits 结算 → Ledger + CallLog

Credits 活动：领取 → Redis Lua（库存/限购/XADD）→ Redis Stream Consumer
           → MySQL（CreditOrder/CreditAccount/CreditLedger）
```

Credits 是平台内部余额；`prompt_tokens` 和 `completion_tokens` 是模型返回的 LLM Token，用来计算 Credits 消耗，两者不混用。

## 启动

```powershell
# 首次启动会依次导入基础库、历史秒杀演示表和新的 Credits 平台表
docker compose up -d

# 配置真实模型密钥（仅当前 PowerShell 会话）
$env:DEEPSEEK_API_KEY = 'sk-...'

# 启动后端
mvn spring-boot:run -Dspring-boot.run.profiles=demo
```

`docker-compose.yml` 会自动挂载并执行 [credit_platform.sql](src/main/resources/db/credit_platform.sql)。已有数据库可手动执行该文件；它是增量建表和种子数据，不会删除原有数据。

前端静态页仍由 `nginx-1.18.0` 托管；业务接口默认经 `/api` 代理到后端 8081。

## 核心接口

| 场景 | 接口 | 鉴权 |
| --- | --- | --- |
| 创建/查看 App | `POST /apps`、`GET /apps` | 登录态 |
| 管理 API Key | `POST /apps/{id}/keys`、`PUT /apps/keys/{keyId}` | 登录态 |
| Credits 账户 | `GET /credits/account` | 登录态 |
| 模拟充值 | `POST /credits/recharge` | 登录态 |
| Credits 活动 | `GET /credit-activities` | 公开 |
| 领取活动包 | `POST /credit-activities/packages/{id}/claim` | 登录态 |
| 可调用模型 | `GET /v1/models` | API Key |
| 模型调用 | `POST /v1/chat/completions` | API Key |

创建 API Key 时会返回一次明文 `tok_...`；数据库只保存 SHA-256 哈希与前缀。每个新 App 自动获得当前启用模型的权限，之后可通过 `tb_app_model` 在后台精确调整。

## OpenAI-compatible 调用示例

```powershell
curl http://localhost:8081/v1/chat/completions `
  -Method POST `
  -Headers @{ Authorization = 'Bearer tok_your_api_key'; 'Content-Type' = 'application/json' } `
  -Body '{"model":"deepseek-chat","messages":[{"role":"user","content":"用一句话介绍 Credits 计费"}],"max_tokens":128}'
```

成功响应包含 OpenAI 格式的 `choices` 和 `usage.prompt_tokens`、`usage.completion_tokens`、`usage.total_tokens`。当前版本明确拒绝 `stream=true`，以避免将未结算的流式输出暴露给客户端；可在后续迭代以 Provider 流 + SSE 实现。

## 计费与一致性

1. 根据请求长度及 `max_tokens` 计算最高可能 Credits，原子地从 `balance` 转到 `frozen_balance`。
2. 调用真实 Provider，读取返回的真实 usage，并按 `tb_model_price` 的输入/输出每 1K Token 价格计算实际 Credits。
3. 结算时解冻预占额度、多退少补，写入 `tb_credit_ledger` 和 `tb_ai_call_log`；Provider 失败时释放全部冻结余额并写失败审计。

默认 `deepseek-chat` 价格为输入 10、输出 20 Credits / 1K Tokens，仅作演示，可在 `tb_model_price` 修改。

## Credits 活动的并发保证

- Lua 在单次 Redis 命令中执行库存检查、限购检查、库存预扣与 `XADD`，避免“扣库存成功但消息丢失”。
- Stream consumer 以用户维度 Redisson 锁串行处理；MySQL 以 `stock > 0` 条件更新再次防超卖。
- 订单 ID 具备幂等性；数据库校验失败会执行 Lua 回滚 Redis 预扣。
- 最终入账与 `CreditOrder`、`CreditLedger` 同步完成，保证账户余额可审计。

## 数据表

核心表为：`tb_app`、`tb_api_key`、`tb_model`、`tb_app_model`、`tb_model_price`、`tb_credit_account`、`tb_credit_ledger`、`tb_recharge_order`、`tb_credit_activity`、`tb_credit_package`、`tb_credit_order`、`tb_ai_call_log`。

## 验证

```powershell
mvn -q -DskipTests package
git diff --check
```

已完成构建验证。调用真实模型前必须设置 `DEEPSEEK_API_KEY`；未设置时接口会返回清晰的配置错误，且已预占的 Credits 会被释放。
