package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.Voucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.service.IVoucherService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * 下单架构（差异化防超卖）：
 * <ul>
 *     <li>普通券（type=0）：无库存竞争，同步直接落库；</li>
 *     <li>秒杀券（type=1）：Lua 原子「库存校验+一人一单+预扣库存」→ Kafka 异步落库；</li>
 *     <li>限购券（type=2）：Lua 原子「库存校验+限购 N 张计数+预扣库存」→ Kafka 异步落库。</li>
 * </ul>
 * 数据一致性：Redis 预扣只作流量拦截与削峰，DB 库存扣减以「乐观锁（stock > 0）」为准；
 * 异步消费侧通过「Redisson 分布式锁 + DB 一人一单校验」保证幂等。
 */
@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    /** Kafka 秒杀订单主题 */
    public static final String SECKILL_ORDER_TOPIC = "seckill-order";

    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Resource
    private IVoucherService voucherService;
    @Resource
    private RedisIdWorker redisIdWorker;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private RedissonClient redissonClient;
    @Resource
    private KafkaTemplate<String, String> kafkaTemplate;

    /**
     * 差异化下单入口：按优惠券类型分派到不同防超卖架构
     */
    @Override
    public Result createOrder(Long voucherId) {
        Voucher voucher = voucherService.getById(voucherId);
        if (voucher == null) {
            return Result.fail("优惠券不存在");
        }
        // 普通券：无库存竞争，同步下单
        if (voucher.getType() != null && voucher.getType() == 0) {
            return createNormalOrder(voucherId);
        }
        // 秒杀券/限购券：Lua 预扣 + Kafka 异步落库
        return seckillVoucher(voucherId);
    }

    /**
     * 普通券下单：直接创建订单，无需防超卖
     */
    private Result createNormalOrder(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        VoucherOrder order = new VoucherOrder();
        order.setId(redisIdWorker.nextId("order"));
        order.setUserId(userId);
        order.setVoucherId(voucherId);
        order.setStatus(1);
        save(order);
        log.info("普通券下单成功: orderId={}, userId={}, voucherId={}", order.getId(), userId, voucherId);
        return Result.ok(order.getId());
    }

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    private static final DefaultRedisScript<Long> ROLLBACK_SCRIPT;
    static {
        ROLLBACK_SCRIPT = new DefaultRedisScript<>();
        ROLLBACK_SCRIPT.setLocation(new ClassPathResource("rollback_seckill.lua"));
        ROLLBACK_SCRIPT.setResultType(Long.class);
    }

    @Override
    public Result seckillVoucher(Long voucherId) {
        //获取用户
        Long userId = UserHolder.getUser().getId();
        //获取订单id
        long orderId = redisIdWorker.nextId("order");
        //校验秒杀时间窗口 + 查询限购数量（秒杀券默认一人一单）
        SeckillVoucher seckillVoucher = seckillVoucherService.getById(voucherId);
        if (seckillVoucher == null) {
            return Result.fail("秒杀券不存在");
        }
        LocalDateTime now = LocalDateTime.now();
        if (seckillVoucher.getBeginTime() != null && seckillVoucher.getBeginTime().isAfter(now)) {
            return Result.fail("秒杀尚未开始");
        }
        if (seckillVoucher.getEndTime() != null && seckillVoucher.getEndTime().isBefore(now)) {
            return Result.fail("秒杀已结束");
        }
        Integer limitCount = 1;
        Integer lc = seckillVoucher.getLimitCount();
        limitCount = lc == null || lc <= 0 ? 1 : lc;

        //执行lua脚本：原子完成 库存校验 + 差异化限购校验 + 预扣库存
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString(), String.valueOf(orderId), String.valueOf(limitCount)
        );
        //判断结果: -1库存不足, -2重复下单, -3超出限购, >=0成功(值为剩余库存)
        if (result == null || result < 0) {
            String msg = result == null ? "系统异常"
                    : (result == -1 ? "库存不足"
                    : (result == -2 ? "不能重复下单" : "超出限购数量"));
            return Result.fail(msg);
        }

        //异步落库：发送订单消息到 Kafka（失败则补偿回滚 Redis 预扣）
        boolean sent = sendOrderToKafka(orderId, voucherId, userId);
        if (!sent) {
            rollbackSeckill(voucherId, userId);
            return Result.fail("下单失败，请重试");
        }

        //返回订单id和剩余库存，前端可据此更新显示
        Map<String, Object> data = new HashMap<>();
        data.put("orderId", orderId);
        data.put("remainStock", result);
        return Result.ok(data);
    }

    /**
     * 发送订单消息到 Kafka（同步等待结果），失败返回 false
     */
    private boolean sendOrderToKafka(long orderId, Long voucherId, Long userId) {
        Map<String, Object> msg = new HashMap<>();
        msg.put("id", orderId);
        msg.put("voucherId", voucherId);
        msg.put("userId", userId);
        try {
            kafkaTemplate.send(SECKILL_ORDER_TOPIC, userId.toString(), JSONUtil.toJsonStr(msg))
                    .get(3, TimeUnit.SECONDS);
            return true;
        } catch (Exception e) {
            log.error("Kafka消息发送失败: orderId={}, voucherId={}, userId={}", orderId, voucherId, userId, e);
            return false;
        }
    }

    /**
     * 补偿回滚 Redis 预扣的库存与用户记录（发送失败/落库失败时调用）
     */
    private void rollbackSeckill(Long voucherId, Long userId) {
        try {
            stringRedisTemplate.execute(ROLLBACK_SCRIPT, Collections.emptyList(),
                    voucherId.toString(), userId.toString());
            log.info("已回滚秒杀预扣: voucherId={}, userId={}", voucherId, userId);
        } catch (Exception e) {
            log.error("回滚秒杀预扣异常: voucherId={}, userId={}", voucherId, userId, e);
        }
    }

    /**
     * 秒杀订单落库（由 Kafka 消费者调用）
     * 幂等保证：Redisson 锁（消费侧） + DB「一人一单」校验 + 乐观锁扣库存
     *
     * @return true=落库成功（或已存在订单）；false=DB 库存不足（需回滚 Redis 预扣）
     */
    @Transactional
    public boolean createVoucherOrder(VoucherOrder voucherOrder) {
        //一人一单（重复消息幂等处理）
        Long userId = voucherOrder.getUserId();
        int count = query().eq("user_id", userId)
                .eq("voucher_id", voucherOrder.getVoucherId()).count();
        if(count>0){
            log.info("订单已存在，幂等跳过: userId={}, voucherId={}", userId, voucherOrder.getVoucherId());
            return true;
        }

        //乐观锁扣减库存（条件 stock > 0，防止超卖）
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1 ")
                .eq("voucher_id", voucherOrder.getVoucherId())
                .gt("stock", 0)
                .update();
        if(!success){
            log.warn("DB库存不足，落库失败: voucherId={}", voucherOrder.getVoucherId());
            return false;
        }
        //创建订单
        save(voucherOrder);
        return true;
    }
}
