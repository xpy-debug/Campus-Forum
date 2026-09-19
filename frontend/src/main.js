import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import 'element-plus/dist/index.css'
// Element 的深色变量。必须排在 index.css 之后、main.css 之前：
// 后者要用同名变量覆写主题色，顺序反了就被 Element 的默认值盖掉
import 'element-plus/theme-chalk/dark/css-vars.css'

import App from './App.vue'
import router from './router'
import { useUserStore } from './stores/user'
import { initTheme } from './composables/useTheme'
import './styles/main.css'

const app = createApp(App)

// 与 index.html 内的无闪烁脚本共用同一份判断：那边已经设好了 <html> 的 class，
// 这里只是把 composable 的状态对齐，避免首帧的水合不一致
initTheme()

app.use(createPinia())
app.use(router)
app.use(ElementPlus, { locale: zhCn })

// 先恢复登录态再挂载：访问令牌只在内存里，刷新页面后要用持久化的刷新令牌换回来。
// 不等这一步就挂载的话，已登录的用户会先看到登录页闪一下再跳走。
//
// 这里用 finally 而不是顶层 await：顶层 await 要求构建产物是 esnext 模块，
// 会把可运行的浏览器范围无谓地收窄到最新版本。restore 内部已经把异常都吞掉了，
// 因此它不会失败，用 finally 只是为了让「无论结果如何都要挂载」这件事写在脸上
useUserStore()
  .restore()
  .finally(() => app.mount('#app'))
