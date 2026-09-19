import axios from 'axios'

/**
 * 令牌续期。单独成一个模块，是为了打断 request 与 user store 之间的循环依赖：
 *
 * <pre>
 *   request.js  →  stores/user.js  →  refresh.js
 *        └──────────────────────────→  refresh.js
 * </pre>
 *
 * <p>把 refresh 留在 request.js 里的话，store 就得反过来导入 request.js，
 * 形成环。这里不依赖任何业务模块，两边都能安心引用。
 *
 * <p>本实例<b>不挂任何拦截器</b>：刷新请求自己失败时若又被拦截器拦下去刷新，
 * 就会递归调用自己。
 */
const bare = axios.create({ baseURL: '/api', timeout: 10000 })

/**
 * 并发的刷新只会真正发出一次请求，其余调用等待同一个 Promise。
 *
 * <p>不加这层去重的话，一页里同时发出的 5 个请求在令牌过期时会打出 5 次刷新，
 * 而每次刷新都会签发一张新令牌并写入 Redis 白名单——凭空多出 4 次会话。
 */
let refreshing = null

export function refreshAccessToken(refreshToken) {
  if (!refreshToken) {
    return Promise.reject(new Error('没有可用的刷新令牌'))
  }
  if (!refreshing) {
    refreshing = bare
      .post('/auth/refresh', { refreshToken })
      .then(({ data }) => {
        if (data.code !== 0) {
          throw new Error(data.message)
        }
        return data.data
      })
      .finally(() => {
        // 无论成功失败都要清空，否则一次失败会让后续所有刷新都复用它
        refreshing = null
      })
  }
  return refreshing
}
