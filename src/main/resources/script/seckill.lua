
local voucherId = ARGV[1]
local userId = ARGV[2]
local orderId = ARGV[3]

local stockKey = "seckill:stock:"..voucherId
local orderKey = "seckill:order:"..voucherId


-- 判断库存是否充足
if(tonumber(redis.call('get',stockKey))<=0) then
    -- 库存不足
    return 1
end
-- 判断用户是否下过单
if(redis.call('sismember',orderKey,userId) == 1) then
    -- 用户已经下过单
    return 2
end
-- 用户可下单
-- 减库存
redis.call('incrby',stockKey,-1)
-- 保存用户ID
redis.call('sadd',orderKey,userId)
-- 发送订单消息至消息队列
redis.call('xadd','stream.order','*','userId',userId,'voucherId',voucherId,'id',orderId)
return 0
