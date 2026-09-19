-- 每日签到：原子置位 + 返回幂等信号
-- KEYS[1] = forum:points:signin:{userId}:{yyyyMM}   签到位图
-- ARGV[1] = offset   日 - 1（当月第 N 天对应第 N-1 位）
-- ARGV[2] = ttl      位图过期秒数
--
-- 返回 { prev, count }：
--   prev  = 0 表示本次是首次签到（有效），1 表示今天已经签过（幂等，不应加分）
--   count = 本次操作后当月已签天数（BITCOUNT），供前端直接展示，省一次往返
--
-- 【为什么用 SETBIT 的返回值做幂等判断，而不是先 GETBIT 再 SETBIT】
-- 两步写法在并发下必然出问题：两个请求同时 GETBIT 都得到 0，
-- 双双认为「今天还没签」，然后各自 SETBIT 并各自加分。
-- 只能靠数据库唯一索引兜底，而那意味着其中一个请求要以「抛异常」结束——
-- 幂等应该是正常返回，不是异常路径。
--
-- SETBIT 返回的是该位**原来的值**，所以一条命令就同时完成了「判断」与「写入」，
-- 不存在两者之间的时间窗。整个脚本在 Redis 里单线程原子执行，这一点由其保证。

local key    = KEYS[1]
local offset = tonumber(ARGV[1])
local ttl    = tonumber(ARGV[2])

local prev = redis.call('SETBIT', key, offset, 1)

if prev == 0 then
    -- 只在「本次真的置了新位」时刷新 TTL，同一天的重复请求不刷新。
    -- 这一位之差是有意的：置新位一个月最多 31 次，无论怎么刷都只把过期时间
    -- 推到「这个月最后一次签到 + 70 天」，是个有界的量；
    -- 而若不加判断地每次都 EXPIRE，任何人只要反复调签到接口
    -- 就能让这个 key 永不过期——一个可以被外部请求无限续命的 key，
    -- 等于没有 TTL。「续命次数必须由业务动作的次数决定，而不是由请求次数决定」。
    redis.call('EXPIRE', key, ttl)
end

return { prev, redis.call('BITCOUNT', key) }
