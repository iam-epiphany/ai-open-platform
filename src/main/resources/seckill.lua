-- 秒杀/限购券预扣库存脚本
-- 在 Redis 内原子完成：库存校验 + 限购校验 + 预扣库存 + 记录用户
-- 返回：>=0 剩余库存（成功）；-1 库存不足；-2 重复下单（一人一单）；-3 超出限购数量
-- 消息发送与订单落库由 Java 端通过 Kafka 异步完成（流量削峰）

-- 1.参数列表
-- 1.1.优惠券id
local voucherId = ARGV[1]
-- 1.2.用户id
local userId = ARGV[2]
-- 1.3.订单id
local orderId = ARGV[3]
-- 1.4.每人限购数量（1=秒杀券一人一单；>1=限购券）
local limitCount = tonumber(ARGV[4]) or 1

-- 2.数据key
-- 2.1.库存key
local stockKey = 'seckill:stock:' .. voucherId
-- 2.2.订单key
local orderKey = 'seckill:order:' .. voucherId
-- 2.3.限购计数key
local countKey = 'seckill:count:' .. voucherId .. ':' .. userId

-- 3.脚本业务
-- 3.1.判断库存是否充足 get stockKey
local stock = redis.call('get', stockKey)
if(not stock or tonumber(stock) <= 0) then
    -- 3.2.库存不足，返回-1
    return -1
end
-- 3.3.差异化限购校验
if limitCount <= 1 then
    -- 秒杀券：一人一单，重复下单返回-2
    if(redis.call('sismember', orderKey, userId) == 1) then
        return -2
    end
else
    -- 限购券：同一用户累计限购 N 张，超出返回-3（并回退计数）
    local bought = redis.call('incr', countKey)
    -- 计数有效期 7 天，防止活动结束后 key 常驻内存
    redis.call('expire', countKey, 604800)
    if bought > limitCount then
        redis.call('decr', countKey)
        return -3
    end
end
-- 3.4.预扣库存 incrby stockKey -1，并记录剩余库存
local remain = redis.call('incrby', stockKey, -1)
-- 3.5.记录下单用户（幂等对账用）sadd orderKey userId
redis.call('sadd', orderKey, userId)
-- 3.6.返回剩余库存；订单消息由 Java 端发送到 Kafka，异步落库
return remain
