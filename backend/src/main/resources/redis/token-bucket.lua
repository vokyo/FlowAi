-- One token bucket, kept as a hash of how many tokens it holds and when that was
-- last worked out. Tokens come back continuously, capacity of them per window, the
-- way the in-process Bucket4j buckets refill. Redis runs the whole script before any
-- other command, so two instances cannot both take the last token.
--
-- A missing key is a full bucket, so a key only lives until its bucket would be
-- full again. The clock is Redis's own, which every instance shares.
--
-- KEYS[1]  the bucket
-- ARGV[1]  capacity
-- ARGV[2]  window, in microseconds
-- ARGV[3]  "consume" takes a token, "refund" gives one back
--
-- Returns {1, 0} when it took or gave back a token, and {0, micros} when the bucket
-- is empty, with how long until the next token.

local capacity = tonumber(ARGV[1])
local window = tonumber(ARGV[2])
local time = redis.call('TIME')
local at = time[1] .. string.format('%06d', tonumber(time[2]))
local now = tonumber(at)

local state = redis.call('HMGET', KEYS[1], 'tokens', 'at')
local tokens = tonumber(state[1]) or capacity
local since = tonumber(state[2]) or now
tokens = math.min(capacity, tokens + math.max(0, now - since) * capacity / window)

local result
if ARGV[3] == 'refund' then
  tokens = math.min(capacity, tokens + 1)
  result = {1, 0}
elseif tokens >= 1 then
  tokens = tokens - 1
  result = {1, 0}
else
  result = {0, math.ceil((1 - tokens) * window / capacity)}
end

local untilFull = math.ceil((capacity - tokens) * window / capacity)
if untilFull <= 0 then
  redis.call('DEL', KEYS[1])
else
  redis.call('HSET', KEYS[1], 'tokens', string.format('%.17g', tokens), 'at', at)
  redis.call('PEXPIRE', KEYS[1], math.ceil(untilFull / 1000))
end
return result
