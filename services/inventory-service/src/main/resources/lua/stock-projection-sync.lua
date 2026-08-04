-- stock-projection-sync.lua
-- Revision-fenced synchronization of the authoritative durable stock projection.
-- KEYS[1] : stock:{saleId}
-- KEYS[2] : stock:version:{saleId}
-- ARGV[1] : authoritative durable stock
-- ARGV[2] : authoritative durable revision
-- Returns : 1 = applied | 2 = stale ignored | 3 = missing
--          -1 = invalid stored stock | -2 = invalid stored revision

local APPLIED = 1
local STALE_IGNORED = 2
local MISSING = 3
local INVALID_STOCK = -1
local INVALID_REVISION = -2

local function normalize_non_negative_integer(value)
    if value == false or string.match(value, '^%d+$') == nil then
        return nil
    end

    local normalized = string.gsub(value, '^0+', '')
    if normalized == '' then
        return '0'
    end
    return normalized
end

local function compare_non_negative_integers(left, right)
    if string.len(left) < string.len(right) then
        return -1
    end
    if string.len(left) > string.len(right) then
        return 1
    end
    if left < right then
        return -1
    end
    if left > right then
        return 1
    end
    return 0
end

local stored_stock_raw = redis.call('GET', KEYS[1])
if stored_stock_raw == false then
    redis.call('DEL', KEYS[2])
    return MISSING
end

local stored_revision_raw = redis.call('GET', KEYS[2])
if stored_revision_raw == false then
    redis.call('DEL', KEYS[1], KEYS[2])
    return MISSING
end

local stored_stock = normalize_non_negative_integer(stored_stock_raw)
if stored_stock == nil or tonumber(stored_stock) > 2147483647 then
    return INVALID_STOCK
end

local stored_revision = normalize_non_negative_integer(stored_revision_raw)
local maximum_revision = '9223372036854775807'
if stored_revision == nil
        or compare_non_negative_integers(stored_revision, maximum_revision) > 0 then
    return INVALID_REVISION
end

local durable_revision = normalize_non_negative_integer(ARGV[2])
if durable_revision == nil then
    return INVALID_REVISION
end

if compare_non_negative_integers(durable_revision, stored_revision) < 0 then
    return STALE_IGNORED
end

local absolute_expiration = redis.call('PEXPIRETIME', KEYS[1])
redis.call('SET', KEYS[1], ARGV[1], 'KEEPTTL')
redis.call('SET', KEYS[2], ARGV[2])
if absolute_expiration >= 0 then
    redis.call('PEXPIREAT', KEYS[2], absolute_expiration)
end
return APPLIED
