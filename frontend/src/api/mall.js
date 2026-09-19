import request from './request'

// ==================== 用户侧：浏览与下单 ====================

/**
 * 在售商品列表。
 *
 * <p>不分页：校园商城的商品数量是个位到几十的量级，一屏能看完。
 * 每条带 `canExchange`（积分够不够 + 有没有库存）与 `lackPoints`，
 * 按钮是否可用由后端判定，前端不重复算一遍规则。
 */
export function listGoods() {
  return request.get('/mall/goods')
}

/** 商品详情 */
export function getGoods(goodsId) {
  return request.get(`/mall/goods/${goodsId}`)
}

/**
 * 兑换商品。
 *
 * <p>请求体里只有 `goodsId` 和留言，**没有价格**——花多少积分由服务端
 * 从商品行读出，前端传金额的接口是可以被改请求体骗的。
 */
export function createOrder(data) {
  return request.post('/mall/orders', data)
}

/** 我的兑换记录 */
export function listMyOrders(params) {
  return request.get('/mall/orders', { params })
}

/**
 * 取消兑换并退回积分。
 *
 * <p>用 POST 而不是 DELETE：取消不是删除订单，而是让它进入已取消状态，
 * 订单本身还要在记录里留着。
 */
export function cancelOrder(orderId) {
  return request.post(`/mall/orders/${orderId}/cancel`)
}

// ==================== 管理侧：只有管理员能调 ====================
// 这些接口的权限由后端 @RequireRole(ROLE_ADMIN) 在拦截器层校验。
// 前端把管理入口藏起来只是「让普通用户看不到」，不是安全边界——
// 真正拦住越权的是服务端，所以这里不需要也不应该做任何权限判断。

/** 商品列表（含草稿与已下架）。status 不传表示全部 */
export function adminListGoods(params) {
  return request.get('/admin/mall/goods', { params })
}

/** 新建商品，返回新商品 ID */
export function adminCreateGoods(data) {
  return request.post('/admin/mall/goods', data)
}

/** 编辑商品。表单里为 null 的字段保持不变 */
export function adminUpdateGoods(goodsId, data) {
  return request.put(`/admin/mall/goods/${goodsId}`, data)
}

/** 上架 / 下架 / 转草稿。status 走 query，与后端的 @RequestParam 对应 */
export function adminChangeGoodsStatus(goodsId, status) {
  return request.post(`/admin/mall/goods/${goodsId}/status`, null, { params: { status } })
}

/** 兑换订单列表（全部用户） */
export function adminListOrders(params) {
  return request.get('/admin/mall/orders', { params })
}

/** 标记订单已发放。只有「待发放」的订单能发货，否则返回 16007 */
export function adminFinishOrder(orderId, remark) {
  return request.post(`/admin/mall/orders/${orderId}/finish`, null, { params: { remark } })
}

/** 管理员取消订单并退回积分与库存 */
export function adminCancelOrder(orderId, remark) {
  return request.post(`/admin/mall/orders/${orderId}/cancel`, null, { params: { remark } })
}
