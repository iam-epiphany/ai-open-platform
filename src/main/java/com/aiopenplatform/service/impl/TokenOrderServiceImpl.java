package com.aiopenplatform.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.aiopenplatform.dto.Result;
import com.aiopenplatform.entity.TokenOrder;
import com.aiopenplatform.entity.TokenSku;
import com.aiopenplatform.gateway.PlatformService;
import com.aiopenplatform.mapper.TokenOrderMapper;
import com.aiopenplatform.service.ITokenOrderService;
import com.aiopenplatform.service.ITokenSkuService;
import com.aiopenplatform.utils.RedisIdWorker;
import com.aiopenplatform.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.aiopenplatform.service.ITokenOrderService.STATUS_GRANT_FAILED;
import static com.aiopenplatform.service.ITokenOrderService.STATUS_GRANTED;

import static com.aiopenplatform.utils.RedisConstants.TOKEN_COUNT_KEY;
import static com.aiopenplatform.utils.RedisConstants.TOKEN_COUNT_TTL;
import static com.aiopenplatform.utils.RedisConstants.TOKEN_GRANTED_KEY;
import static com.aiopenplatform.utils.RedisConstants.TOKEN_GRANT_STREAM_KEY;
import static com.aiopenplatform.utils.RedisConstants.TOKEN_STOCK_KEY;

/**
 * <p>
 * Token 发放订单服务实现
 * </p>
 * 写链路（Redis Lua + Stream 异步发放）：
 * <ul>
 *     <li>抢购接口：Lua 原子「库存校验 + 防重复领取 + 预扣库存 + XADD 写 Stream」，接口直接返回订单 id；</li>
 *     <li>后台消费者：Redisson 锁 + 订单幂等校验（状态感知）+ MySQL 乐观锁（stock &gt; 0）扣库存
 *     → 创建发放订单 → 写 token 账本 → 更新用户权益 → ACK Stream 消息；</li>
 *     <li>数据一致性：Redis 预扣只作流量拦截与削峰，DB 库存以「乐观锁」为准；
 *     DB 校验不通过（库存不足/已领取）时回滚 Redis 预扣，保证两库一致；
 *     终局失败会落 status=2 失败订单留痕（用户可见/可退款），不静默丢弃。</li>
 * </ul>
 */
@Slf4j
@Service
public class TokenOrderServiceImpl extends ServiceImpl<TokenOrderMapper, TokenOrder> implements ITokenOrderService {

    @Resource
    private ITokenSkuService tokenSkuService;
    @Resource
    private PlatformService platformService;
    @Resource
    private RedisIdWorker redisIdWorker;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private JdbcTemplate jdbcTemplate;

    private static final DefaultRedisScript<Long> GRANT_SCRIPT;

    static {
        GRANT_SCRIPT = new DefaultRedisScript<>();
        GRANT_SCRIPT.setLocation(new ClassPathResource("lua/grant.lua"));
        GRANT_SCRIPT.setResultType(Long.class);
    }

    /**
     * 抢购入口：Lua 原子预扣 + 写 Stream，发放由后台消费者异步完成
     */
    @Override
    public Result grantToken(Long skuId) {
        Long userId = UserHolder.getUser().getId();

        // 校验 SKU 与活动时间窗（SKU 详情走四级缓存，热点读）
        TokenSku sku = tokenSkuService.getSkuWithCache(skuId);
        if (sku == null) {
            return Result.fail("Credits 包不存在");
        }
        if (sku.getStatus() == null || sku.getStatus() != 1) {
            return Result.fail("Credits 包已下架");
        }
        LocalDateTime now = LocalDateTime.now();
        Integer active = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tb_token_activity WHERE status=1 AND FIND_IN_SET(?,sku_ids)>0 "
                        + "AND (start_time IS NULL OR start_time<=NOW()) AND (end_time IS NULL OR end_time>=NOW())",
                Integer.class, skuId);
        if (active == null || active == 0) {
            return Result.fail("该 Credits 包当前不在有效活动中");
        }
        if (sku.getBeginTime() != null && sku.getBeginTime().isAfter(now)) {
            return Result.fail("活动尚未开始");
        }
        if (sku.getEndTime() != null && sku.getEndTime().isBefore(now)) {
            return Result.fail("活动已结束");
        }
        int limitCount = sku.getLimitCount() == null || sku.getLimitCount() <= 0 ? 1 : sku.getLimitCount();
        long orderId = redisIdWorker.nextId("token-order");

        // 执行 Lua：原子完成 库存校验 + 防重复领取 + 预扣库存 + XADD 写 Stream
        Long result = stringRedisTemplate.execute(
                GRANT_SCRIPT,
                Arrays.asList(
                        TOKEN_STOCK_KEY + skuId,
                        TOKEN_GRANTED_KEY + skuId,
                        TOKEN_COUNT_KEY + skuId + ":" + userId,
                        TOKEN_GRANT_STREAM_KEY),
                String.valueOf(skuId), String.valueOf(userId), String.valueOf(orderId),
                String.valueOf(limitCount), String.valueOf(TOKEN_COUNT_TTL)
        );
        // -1 库存不足；-2 不能重复领取；-3 超出限购；-4 系统异常（XADD 失败，已回滚）
        if (result == null || result < 0) {
            String msg = result == null ? "系统异常"
                    : (result == -1 ? "库存不足"
                    : (result == -2 ? "不能重复领取"
                    : (result == -3 ? "超出限购数量" : "系统繁忙，请重试")));
            return Result.fail(msg);
        }

