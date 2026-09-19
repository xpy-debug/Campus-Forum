-- 取消点赞
-- KEYS[1] = forum:like:target:{type}:{id}:users
-- KEYS[2] = forum:like:target:{type}:{id}:count
-- ARGV[1] = userId
-- ARGV[2] = 计数键缺失时的基准值
--
-- 返回 1 表示本次真的取消了（需要落库），0 表示本来就没点赞（幂等）。

local removed = redis.call('SREM', KEYS[1], ARGV[1])

if removed == 1 then
    if redis.call('EXISTS', KEYS[2]) == 0 then
        redis.call('SET', KEYS[2], ARGV[2])
    end
    local count = redis.call('DECR', KEYS[2])
    -- 防御性修正：正常的业务路径不会让计数降到 0 以下（SREM 返回 1 就说明集合里
    -- 确实有这个用户，计数至少是 1），但如果计数曾被外部手段改小过，
    -- 负数会一路传播到前端，不如在这里截断
    if count < 0 then
        redis.call('SET', KEYS[2], 0)
        return 0
    end
    return 1
end

return 0
