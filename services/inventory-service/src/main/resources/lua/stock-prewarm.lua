-- stock-prewarm.lua
-- Revision-fenced atomic initialization of the stock projection pair.
-- KEYS[1] : stock:{saleId}
-- KEYS[2] : stock:version:{saleId}
-- ARGV[1] : authoritative current_stock (non-negative integer)
-- ARGV[2] : authoritative StockLevel revision (non-negative integer)
-- ARGV[3] : TTL in milliseconds (positive integer, saleEnd + 10 minutes - now)
-- Returns : 1 = WARMED | 2 = UPDATED | 3 = ALREADY_CURRENT | 4 = STALE_IGNORED
--          -1 = INVALID_STATE

local WARMED = 1
local UPDATED = 2
local ALREADY_CURRENT = 3
local STALE_IGNORED = 4
local INVALID_STATE = -1

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
    if string.len(left) < string.len(right) then return -1 end
    if string.len(left) > string.len(right) then return 1 end
    if left < right then return -1 end
    if left > right then return 1 end
    return 0
end

local MAX_LONG = '9223372036854775807'
local MAX_INT = 2147483647

-- Validate incoming arguments before touching Redis
local incoming_stock = normalize_non_negative_integer(ARGV[1])
if incoming_stock == nil or tonumber(incoming_stock) > MAX_INT then
    return INVALID_STATE
end

local incoming_revision = normalize_non_negative_integer(ARGV[2])
if incoming_revision == nil
        or compare_non_negative_integers(incoming_revision, MAX_LONG) > 0 then
    return INVALID_STATE
end

local stock_raw = redis.call('GET', KEYS[1])
local version_raw = redis.call('GET', KEYS[2])

local stock_present = (stock_raw ~= false)
local version_present = (version_raw ~= false)

if not stock_present and not version_present then
    -- Both keys missing: initialize pair with fixed expiration
    redis.call('SET', KEYS[1], ARGV[1], 'PX', ARGV[3])
    redis.call('SET', KEYS[2], ARGV[2], 'PX', ARGV[3])
    return WARMED

elseif stock_present and version_present then
    -- Both keys present: validate stored values
    local stored_stock = normalize_non_negative_integer(stock_raw)
    if stored_stock == nil or tonumber(stored_stock) > MAX_INT then
        return INVALID_STATE
    end

    local stored_revision = normalize_non_negative_integer(version_raw)
    if stored_revision == nil
            or compare_non_negative_integers(stored_revision, MAX_LONG) > 0 then
        return INVALID_STATE
    end

    -- Both keys must carry a TTL (not persistent) and TTLs must match
    local stock_expiry = redis.call('PEXPIRETIME', KEYS[1])
    local version_expiry = redis.call('PEXPIRETIME', KEYS[2])
    if stock_expiry < 0 or version_expiry < 0 or stock_expiry ~= version_expiry then
        return INVALID_STATE
    end

    local cmp = compare_non_negative_integers(incoming_revision, stored_revision)
    if cmp < 0 then
        return STALE_IGNORED
    elseif cmp == 0 then
        return ALREADY_CURRENT
    else
        -- Newer revision: update stock and revision while preserving existing expiration
        redis.call('SET', KEYS[1], ARGV[1], 'KEEPTTL')
        redis.call('SET', KEYS[2], ARGV[2])
        redis.call('PEXPIREAT', KEYS[2], stock_expiry)
        return UPDATED
    end

elseif stock_present and not version_present then
    -- Stock key without revision: stored revision is indeterminate, fail closed
    return INVALID_STATE

else
    -- Only version key present: compare against its stored revision
    local stored_revision = normalize_non_negative_integer(version_raw)
    if stored_revision == nil
            or compare_non_negative_integers(stored_revision, MAX_LONG) > 0 then
        return INVALID_STATE
    end

    -- Surviving version key must carry a TTL
    local version_expiry = redis.call('PEXPIRETIME', KEYS[2])
    if version_expiry < 0 then
        return INVALID_STATE
    end

    local cmp = compare_non_negative_integers(incoming_revision, stored_revision)
    if cmp < 0 then
        -- Older incoming revision: leave surviving state, fail closed
        return INVALID_STATE
    end

    -- Not older: remove partial pair and initialize from authoritative snapshot
    redis.call('DEL', KEYS[1], KEYS[2])
    redis.call('SET', KEYS[1], ARGV[1], 'PX', ARGV[3])
    redis.call('SET', KEYS[2], ARGV[2], 'PX', ARGV[3])
    return WARMED
end
