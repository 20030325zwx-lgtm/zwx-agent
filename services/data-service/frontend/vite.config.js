import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// 独立部署在 /data/ 路径下（nginx 前缀路由）
export default defineConfig({
  base: '/data/',
  plugins: [vue()],
  build: { outDir: 'dist', emptyOutDir: true },
  server: {
    port: 3101,
    proxy: { '/api': 'http://127.0.0.1:8141' }
  }
})
