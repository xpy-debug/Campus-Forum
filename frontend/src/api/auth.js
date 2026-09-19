import request from './request'

/** 注册。成功返回新用户 ID，需要再走一次登录 */
export function register(data) {
  return request.post('/auth/register', data)
}

/** 登录。返回 { accessToken, refreshToken, expiresIn, user } */
export function login(data) {
  return request.post('/auth/login', data)
}

/** 登出。服务端会删除令牌白名单，旧令牌立即失效 */
export function logout() {
  return request.post('/auth/logout')
}

/** 当前登录用户的资料 */
export function me() {
  return request.get('/auth/me')
}
