-- stock_release.lua
-- Atomic stock restore on saga compensation or reservation expiry
-- KEYS[1] : stock:{saleId}
-- ARGV[1] : quantity to restore
-- ARGV[2] : total_allocated ceiling (prevents over-restore on replay)
-- Returns : -2 = key missing (sale ended) | >= 0 = new stock level

local stock = tonumber(redis.call('GET', KEYS[1]))

if stock == nil then
    return -2
end

local qty      = tonumber(ARGV[1])
local ceiling  = tonumber(ARGV[2])
local newStock = math.min(stock + qty, ceiling)

redis.call('SET', KEYS[1], newStock)
return newStock