package com.aiopenplatform.consumer;

import com.aiopenplatform.entity.TokenOrder;
import com.aiopenplatform.service.ITokenOrderService;
import com.aiopenplatform.service.ITokenOrderService.GrantResult;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.annotation.Resource;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static com.aiopenplatform.utils.RedisConstants.TOKEN_COUNT_TTL;
import static com.aiopenplatform.utils.RedisConstants.TOKEN_GRANT_GROUP;
import static com.aiopenplatform.utils.RedisConstants.TOKEN_GRANT_STREAM_KEY;

/**
 * Token 发放订单 Redis Stream 消费者（异步发放，替代原 Kafka 消费者）
 * <p>
 * 可靠性设计：
 * <ul>
 *     <li><b>手动 ACK</b>：处理成功才 XACK；处理失败不 ACK，消息进入 pending-list，
 *     由 {@link GrantPendingCompensator} 定时补偿重放；</li>
 *     <li><b>幂等</b>：Redisson 分布式锁保证同一用户的发放串行处理，
 *     DB 侧「订单唯一（状态感知）+ 一人一份/限购校验 + 乐观锁（stock &gt; 0）」防止重复发放与超卖；</li>
 *     <li><b>失败留痕</b>：DB 终局校验不通过（库存不足/SKU 消失）或重试耗尽时，
 *     先落 status=2 失败订单（抢购接口已向用户承诺成功，留痕供用户可见/对账退款），再 ACK，不静默丢弃；</li>
 *     <li><b>库存校正</b>：回滚 Redis 预扣按失败原因区分——重复领取等「DB 未扣减」场景 +1 精确归还；
 *     库存不足等「DB 已无货」场景把 Redis 库存 SET 为 DB 实际值，避免幻影库存被反复误售；</li>
 *     <li><b>可恢复异常即时重试</b>：DB/Redis 瞬时故障在进程内退避重试 {@link #MAX_ATTEMPTS} 次
 *     后才抛出交给补偿器；单条失败不拖慢同批其余消息；</li>
 *     <li><b>并发扩展</b>：{@code token.grant.workers}（默认 1）个轮询工作线程并行处理，
 *     组内消费名自动编号 c1..cN 分摊消息；默认单线程时与旧版行为一致。</li>
 * </ul>
 * </p>
 */
@Slf4j
@Component
public class TokenGrantConsumer {

    /** 单条消息进程内最大尝试次数（针对 DB/Redis 可恢复异常；业务终局结果不抛异常、不重试） */
    private static final int MAX_ATTEMPTS = 3;
    /** 各次重试之间的退避毫秒数（第 i 次失败后等待 BACKOFF_MS[i]） */
    private static final long[] BACKOFF_MS = {500L, 2000L};
    /** 单工作线程轮询间隔（与轮询一批的处理时长构成 fixed-delay） */
    private static final long POLL_INTERVAL_MS = 150L;
    /** 单轮最大拉取条数 */
    private static final int POLL_BATCH_SIZE = 10;

    /** 并行发放工作线程数（默认 1：单消费者，消费名 token-grant-c1，行为与旧版一致） */
    @Value("${token.grant.workers:1}")
    private int grantWorkers;

    private ScheduledExecutorService workerPool;

    @Resource
    private ITokenOrderService tokenOrderService;
    @Resource
    private RedissonClient redissonClient;
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    private static final DefaultRedisScript<Long> ROLLBACK_SCRIPT;

    static {
        ROLLBACK_SCRIPT = new DefaultRedisScript<>();
        ROLLBACK_SCRIPT.setLocation(new ClassPathResource("lua/rollback_grant.lua"));
        ROLLBACK_SCRIPT.setResultType(Long.class);
    }

    /**
     * 启动：确保 Stream 消费组存在后，按 {@code token.grant.workers} 启动 N 个并行轮询工作线程
     */
    @PostConstruct
    public void startWorkers() {
        initStreamGroup();
        int workers = Math.max(1, grantWorkers);
        workerPool = Executors.newScheduledThreadPool(workers, runnable -> {
            Thread thread = new Thread(runnable, "token-grant-worker");
            thread.setDaemon(true);
            return thread;
        });
        for (int i = 1; i <= workers; i++) {
            // 组内多消费者并行分摊消息（pending 归属互不影响）；
            // c1 与常量 TOKEN_GRANT_CONSUMER("token-grant-c1") 同名，兼容历史 pending 归属
            final String consumerName = "token-grant-c" + i;
            workerPool.scheduleWithFixedDelay(() -> pollOnce(consumerName),
                    5000L + (i - 1) * 100L, POLL_INTERVAL_MS, TimeUnit.MILLISECONDS);
        }
        log.info("已启动 Token 发放轮询工作线程: workers={}, group={}", workers, TOKEN_GRANT_GROUP);
    }

    @PreDestroy
    public void stopWorkers() {
        if (workerPool != null) {
            workerPool.shutdownNow();
            log.info("Token 发放轮询工作线程已停止");
        }
    }

