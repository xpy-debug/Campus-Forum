import axios from 'axios'
import { ElMessage } from 'element-plus'
import { useUserStore } from '@/stores/user'
import { refreshAccessToken } from './refresh'

/** 与后端 ErrorCode.UNAUTHORIZED 对应。后端 HTTP 状态码恒为 200，错误码在响应体里 */
const UNAUTHORIZED = 10002

/** 业务请求实例。响应拦截器直接把 Result.data 交给调用方，业务代码不必层层解包 */
const service = axios.create({ baseURL: '/api', timeout: 10000 })

service.interceptors.request.use((config) => {
  const { accessToken } = useUserStore()
  if (accessToken) {
    config.headers.Authorization = `Bearer ${accessToken}`
  }
  return config
})

async function toLogin() {
  useUserStore().clear()
  // 动态导入打破循环依赖：router → views → api → router。
  // 顶层静态导入其实也能跑（ESM 会把导入提升），但那时能否拿到 router
  // 取决于模块的求值顺序，一旦出错的表现是 router 为 undefined，很难定位
  const { default: router } = await import('@/router')
  if (router.currentRoute.value.name !== 'login') {
    await router.push({ name: 'login', query: { redirect: router.currentRoute.value.fullPath } })
  }
}

service.interceptors.response.use(
  async (response) => {
    const body = response.data
    if (body.code === 0) {
      return body.data
    }

    // 访问令牌过期：换一张再重放原请求。__retried 保证只重放一次，
    // 避免「刷新成功但仍返回未登录」时无限重试
    if (body.code === UNAUTHORIZED && !response.config.__retried) {
      response.config.__retried = true
      const store = useUserStore()
      try {
        store.setToken(await refreshAccessToken(store.refreshToken))
        return service(response.config)
      } catch {
        ElMessage.error('登录已过期，请重新登录')
        await toLogin()
        return Promise.reject(new Error(body.message))
      }
    }

    ElMessage.error(body.message)
    return Promise.reject(new Error(body.message))
  },
  (error) => {
    // HTTP 层出错（后端未启动、网关超时等）。这一层没有面向用户的消息，自己拼一句
    const message = error.response
      ? `请求失败（HTTP ${error.response.status}）`
      : '网络异常，请检查后端服务是否已启动'
    ElMessage.error(message)
    return Promise.reject(error)
  }
)

export default service
