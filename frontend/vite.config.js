import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url))
    }
  },
  server: {
    port: 5173,
    proxy: {
      // 后端 context-path 就是 /api，所以这里不需要 rewrite。
      // 代理的意义在于让浏览器只看到 5173 这一个源：同源请求既不用处理 CORS，
      // 也不必在后端开跨域白名单——开发期和生产期的请求路径因此完全一致
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true
      }
    }
  }
})
