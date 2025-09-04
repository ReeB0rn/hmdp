--可重入锁 获取锁LUA脚本
local key = KEYS[1]
local threadId = ARGV[1]
local releaseTime = ARGV[2]

-- 锁不存在的情况
if(redis.call('exists',key)==0) then
    -- 初始化锁
    redis.call('hset',key,threadId,1)
    -- 设置过期时间
    redis.call('expire',key,releaseTime)
    -- 返回
    return 1
end
-- 锁存在且属于当前线程
if(redis.call('hexists',key,threadId)==1) then
    -- 进行加数操作
    redis.call('hincrby',key,threadId,1)
    -- 设置过期时间
    redis.call('expire',key,releaseTime)
    -- 返回
    return 1
end
-- 锁存在且不属于当前线程
return 0