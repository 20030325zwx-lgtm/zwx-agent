import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// 独立部署在 /eval/ 路径下（nginx 前缀路由），静态资源 base 必须一致
export default defineConfig({
  base: '/eval/',
  plugins: [vue()],
  build: { outDir: 'dist', emptyOutDir: true },
  server: {
    port: 3100,
    proxy: { '/api': 'http://127.0.0.1:8140' }
  }
})
