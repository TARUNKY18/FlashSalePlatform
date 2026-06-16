-- rate_limit.lua
-- Sliding window rate limiter using Sorted Set
-- KEYS[1] : rate:{userId}:{saleId}
-- ARGV[1] : current timestamp in milliseconds
-- ARGV[2] : window size in milliseconds (60000 = 60 seconds)
-- ARGV[3] : request limit (10)
-- ARGV[4] : unique request ID (UUID for deduplication)
-- Returns : 0 = allowed | 1 = rate limited

local key       = KEYS[1]
local now       = tonumber(ARGV[1])
local window    = tonumber(ARGV[2])
local limit     = tonumber(ARGV[3])
local requestId = ARGV[4]
local cutoff    = now - window

redis.call('ZREMRANGEBYSCORE', key, '-inf', cutoff)

local count = redis.call('ZCARD', key)

if count >= limit then
    return 1
end

redis.call('ZADD', key, now, requestId)
redis.call('PEXPIRE', key, window)

return 0