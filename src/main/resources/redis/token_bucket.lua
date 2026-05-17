-- Token-bucket rate limiter (Spec §11).
-- KEYS[1] = ratelimit:hold:{user_id}
-- ARGV[1] = capacity        (tokens)
-- ARGV[2] = refill_per_sec  (tokens/sec, fractional)
-- ARGV[3] = now_ms          (server-supplied epoch ms)
--
-- Returns:
--   1                       — request accepted, bucket decremented
--   integer (retry_after_s) — request rejected, wait this many seconds for one token
--
-- The bucket state is stored in a hash with two fields {tokens, ts}; we refill lazily on
-- every call by computing elapsed*refill_per_sec/1000 since the last touch. Capacity 10 and
-- refill 10/min (≈0.1667/sec) gives a steady 10 req/min with burst-of-10 tolerance.

local key       = KEYS[1]
local capacity  = tonumber(ARGV[1])
local refill    = tonumber(ARGV[2])
local now_ms    = tonumber(ARGV[3])

local raw = redis.call('HMGET', key, 'tokens', 'ts')
local tokens = tonumber(raw[1])
local last   = tonumber(raw[2])
if tokens == nil then tokens = capacity end
if last   == nil then last   = now_ms end

local elapsed_ms = math.max(0, now_ms - last)
local refilled   = math.min(capacity, tokens + (elapsed_ms * refill) / 1000)

if refilled < 1 then
    -- not enough tokens — tell the caller how many seconds until 1 token is available
    local need = 1 - refilled
    local wait = math.ceil(need / refill)
    if wait < 1 then wait = 1 end
    -- store the refilled value so subsequent calls keep the timer fresh
    redis.call('HMSET', key, 'tokens', refilled, 'ts', now_ms)
    redis.call('EXPIRE', key, 120)
    return wait
end

redis.call('HMSET', key, 'tokens', refilled - 1, 'ts', now_ms)
redis.call('EXPIRE', key, 120)
return 0