        // 抢购成功：立即返回订单 id + 剩余库存，真正的发放由 Stream 消费者异步完成
        Map<String, Object> data = new HashMap<>();
        data.put("orderId", orderId);
        data.put("remainStock", result);
        log.info("抢购成功，进入异步发放: orderId={}, userId={}, skuId={}", orderId, userId, skuId);
        return Result.ok(data);
    }

    /**
     * 发放落库（Stream 消费者调用）
     * 幂等保证：Redisson 锁（消费侧） + 订单 id 唯一校验（状态感知） + DB「一人一份/限购」校验 + 乐观锁扣库存；
     * 终局失败（库存不足/SKU 消失）由消费端留痕失败订单并校正 Redis 预扣
     */
    @Override
    @Transactional
    public GrantResult grantTokenOrder(Long orderId, Long skuId, Long userId) {
        // 1. 同一消息重复投递幂等（状态感知）：已发放的直接跳过；已终局失败的不再重放发放，按失败收尾
        TokenOrder existing = getById(orderId);
        if (existing != null) {
            if (existing.getStatus() != null && existing.getStatus() == STATUS_GRANTED) {
                log.info("发放消息重复投递，订单已发放，幂等跳过: orderId={}", orderId);
                return GrantResult.DUPLICATE_MSG;
            }
            log.warn("发放消息重复投递，订单已终局失败，按失败收尾: orderId={}, status={}", orderId, existing.getStatus());
            return GrantResult.ALREADY_FAILED;
        }
        TokenSku sku = tokenSkuService.getById(skuId);
        if (sku == null) {
            // SKU 已删除：单独枚举终局失败（若并入 DUPLICATE_MSG 会直接 ACK、Redis 预扣永不归还）
            log.error("发放订单对应的 SKU 不存在，按终局失败处理: orderId={}, skuId={}", orderId, skuId);
            return GrantResult.SKU_NOT_EXISTS;
        }
        int limit = sku.getLimitCount() == null || sku.getLimitCount() <= 0 ? 1 : sku.getLimitCount();

        // 2. DB 一人一份/限购校验（事实源，只统计已发放订单；失败留痕订单不算已领取）
        long count = query().eq("user_id", userId).eq("sku_id", skuId)
                .eq("status", STATUS_GRANTED).count();
        if (count >= limit) {
            log.warn("用户已领取/已超限，回滚 Redis 预扣: userId={}, skuId={}", userId, skuId);
            return GrantResult.DUPLICATE_ALREADY_GRANTED;
        }

        // 3. 乐观锁扣减 DB 库存（条件 stock > 0，防止超卖）
        boolean deducted = tokenSkuService.update()
                .setSql("stock = stock - 1")
                .eq("id", skuId)
                .gt("stock", 0)
                .update();
        if (!deducted) {
            log.warn("DB 库存不足，回滚 Redis 预扣: skuId={}", skuId);
            return GrantResult.STOCK_NOT_ENOUGH;
        }

        // 4. 创建发放订单
        LocalDateTime now = LocalDateTime.now();
        TokenOrder order = new TokenOrder();
        order.setId(orderId);
        order.setUserId(userId);
        order.setSkuId(skuId);
        order.setTokenAmount(sku.getTokenAmount());
        order.setStatus(STATUS_GRANTED);
        order.setChannel(sku.getType() != null && sku.getType() == 2 ? 2 : 1);
        order.setCreateTime(now);
        order.setGrantTime(now);
        save(order);

        // 5. 发放到统一 Credits 账户并写账本（加入当前事务）
        platformService.grantCredits(userId, sku.getTokenAmount(), String.valueOf(orderId), sku.getPackageName());

        log.info("Credits 发放成功: orderId={}, userId={}, skuId={}, amount={}",
                orderId, userId, skuId, sku.getTokenAmount());
        return GrantResult.SUCCESS;
    }

    @Override
    public Integer handleTerminalGrantFailure(Long orderId, Long skuId, Long userId) {
        recordFailedOrder(orderId, skuId, userId);
        TokenSku sku = tokenSkuService.getById(skuId);
        return sku == null ? null : sku.getStock();
    }

    @Override
    public void recordFailedOrder(Long orderId, Long skuId, Long userId) {
        // 幂等：已存在（发放成功或已留痕）则不再写入
        if (getById(orderId) != null) {
            return;
        }
        TokenSku sku = tokenSkuService.getById(skuId);
        if (sku == null || sku.getTokenAmount() == null) {
            // token_amount 非空：SKU 已删除导致金额缺失时无法落库，告警留痕由人工核查
            log.error("发放终局失败但 SKU 金额缺失，无法落库留痕，需人工核查: orderId={}, skuId={}, userId={}",
                    orderId, skuId, userId);
            return;
        }
        TokenOrder order = new TokenOrder();
        order.setId(orderId);
        order.setUserId(userId);
        order.setSkuId(skuId);
        order.setTokenAmount(sku.getTokenAmount());
        order.setStatus(STATUS_GRANT_FAILED);
        order.setChannel(sku.getType() != null && sku.getType() == 2 ? 2 : 1);
        order.setCreateTime(LocalDateTime.now());
        try {
            save(order);
            log.warn("已记录发放失败订单（留痕，用户可见/可对账退款）: orderId={}, skuId={}, userId={}", orderId, skuId, userId);
        } catch (DuplicateKeyException e) {
            // 并发留痕幂等
            log.info("发放失败订单已存在（并发留痕幂等跳过）: orderId={}", orderId);
        }
    }

    @Override
    public List<TokenOrder> getUserOrders(Long userId) {
        return lambdaQuery().eq(TokenOrder::getUserId, userId)
                .orderByDesc(TokenOrder::getCreateTime)
                .list();
    }
}
