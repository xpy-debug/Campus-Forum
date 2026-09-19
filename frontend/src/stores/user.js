import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import * as authApi from '@/api/auth'
import { refreshAccessToken } from '@/api/refresh'
import { ROLE_ADMIN } from '@/constants'

/** 刷新令牌的持久化键名 */
const REFRESH_KEY = 'forum_refresh_token'

export const useUserStore = defineStore('user', () => {
  /**
   * 访问令牌只放在内存里。
   *
   * <p>localStorage 能被页面上的任何脚本读到，而访问令牌是拿来就能用的凭证——
   * 把它写进去等于把「一次 XSS」升级成「长期账号接管」。
   * 代价是刷新页面后内存清空，此时用持久化的刷新令牌换一张新的：
   * 用「丢失」换取「即使被读走也很快过期」。
   */
  const accessToken = ref('')
  const refreshToken = ref(localStorage.getItem(REFRESH_KEY) || '')
  const info = ref(null)

  const isLogin = computed(() => Boolean(accessToken.value))

  /**
   * 是否是管理员。
   *
   * <p>这个值**只用来决定界面显示什么**（管理入口要不要出现），
   * 不是权限判断——前端算出来的布尔值改一下就绕过了。
   * 真正的边界在后端的 {@code @RequireRole} 上，越权请求会被拦截器拒绝。
   * 因此即使这里的 role 是伪造的，用户最多只是看到一个点了就报 10003 的入口。
   */
  const isAdmin = computed(() => (info.value?.role ?? 0) >= ROLE_ADMIN)

  function setToken(token) {
    accessToken.value = token.accessToken
    refreshToken.value = token.refreshToken
    localStorage.setItem(REFRESH_KEY, token.refreshToken)
  }

  function clear() {
    accessToken.value = ''
    refreshToken.value = ''
    info.value = null
    localStorage.removeItem(REFRESH_KEY)
  }

  async function login(form) {
    const data = await authApi.login(form)
    setToken(data)
    info.value = data.user
    return data
  }

  function register(form) {
    return authApi.register(form)
  }

  async function logout() {
    try {
      await authApi.logout()
    } catch {
      // 服务端登出失败（网络断了、令牌已过期）不该把用户困在页面里：
      // 本地状态照样清掉，服务端那张令牌最迟 30 分钟后自然失效
    } finally {
      clear()
    }
  }

  /**
   * 应用启动时恢复登录态。换不到新令牌就当未登录——
   * 刷新令牌也是会过期的（7 天），这不是错误，只是需要重新登录。
   */
  async function restore() {
    if (!refreshToken.value) {
      return
    }
    try {
      setToken(await refreshAccessToken(refreshToken.value))
      info.value = await authApi.me()
    } catch {
      clear()
    }
  }

  return {
    accessToken,
    refreshToken,
    info,
    isLogin,
    isAdmin,
    setToken,
    clear,
    login,
    register,
    logout,
    restore
  }
})
