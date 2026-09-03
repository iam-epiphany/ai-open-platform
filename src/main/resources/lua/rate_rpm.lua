-- API Key 级 RPM 令牌桶：容量=每分钟配额（可突发 1 分钟的量），按 容量/60 每秒匀速补充
-- KEYS[1] = rate:key:rpm:{keyId}   Hash: tokens / ts
-- ARGV[1] = 容量 capacity   ARGV[2] = 每秒补充速率   ARGV[3] = 当前秒
local tokens = tonumber(redis.call('hget', KEYS[1], 'tokens') or ARGV[1])
local ts = tonumber(redis.call('hget', KEYS[1], 'ts') or ARGV[3])
local now = tonumber(ARGV[3])
tokens = math.min(tonumber(ARGV[1]), tokens + math.max(0, now - ts) * tonumber(ARGV[2]))
if tokens >= 1 then
    redis.call('hmset', KEYS[1], 'tokens', tokens - 1, 'ts', now)
    return 1
end
redis.call('hmset', KEYS[1], 'tokens', tokens, 'ts', now)
return 0
