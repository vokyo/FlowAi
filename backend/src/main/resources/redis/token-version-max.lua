-- Writes a user's token version unless the key already holds a greater one.
-- Versions only ever go up, so the greater value is the newer one: a version read
-- from the database before a logout and written back after the logout stored its
-- new version cannot replace it, whichever write reaches Redis last.
--
-- KEYS[1]  the user's token version key
-- ARGV[1]  the version
-- ARGV[2]  how long the entry lives, in milliseconds
--
-- Returns 1 when it wrote the version, 0 when the key already held a version at
-- least as great. An equal or older write leaves the entry's expiry alone, so an
-- entry nobody updates still runs out.

local current = tonumber(redis.call('GET', KEYS[1]))
if current == nil or tonumber(ARGV[1]) > current then
  redis.call('SET', KEYS[1], ARGV[1], 'PX', ARGV[2])
  return 1
end
return 0
