import request from './request'

/**
 * 签到页状态：本月日历、连续天数、全勤进度。
 *
 * <p>一次拿全一个月的数据，前端不必按天查询。返回里的 `today` 是**服务器时间**，
 * 渲染「今天」必须用它而不是 `new Date()`——客户端时钟不准时，
 * 用户会看到「今天已签到」却点不动按钮。
 */
export function signinStatus() {
  return request.get('/points/signin/status')
}

/**
 * 签到。
 *
 * <p>接口总是返回成功（code 为 0），今天是否真的签上了看 `data.success`。
 * 重复签到是正常状态而不是错误，因此这里**不需要** try/catch，
 * 也不该把 `success: false` 当成失败来提示。
 */
export function signin() {
  return request.post('/points/signin')
}

/** 我的积分账户：余额、累计获得、累计消耗 */
export function getAccount() {
  return request.get('/points/account')
}

/** 我的积分明细。cursor 为上一页的 nextCursor，首页不传 */
export function listRecords(params) {
  return request.get('/points/records', { params })
}
