-- stock_decrement.lua
-- Atomic check-and-decrement for flash sale inventory
-- KEYS[1] : stock:{saleId}
-- ARGV[1] : quantity to reserve (integer >= 1)
-- Returns : -2 = cache miss | -1 = sold out | >= 0 = remaining stock

local stock = tonumber(redis.call('GET', KEYS[1]))

if stock == nil then
    return -2
end

if stock <= 0 then
    return -1
end

local qty = tonumber(ARGV[1]) or 1

if stock < qty then
    return -1
end

redis.call('DECRBY', KEYS[1], qty)
return stock - qty