import request from './request'

// ==================== 用户侧 ====================

/**
 * 秒杀活动列表。
 *
 * 顺序由服务端定死为「进行中 → 即将开始 → 已结束」，前端不再排序：
 * 这个顺序表达的是「现在能抢什么」，属于业务判断，放在浏览器里改一次就散一次。
 */
export function listSeckillActivities() {
  return request.get('/seckill/activities')
}

/**
 * 抢购。
 *
 * 返回的是「排队中 + orderNo」，不是订单本身：Redis 预扣成功就立刻返回，
 * 订单由 MQ 消费者异步落库。拿到 orderNo 之后要轮询 getSeckillResult。
 *
 * 重复点击是安全的——Lua 会返回同一个 orderNo，不会重复扣库存。
 */
export function grab(activityId) {
  return request.post(`/seckill/${activityId}/grab`)
}

/**
 * 轮询抢购结果。
 *
 * 取值为 PENDING / SUCCESS / FAILED / TIMEOUT / CANCELLED / NONE。
 * 服务端在结果键过期后会回查订单表，因此刷新页面也能拿到正确结果。
 */
export function getSeckillResult(activityId) {
  return request.get(`/seckill/${activityId}/result`)
}

/**
 * 支付（用积分）。
 *
 * 积分不足时返回 16002，订单仍是「待支付」，可以再试或等它超时自动关闭。
 */
export function paySeckillOrder(orderNo) {
  return request.post(`/seckill/orders/${orderNo}/pay`)
}

/** 取消（仅本人、仅待支付）。取消后不能重新抢购——限购是活动期内的一次性资格 */
export function cancelSeckillOrder(orderNo) {
  return request.post(`/seckill/orders/${orderNo}/cancel`)
}

/** 我的秒杀订单 */
export function listMySeckillOrders(params) {
  return request.get('/seckill/orders', { params })
}

// ==================== 管理侧（后端 @RequireRole 校验，前端隐藏入口不算安全边界） ====================

/** 活动列表（含草稿与已下线）。status 不传表示全部 */
export function adminListActivities(params) {
  return request.get('/admin/seckill/activities', { params })
}

/** 新建活动。只传 goodsId，商品名与封面由服务端取回后快照写入 */
export function adminCreateActivity(data) {
  return request.post('/admin/seckill/activities', data)
}

/** 编辑活动。为 null 的字段保持不变；库存与已售数不可通过本接口调整 */
export function adminUpdateActivity(activityId, data) {
  return request.put(`/admin/seckill/activities/${activityId}`, data)
}

/** 上下线。status 走 query，与后端 @RequestParam 对应 */
export function adminChangeActivityStatus(activityId, status) {
  return request.post(`/admin/seckill/activities/${activityId}/status`, null, { params: { status } })
}

/** 手动预热。返回本次是否为「首次写入库存」 */
export function adminWarmupActivity(activityId) {
  return request.post(`/admin/seckill/activities/${activityId}/warmup`)
}

/** 强制库存对账。不跳过进行中的活动，由调用者确认当前没有在途请求 */
export function adminReconcileActivity(activityId) {
  return request.post(`/admin/seckill/activities/${activityId}/reconcile`)
}

/** 全部秒杀订单 */
export function adminListSeckillOrders(params) {
  return request.get('/admin/seckill/orders', { params })
}

/** 标记已发放：已支付 → 已完成 */
export function adminFinishSeckillOrder(orderNo, remark) {
  return request.post(`/admin/seckill/orders/${orderNo}/finish`, null, { params: { remark } })
}

/** 管理员取消。已支付的订单会退回积分并把库存从已售退回可售 */
export function adminCancelSeckillOrder(orderNo, remark) {
  return request.post(`/admin/seckill/orders/${orderNo}/cancel`, null, { params: { remark } })
}
