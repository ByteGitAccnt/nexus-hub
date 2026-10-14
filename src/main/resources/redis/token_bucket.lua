local key = KEYS[1]

local capacity = tonumber(ARGV[1])
local refill_rate = tonumber(ARGV[2])
local refill_period = tonumber(ARGV[3])
local ttl = tonumber(ARGV[4])

-- Redis TIME returns:
-- { seconds, microseconds }
local redis_time = redis.call('TIME')

local now_seconds = tonumber(redis_time[1])
local now_microseconds = tonumber(redis_time[2])

-- Store time in microseconds so we have enough precision
local now = (now_seconds * 1000000) + now_microseconds

local tokens = redis.call('HGET', key, 'tokens')
local last_refill = redis.call('HGET', key, 'lastRefillTime')

-- First request: initialize a full bucket
if not tokens or not last_refill then
    tokens = capacity
    last_refill = now
else
    tokens = tonumber(tokens)
    last_refill = tonumber(last_refill)
end

local elapsed = now - last_refill

-- Calculate newly available tokens.
--
-- refill_rate tokens are generated during refill_period seconds.
-- Convert elapsed microseconds into the corresponding fraction
-- of the refill period.
local refill =
    (elapsed * refill_rate) /
    (refill_period * 1000000)

tokens = math.min(capacity, tokens + refill)

local allowed = 0
local retry_after = 0

if tokens >= 1 then

    tokens = tokens - 1
    allowed = 1

else

    -- How long until one token becomes available?
    local tokens_needed = 1 - tokens

    retry_after =
        math.ceil(
            (tokens_needed * refill_period) /
            refill_rate
        )

end

-- Only move the refill timestamp forward when we actually
-- accounted for elapsed time.
last_refill = now

redis.call(
    'HSET',
    key,
    'tokens',
    tokens,
    'lastRefillTime',
    last_refill
)

redis.call(
    'EXPIRE',
    key,
    ttl
)

local remaining = math.floor(tokens)

return {
    allowed,
    remaining,
    retry_after
}