    /**
     * 创建 Stream 消费组（stream 不存在时先插入占位消息再建组）
     */
    private void initStreamGroup() {
        try {
            stringRedisTemplate.opsForStream().createGroup(TOKEN_GRANT_STREAM_KEY, TOKEN_GRANT_GROUP);
            log.info("已创建 Stream 消费组: group={}", TOKEN_GRANT_GROUP);
        } catch (RedisSystemException e) {
            if (e.getMessage() != null && e.getMessage().contains("BUSYGROUP")) {
                log.info("Stream 消费组已存在: group={}", TOKEN_GRANT_GROUP);
                return;
            }
            // Stream key 不存在导致建组失败：插入占位消息后建组，再删除占位消息
            try {
                RecordId initId = stringRedisTemplate.opsForStream()
                        .add(TOKEN_GRANT_STREAM_KEY, Collections.singletonMap("init", "1"));
                stringRedisTemplate.opsForStream().createGroup(TOKEN_GRANT_STREAM_KEY, TOKEN_GRANT_GROUP);
                stringRedisTemplate.opsForStream().delete(TOKEN_GRANT_STREAM_KEY, initId);
                log.info("已创建 Stream 消费组（占位建组）: group={}", TOKEN_GRANT_GROUP);
            } catch (Exception ex) {
                log.error("创建 Stream 消费组失败", ex);
            }
        }
    }

    /**
     * 单轮拉取并处理（XREADGROUP），处理成功后手动 XACK；
     * 单条失败仅记录并跳过，不中断同批其余消息（失败消息不 ACK、留在 pending-list，
     * 由 {@link GrantPendingCompensator} 认领重试）
     */
    private void pollOnce(String consumerName) {
        try {
            List<MapRecord<String, Object, Object>> records = stringRedisTemplate.opsForStream().read(
                    Consumer.from(TOKEN_GRANT_GROUP, consumerName),
                    StreamReadOptions.empty().count(POLL_BATCH_SIZE),
                    StreamOffset.create(TOKEN_GRANT_STREAM_KEY, ReadOffset.lastConsumed()));
            if (records == null || records.isEmpty()) {
                return;
            }
            for (MapRecord<String, Object, Object> record : records) {
                try {
                    Map<Object, Object> value = record.getValue();
                    Long orderId = Long.valueOf(String.valueOf(value.get("orderId")));
                    Long skuId = Long.valueOf(String.valueOf(value.get("skuId")));
                    Long userId = Long.valueOf(String.valueOf(value.get("userId")));
                    int limitCount = Integer.parseInt(String.valueOf(value.get("limitCount")));
                    processRecord(record.getId().getValue(), orderId, skuId, userId, limitCount);
                } catch (Exception e) {
                    // 不 ACK：消息留在 pending-list 由补偿器稍后认领；本批其余消息继续处理
                    log.error("发放消息处理失败（保留 pending 待补偿）: messageId={}", record.getId().getValue(), e);
                }
            }
        } catch (Exception e) {
            // scheduleWithFixedDelay 在任务抛异常后会静默停止后续调度，必须顶层兜底
            log.warn("轮询发放消息失败（下一轮自动重试）: err={}", e.getMessage());
        }
    }

