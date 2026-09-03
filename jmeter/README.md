# Token 包高并发抢购压测

脚本 [token-grant-stress.jmx](token-grant-stress.jmx) 让每个线程读取一个独立登录 token，在同步点同时请求：

```text
POST /token-order/grant/{skuId}
```

它针对的是 Redis Lua 预扣库存、Redis Stream 异步发放、MySQL 条件扣库存和用户限购的一致性测试。

## 运行前准备

1. 在**本地或独立测试库**启动服务；不要对生产库存运行。
2. 创建一个处于活动期、已上架的测试 SKU。为方便判定，设 `limit_count=1`，压测前库存应大于等于并发用户数。
3. 准备与线程数相同数量的不同用户登录 token。复制 `tokens.csv.example` 为 `tokens.csv`，删除示例说明行，填入真实数据。两列依次为 `token,clientIp`。
4. `clientIp` 只用于绕过本项目当前“每 IP、每接口、每分钟 30 次”的测试保护。该接口直接信任 `X-Forwarded-For`，所以此做法仅限本地/测试环境；生产应由可信反向代理覆盖该请求头。

## 执行

在 `jmeter` 目录执行。`threads` 必须不大于 `tokens.csv` 的有效数据行数。

```powershell
Copy-Item tokens.csv.example tokens.csv
# 编辑 tokens.csv 后执行：
jmeter -n -t token-grant-stress.jmx -l results.jtl -e -o report `
  -Jhost=127.0.0.1 -Jport=8081 -JskuId=1 -Jthreads=100 -JrampUp=1
```

输出的 HTML 报告在 `report/index.html`。JTL 的标签含义：

| 标签 | 含义 |
| --- | --- |
| `抢购 - 成功` | 已通过 Lua 预扣并写入 Stream；不代表已完成 MySQL 异步入库。 |
| `抢购 - 库存不足` | 业务正常拒绝，不是 HTTP 错误。 |
| `抢购 - 重复或超限` | CSV token 重复或用户已领取过该 SKU。 |
| `抢购 - 限流` | 测试来源 IP 不足/重复，或没有正确读取 `clientIp`。 |

请求层的 401、429、5xx 会被标记为失败；HTTP 200 的业务拒绝会保留为成功传输样本并按标签分类。

## 验收

等待应用日志显示 Stream 消费完成，再执行 [verify-token-grant.sql](verify-token-grant.sql)：成功订单数不能超过压测前库存，同一用户不能有多笔订单；同时比对 MySQL `tb_token_sku.stock` 与 Redis `token:stock:<skuId>`。
