-- KEYS[1]=stock key, KEYS[2]=claimed-user set, KEYS[3]=per-user count key
-- ARGV[1]=user id, ARGV[2]=limit count
redis.call('incrby', KEYS[1], 1)
redis.call('srem', KEYS[2], ARGV[1])
if tonumber(ARGV[2]) > 1 then
  local cnt = tonumber(redis.call('get', KEYS[3]) or '0')
  if cnt > 0 then redis.call('decr', KEYS[3]) end
end
return 1
