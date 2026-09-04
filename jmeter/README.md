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

## 2026-09-03 压测补充脚本（详见 docs/压测报告-2026-09-03.md）

| 脚本 | 用途 |
| --- | --- |
| `token-grant-ramp.jmx` | 无同步器的抢购容量版：线程 ramp 爬坡连续请求，避免瞬时建连拒绝干扰 |
| `v1-gateway-stress.jmx` | `/v1/chat/completions` 持续施压（时长/线程参数化，业务码分类：400/402/502=预期失败） |
| `mixed-scenario.jmx` | 三组混合：v1 网关 + 抢购突发 + 活动页读（CSV 必须嵌套在各线程组内，防止跨组消费） |
| `setup/prepare-stress-data.sh` | 幂等造数：压测 SKU(1001/1002/1004)+活动、3000 用户、800 个 API Key+余额、Redis 登录态、`data/*.csv`（**注意**：`data/` 为生成目录不入库，重跑本脚本即可再生成） |
| `setup/soak-orchestrate.sh` | S7 半小时 soak 一键编排（含第 10 分钟 1000 人抢购 + kill 应用 60s 的补偿验证） |

## 2026-09-04 极限压测脚本（详见 docs/压测报告-2026-09-04.md）

| 脚本 | 用途 |
| --- | --- |
| `setup/prepare-limit-data.sh` | 极限造数（幂等）：SKU 1010(stock=10k,限1)/1011(5k,1)/1012(20k,限1k) + 12,500 用户（5010001~5022500，登录态+独立 IP），产出 `data/grant-limit-1010/1011/1012.csv`；**运行前需停止应用**（脚本会清空发放 Stream） |
| `token-grant-capacity.jmx` | 抢购入口容量平台：N 线程持续施压（`-Jthreads/-Jduration` 参数化），CSV 用户轮转 + 每请求随机 XFF |
| `verify-limit.sql` | 1010/1011/1012 终态一致性校验（订单/重复/账本对账/冻结/库存） |

**执行注意（2026-09-04 实测修正）**：
- 采样器实现必须用 Java：`-Jjmeter.httpsampler=Java`——HttpClient4 在本机不复用连接，会把 Windows 16,384 个动态端口耗尽并产生 BindException 洪水（非服务端问题）。
- `/v1` 施压前确认 shell **不带 `DEEPSEEK_API_KEY`**（真实 Key 会打到 DeepSeek 上游而非本地 fast-path）；测试专用 RPM 上限可用 `--ai.limits.rpm-per-key=1000000` 启动应用。

压测要点速记：

- 全链路一致性断言：`tb_token_order` 订单数 ≤ 初始库存、同用户同 SKU 无重复、`tb_token_sku.stock` 与 Redis `token:stock:<skuId>` 终值一致、`tb_credit_ledger` ACTIVITY_GRANT 行数与总额对账、冻结恒为 0、Stream `XPENDING` 归 0。
- 已知边界（2026-09-03 本机实测）：MySQL 默认提交 fsync 下 `/v1` 写链 265~540 rps（放宽持久化后 1,258 rps）；HikariCP 默认 10 连接为并发天花板（50~400 并发吞吐恒 ~520 rps）；抢购异步发放单消费者 ~45-60 单/s；瞬时 500 新建连接约半数被拒（Tomcat acceptCount=100）。
