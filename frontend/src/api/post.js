import request from './request'

/**
 * 帖子列表。
 *
 * @param {{boardId?: number, sort?: string, cursor?: string, size?: number}} params
 *        cursor 为空表示第一页；返回值里的 nextCursor 原样回传即可翻页
 */
export function listPosts(params) {
  return request.get('/posts', { params })
}

export function getPost(postId) {
  return request.get(`/posts/${postId}`)
}

/**
 * 发帖。
 *
 * <p>幂等令牌由调用方生成并在重试时复用——这是它唯一的作用：
 * 网络超时后用户再点一次「发布」，服务端返回首次创建的帖子而不是再发一篇。
 */
export function createPost(data, idempotencyKey) {
  return request.post('/posts', data, idempotencyKey ? { headers: { 'Idempotency-Key': idempotencyKey } } : {})
}
