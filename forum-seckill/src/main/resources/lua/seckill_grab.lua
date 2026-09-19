-- 秒杀抢购：一次 Lua 执行内完成「幂等重放 + 预热检查 + 限购检查 + 库存检查 + 扣减 + 记单号」。
--
-- 为什么必须是 Lua 而不是多次 Redis 调用：这条链路上任何两步之间被插入一个并发请求，
-- 都会破坏「不超卖」或「一人一单」。Lua 在 Redis 单线程内原子执行，中间不可能有别人插进来。
--
-- KEYS[1] = forum:seckill:stock:{activityId}              剩余库存
-- KEYS[2] = forum:seckill:bought:{activityId}             已抢用户 Hash（field=userId, value=数量）
-- KEYS[3] = forum:seckill:order:{activityId}:{userId}      该用户抢到的 orderNo
-- KEYS[4] = forum:seckill:order:no:{yyyyMMdd}             订单号日内序号
-- ARGV[1] = userId
-- ARGV[2] = perUserLimit
-- ARGV[3] = 订单号前缀，如 "S20260917"
-- ARGV[4] = 序号 key 的 TTL（秒）
--
-- 返回值（统一用数组，避免「正整数」与「负数状态码」混在同一个位置）：
--   {>=0 剩余库存, orderNo}  抢购成功；剩余库存 >= 0 是正常态，= 0 表示刚好抢到最后一件
--   {-1}  库存不足
--   {-2}  已达限购（含「已取消但资格不退还」的情况）
--   {-3}  活动未预热

-- ① 幂等重放。用户连点十次、或网络重试，都应该拿到同一张单，而不是「已达限购」。
--    放在最前面：用户要的是「我那一单」，不是一条业务规则的拒绝说明。
local existed = redis.call('GET', KEYS[3])
if existed then
    local left = redis.call('GET', KEYS[1])
    return {tonumber(left or '0'), existed}
end

-- ② 预热检查。库存 key 不存在说明还没预热——这与「被抢光了」是两回事，
--    必须区分开：前者该提示「活动太火爆」，后者才是「已抢光」。
local stock = redis.call('GET', KEYS[1])
if stock == false then
    return {-3}
end

-- ③ 限购检查。用 Hash 而不是 Set，是为了让 perUserLimit 可配置（见 RedisKey.seckillBought）。
--    取消订单不会删除这个标记，所以「取消后不能重抢」在这里也是天然成立的。
local bought = tonumber(redis.call('HGET', KEYS[2], ARGV[1]) or '0')
if bought >= tonumber(ARGV[2]) then
    return {-2}
end

-- ④ 库存检查
if tonumber(stock) <= 0 then
    return {-1}
end

-- ⑤ 生成订单号 + 原子扣减。
--    订单号在这里生成而不是应用层预先生成：失败路径不消耗序号，
--    且「生成 + 扣库存 + 记录单号」严格原子，不会出现「扣了库存却没拿到单号」。
local seq = redis.call('INCR', KEYS[4])
if seq == 1 then
    redis.call('EXPIRE', KEYS[4], tonumber(ARGV[4]))
end
local orderNo = ARGV[3] .. string.format('%06d', seq)

redis.call('DECR', KEYS[1])
redis.call('HINCRBY', KEYS[2], ARGV[1], 1)
redis.call('SET', KEYS[3], orderNo)

return {tonumber(stock) - 1, orderNo}
