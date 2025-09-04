--if (redis.call('get',KEYS[1])==ARGV[1])then
--    return redis.call('delete',KEYS[1])
--end
--return 0

-- 可重入锁 释放锁
local key = KEYS[1]
local threadId = ARGV[1]
local releaseTime = ARGV[2]
-- 检查是否持有锁
if(redis.call('hexists',key,threadId)==1) then
    -- 持有锁
    -- 是否可释放
    if (redis.call('hget',key,threadId) == '1') then
        -- 可释放
        redis.call('DEL',key)
        return nil
    end
    -- 不可释放
    redis.call('hincrby',key,threadId,-1)
    redis.call('expire',key,releaseTime)
    return nil
end
-- 不持有锁
return nil
