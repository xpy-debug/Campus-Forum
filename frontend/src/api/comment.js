import request from './request'

/** 发表评论（parentId 为 0）或回复（parentId 为被回复的评论 ID） */
export function createComment(data) {
  return request.post('/comments', data)
}

/** 某帖的一级评论分页，每条附带前几条回复预览 */
export function listComments(postId, params) {
  return request.get(`/posts/${postId}/comments`, { params })
}

export function deleteComment(commentId) {
  return request.delete(`/comments/${commentId}`)
}
