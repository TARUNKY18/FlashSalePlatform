-- stock_reconcile.lua
-- Correct Redis stock when it drifts from Postgres source of truth
-- KEYS[1] : stock:{saleId}
-- ARGV[1] : authoritative stock value from Postgres
-- ARGV[2] : TTL in seconds (recalculated from saleEnd)
-- Returns : 1 = reconciled (key existed) | 0 = key was missing, set fresh

local current = redis.call('GET', KEYS[1])

if current == nil then
    redis.call('SET', KEYS[1], ARGV[1], 'EX', ARGV[2])
    return 0
end

if tonumber(current) ~= tonumber(ARGV[1]) then
    redis.call('SET', KEYS[1], ARGV[1], 'EX', ARGV[2])
end

return 1