-- Token 包抢购：原子完成 库存校验 + 防重复领取 + 预扣库存 + 写入 Redis Stream
-- KEYS[1]  = stockKey    token:stock:{skuId}
-- KEYS[2]  = grantedKey  token:granted:{skuId}    （已领取用户集合）
-- KEYS[3]  = countKey    token:count:{skuId}:{userId}（限购计数）
-- KEYS[4]  = streamKey   token:grant:stream
-- ARGV[1]  = skuId
-- ARGV[2]  = userId
-- ARGV[3]  = orderId
-- ARGV[4]  = limitCount（<=1 一人一份；>1 限购 N）
-- 返回：-1 库存不足；-2 不能重复领取；-3 超出限购；-4 系统异常（XADD 失败）；>=0 剩余库存

local stockKey = KEYS[1]
local grantedKey = KEYS[2]
local countKey = KEYS[3]
local streamKey = KEYS[4]

-- 1. 库存校验
local stock = tonumber(redis.call('get', stockKey) or '-1')
if stock <= 0 then
    return -1
end

-- 2. 差异化防重复领取
if tonumber(ARGV[4]) <= 1 then
    -- 体验包：一人一份
    if redis.call('sismember', grantedKey, ARGV[2]) == 1 then
        return -2
    end
else
    -- 企业团队共享池：限购 N 份
    local cnt = tonumber(redis.call('incr', countKey))
    if cnt > tonumber(ARGV[4]) then
        redis.call('decr', countKey)
        return -3
    end
    redis.call('expire', countKey, 604800)
end

-- 3. 预扣库存 + 记录已领取用户
redis.call('incrby', stockKey, -1)
redis.call('sadd', grantedKey, ARGV[2])
redis.call('expire', grantedKey, 604800)

-- 4. 写入 Redis Stream（与预扣同一原子操作，避免「预扣成功但消息丢失」）
local ok, err = pcall(redis.call, 'XADD', streamKey, '*',
    'orderId', ARGV[3], 'skuId', ARGV[1], 'userId', ARGV[2], 'limitCount', ARGV[4])
if not ok then
    -- XADD 失败：回滚预扣，保证 Redis 库存一致
    redis.call('incrby', stockKey, 1)
    redis.call('srem', grantedKey, ARGV[2])
    if tonumber(ARGV[4]) > 1 then
        local cnt = tonumber(redis.call('get', countKey) or '0')
        if cnt > 0 then
            redis.call('decr', countKey)
        end
    end
    return -4
end

-- 返回剩余库存
return stock - 1
