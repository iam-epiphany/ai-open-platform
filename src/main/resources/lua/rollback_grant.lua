-- 回滚 Token 包抢购预扣（发放终局失败/死信补偿时调用）
-- 幂等：以 orderId 做回滚标记（SETNX），同一订单只允许回滚一次，
-- 避免消息重投递后重复恢复库存/重复回退限购计数。
-- ARGV[1] = skuId
-- ARGV[2] = userId
-- ARGV[3] = limitCount
-- ARGV[4] = orderId
-- ARGV[5] = ttlSeconds（回滚标记过期秒数）
-- ARGV[6] = 库存校正目标：>=0 时把 Redis 库存 SET 为该值（按 DB 事实校正——
--           用于 DB 库存不足：DB 已无货，若 +1 归还只会留下被反复误售的“幻影库存”）；
--           -1（默认）时 +1 精确归还预扣（用于用户重复领取等 DB 未扣减、货仍在的场景）。
-- 返回：1=本次执行了回滚；0=该订单已回滚过（幂等跳过）

local rbKey = 'token:rb:' .. ARGV[4]
if redis.call('setnx', rbKey, '1') == 0 then
    return 0
end
redis.call('expire', rbKey, ARGV[5])

local syncStock = tonumber(ARGV[6] or '-1')
if syncStock >= 0 then
    redis.call('set', 'token:stock:' .. ARGV[1], syncStock)
else
    redis.call('incrby', 'token:stock:' .. ARGV[1], 1)
end

redis.call('srem', 'token:granted:' .. ARGV[1], ARGV[2])
if tonumber(ARGV[3]) > 1 then
    local cnt = tonumber(redis.call('get', 'token:count:' .. ARGV[1] .. ':' .. ARGV[2]) or '0')
    if cnt > 0 then
        redis.call('decr', 'token:count:' .. ARGV[1] .. ':' .. ARGV[2])
    end
end
return 1
