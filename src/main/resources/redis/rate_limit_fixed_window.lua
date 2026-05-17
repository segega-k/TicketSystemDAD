local c=redis.call('incr',KEYS[1]); if c==1 then redis.call('expire',KEYS[1],ARGV[2]) end; if c>tonumber(ARGV[1]) then return redis.call('ttl',KEYS[1]) end; return 0
