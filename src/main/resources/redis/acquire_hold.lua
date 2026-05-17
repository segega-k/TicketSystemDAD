-- KEYS[1] = hold:group:{hold_group_id}
-- KEYS[2] = purchase:count:{user_id}:{event_id}
-- KEYS[3..N] = hold:seat:{seat_id}
-- ARGV[1]=user_id ARGV[2]=event_id ARGV[3]=hold_token ARGV[4]=seat_ids_csv ARGV[5]=ttl_seconds ARGV[6]=max_per_user
local group_key = KEYS[1]
local count_key = KEYS[2]
local ttl = tonumber(ARGV[5])
local max_per_user = tonumber(ARGV[6])
local n_seats = #KEYS - 2
local current = tonumber(redis.call('GET', count_key) or '0')
if current + n_seats > max_per_user then
  return -2
end
for i = 3, #KEYS do
  if redis.call('EXISTS', KEYS[i]) == 1 then
    return 0
  end
end
redis.call('HSET', group_key,
  'user_id', ARGV[1],
  'event_id', ARGV[2],
  'token', ARGV[3],
  'seat_ids', ARGV[4],
  'created_at', tostring(redis.call('TIME')[1]))
redis.call('EXPIRE', group_key, ttl)
for i = 3, #KEYS do
  redis.call('SET', KEYS[i], ARGV[3], 'EX', ttl)
end
redis.call('INCRBY', count_key, n_seats)
redis.call('EXPIRE', count_key, 86400)
return 1
