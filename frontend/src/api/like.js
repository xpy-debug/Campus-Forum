import request from './request'

/** 目标类型，与后端 UserLike.TARGET_* 一致 */
export const TARGET_POST = 1
export const TARGET_COMMENT = 2

/**
 * 点赞。
 * <p>接口立刻返回，此时数据只在 Redis 里；数据库由消息队列异步补齐，
 * 所以返回值里的 likeCount 是实时值，可以直接用来刷新界面。
 */
export function like(targetType, targetId) {
  return request.post('/likes', { targetType, targetId })
}

/** 取消点赞。参数走 query：DELETE 带请求体可能被网关丢掉 */
export function unlike(targetType, targetId) {
  return request.delete('/likes', { params: { targetType, targetId } })
}
