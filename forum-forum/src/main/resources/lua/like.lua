-- 点赞
-- KEYS[1] = forum:like:target:{type}:{id}:users   点赞用户集合
-- KEYS[2] = forum:like:target:{type}:{id}:count   点赞计数
-- ARGV[1] = userId
-- ARGV[2] = 计数键缺失时的基准值（由调用方 SELECT COUNT(*) 得到）
--
-- 返回 1 表示本次真的新增了点赞（需要落库），0 表示早就点过（幂等，不落库）。

-- SADD 的返回值天然就是判重结果：1 = 原本不在集合里，0 = 已经在
local added = redis.call('SADD', KEYS[1], ARGV[1])

-- 计数键不存在时先用数据库的基准值播种，再 INCR。
-- 这一段之所以安全，是因为整个脚本在 Redis 里是单线程原子执行的：
-- 两个并发请求不可能交错执行「判断是否存在」和「写入基准值」，
-- 因此不会出现后到的请求把先到者已经 INCR 过的值覆盖回去。
if redis.call('EXISTS', KEYS[2]) == 0 then
    redis.call('SET', KEYS[2], ARGV[2])
end

if added == 1 then
    redis.call('INCR', KEYS[2])
    return 1
end

return 0
