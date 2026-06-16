-- stock_prewarm.lua
-- Idempotent pre-warm of stock counter before sale start
-- KEYS[1] : stock:{saleId}        — the counter
-- KEYS[2] : stock:warmed:{saleId} — the completion marker (same shard via hash tag)
-- ARGV[1] : totalStock (integer)
-- ARGV[2] : TTL in seconds
-- Returns : 1 = warmed | 0 = already warmed by another pod

local already = redis.call('GET', KEYS[2])
if already then
    return 0
end

redis.call('SET', KEYS[1], ARGV[1], 'EX', ARGV[2])
redis.call('SET', KEYS[2], '1',     'EX', ARGV[2])

return 1