    /**
     * 处理一条发放消息：Redisson 锁 → 幂等落库 → 失败留痕/库存校正 → XACK（消费与补偿共用）
     *
     * @param messageId  Stream 消息 id（用于 ACK）
     * @param orderId    发放订单 id
     * @param skuId      Token 包 SKU id
     * @param userId     用户 id
     * @param limitCount 限购数量（回滚 Redis 预扣时需要）
     */
    public void processRecord(String messageId, Long orderId, Long skuId, Long userId, int limitCount) {
        // 分布式锁：同一用户的发放串行处理，防止并发重复发放
        RLock lock = redissonClient.getLock("lock:token:grant:" + userId);
        boolean isLock;
        try {
            isLock = lock.tryLock(3, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("获取发放锁被中断: userId=" + userId, e);
        }
        if (!isLock) {
            // 未获取到锁：不 ACK，消息留在 pending-list，交给补偿任务稍后重试
            throw new RuntimeException("获取发放锁失败，稍后重试: userId=" + userId);
        }
        try {
            handleWithTransientRetry(messageId, orderId, skuId, userId, limitCount);
        } finally {
            lock.unlock();
        }
    }

    /**
     * 带即时重试的处理入口：业务终局结果（成功/幂等/终局失败）不抛异常；
     * 仅 DB/Redis 等可恢复异常在进程内退避重试 {@link #MAX_ATTEMPTS} 次，耗尽后抛出
     * （消息不 ACK，由补偿器定时兜底，避免瞬时故障直接进入 60s+ 的补偿等待）
     */
    private void handleWithTransientRetry(String messageId, Long orderId, Long skuId, Long userId, int limitCount) {
        RuntimeException lastEx = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                handleOnce(messageId, orderId, skuId, userId, limitCount);
                return;
            } catch (RuntimeException e) {
                lastEx = e;
                log.warn("发放处理异常（可恢复类，第 {}/{} 次尝试）: orderId={}, err={}",
                        attempt, MAX_ATTEMPTS, orderId, e.getMessage());
                if (attempt < MAX_ATTEMPTS) {
                    sleepBackoff(BACKOFF_MS[attempt - 1]);
                }
            }
        }
        throw lastEx;
    }

    /**
     * 单条消息的一次完整处理：幂等落库 + 按结果分支处理（回滚/留痕/校正），最后 ACK
     */
    private void handleOnce(String messageId, Long orderId, Long skuId, Long userId, int limitCount) {
        GrantResult result = tokenOrderService.grantTokenOrder(orderId, skuId, userId);
        switch (result) {
            case SUCCESS:
            case DUPLICATE_MSG:
                // 发放已落库 / 重复投递幂等（订单已发放）：无需回滚
                break;
            case DUPLICATE_ALREADY_GRANTED:
                // 用户此前已成功领取：DB 未扣减该笔，预扣 +1 精确归还即可
                rollbackGrant(orderId, skuId, userId, limitCount, null);
                break;
            case STOCK_NOT_ENOUGH:
                // DB 终局拒绝且已无货：先失败订单留痕（用户可见/可退款），再把 Redis 库存
                // 校正为 DB 实际值——此处若 +1 归还，只会留下被下一个用户反复误购的幻影库存
                failGrantAndReconcileStock(orderId, skuId, userId, limitCount);
                break;
            case SKU_NOT_EXISTS:
                // SKU 已删除：无法留痕（金额缺失），回滚预扣归还；Redis 残留键由人工清理
                log.error("发放订单对应 SKU 已不存在，回滚预扣后 ACK（需人工核查残留库存键）: orderId={}, skuId={}",
                        orderId, skuId);
                rollbackGrant(orderId, skuId, userId, limitCount, null);
                break;
            case ALREADY_FAILED:
                // 订单已终局失败留痕（死信路径中断后的重投递等）：回滚预扣收尾即可
                rollbackGrant(orderId, skuId, userId, limitCount, null);
                break;
            default:
                throw new IllegalStateException("未知发放结果: " + result);
        }
        // 处理完成（成功/幂等跳过/已留痕回滚），ACK
        stringRedisTemplate.opsForStream().acknowledge(TOKEN_GRANT_STREAM_KEY, TOKEN_GRANT_GROUP, messageId);
        log.info("发放消息处理完成并 ACK: messageId={}, orderId={}, result={}", messageId, orderId, result);
    }

    /** 库存不足终局失败：失败订单留痕 + 按 DB 实际库存校正 Redis（不可用时回退 +1 归还） */
    private void failGrantAndReconcileStock(Long orderId, Long skuId, Long userId, int limitCount) {
        Integer dbStock = tokenOrderService.handleTerminalGrantFailure(orderId, skuId, userId);
        rollbackGrant(orderId, skuId, userId, limitCount, dbStock == null ? null : dbStock.longValue());
    }

    /**
     * 订单是否已发放成功（幂等/死信判断用；status=2 失败留痕订单不算已发放，不拦截回滚收尾）
     */
    public boolean orderGranted(Long orderId) {
        TokenOrder order = tokenOrderService.getById(orderId);
        return order != null && order.getStatus() != null
                && order.getStatus() == ITokenOrderService.STATUS_GRANTED;
    }

    /**
     * 记录 status=2 失败订单（死信丢弃前留痕，防“抢购成功却拿不到 Token”被静默丢弃）
     */
    public void recordFailedOrder(Long orderId, Long skuId, Long userId) {
        tokenOrderService.recordFailedOrder(orderId, skuId, userId);
    }

    /**
     * 回滚 Redis 预扣库存与用户记录（原子 Lua，按订单幂等）
     * <p>
     * 脚本以 orderId 的 SETNX 标记保证同一订单只回滚一次：消息重投递、
     * 死信补偿与消费处理并发时不会重复恢复库存。
     *
     * @param syncStock 库存校正目标：null 表示 +1 精确归还（重复领取/死信等 DB 未扣减场景）；
     *                  非 null 表示把 Redis 库存 SET 为 DB 实际值（DB 库存不足等已无货场景）
     */
    public void rollbackGrant(Long orderId, Long skuId, Long userId, int limitCount, Long syncStock) {
        Long result = stringRedisTemplate.execute(ROLLBACK_SCRIPT, Collections.emptyList(),
                String.valueOf(skuId), String.valueOf(userId), String.valueOf(limitCount),
                String.valueOf(orderId), String.valueOf(TOKEN_COUNT_TTL),
                String.valueOf(syncStock == null ? -1L : syncStock));
        log.warn("已回滚 Redis 预扣: orderId={}, skuId={}, userId={}, applied={}, syncStock={}",
                orderId, skuId, userId, result, syncStock);
    }

    private static void sleepBackoff(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("发放重试等待被中断", e);
        }
    }
}
