-- 回滚秒杀预扣：恢复库存 + 移除用户下单记录 + 回退限购计数
-- 用于：Kafka 发送失败 / 异步落库失败（DB 库存不足）时的补偿

local voucherId = ARGV[1]
local userId = ARGV[2]

local stockKey = 'seckill:stock:' .. voucherId
local orderKey = 'seckill:order:' .. voucherId
local countKey = 'seckill:count:' .. voucherId .. ':' .. userId

-- 1.恢复预扣的库存
redis.call('incrby', stockKey, 1)
-- 2.移除用户下单记录
redis.call('srem', orderKey, userId)
-- 3.回退限购计数
local count = tonumber(redis.call('get', countKey))
if count and count > 0 then
    redis.call('decr', countKey)
end

return 1
