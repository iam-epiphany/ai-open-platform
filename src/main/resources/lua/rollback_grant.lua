-- 回滚 Token 包抢购预扣（发放失败/DB 校验不通过时调用）：恢复库存 + 移除用户记录 + 回退限购计数
-- ARGV[1] = skuId
-- ARGV[2] = userId
-- ARGV[3] = limitCount

redis.call('incrby', 'token:stock:' .. ARGV[1], 1)
redis.call('srem', 'token:granted:' .. ARGV[1], ARGV[2])
if tonumber(ARGV[3]) > 1 then
    local cnt = tonumber(redis.call('get', 'token:count:' .. ARGV[1] .. ':' .. ARGV[2]) or '0')
    if cnt > 0 then
        redis.call('decr', 'token:count:' .. ARGV[1] .. ':' .. ARGV[2])
    end
end
return 1
