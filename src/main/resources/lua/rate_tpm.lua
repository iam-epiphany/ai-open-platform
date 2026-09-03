-- API Key 级 TPM 滑动窗口（60s）：member 编码为 "cost:random"，窗口内 cost 累加
-- KEYS[1] = rate:key:tpm:{keyId}   ZSET（score=请求时间，member=cost:random）
-- ARGV[1] = 窗口秒数 60   ARGV[2] = TPM 上限   ARGV[3] = 当前秒   ARGV[4] = 本次预估 token 数   ARGV[5] = 随机 member
redis.call('zremrangebyscore', KEYS[1], '-inf', tonumber(ARGV[3]) - tonumber(ARGV[1]))
local total = 0
local entries = redis.call('zrange', KEYS[1], 0, -1)
for _, m in ipairs(entries) do
    local idx = string.find(m, ':')
    if idx then
        total = total + tonumber(string.sub(m, 1, idx - 1))
    end
end
if total + tonumber(ARGV[4]) > tonumber(ARGV[2]) then
    return 0
end
redis.call('zadd', KEYS[1], ARGV[3], ARGV[5])
redis.call('expire', KEYS[1], tonumber(ARGV[1]) * 2)
return 1
