import request from './request'

/** 板块列表。只有个位数行，服务端不缓存，前端也不必 */
export function listBoards() {
  return request.get('/boards')
}
