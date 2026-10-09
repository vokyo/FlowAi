-- Lets go of a run lock only if the caller still holds it. Reading and deleting in
-- one script means nothing can happen in between, such as the lock expiring and
-- someone else taking it, so a caller can never delete a lock that is not theirs.
--
-- KEYS[1]  the lock
-- ARGV[1]  the owner letting go of it
--
-- Returns 1 when it deleted the lock, 0 when the lock was gone or someone else's.

if redis.call('GET', KEYS[1]) == ARGV[1] then
  return redis.call('DEL', KEYS[1])
end
return 0
