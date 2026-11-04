import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fileViewerRenderers } from '@file-viewer/vite-plugin'
import path from 'path'

// https://vitejs.dev/config/
export default defineConfig({
  base: process.env.VITE_DESKTOP === 'true' ? './' : '/',
  plugins: [vue(), fileViewerRenderers({ copyAssets: true })],
  resolve: {
    alias: {
      '@': path.resolve(__dirname, 'src')
    }
  },
  server: {
    port: 3000,
    cors: true,
    // 本地开发时 /eval/ 与 /data/ 代理到两个独立中台服务（生产由 nginx 同域前缀路由）
    proxy: {
      '/eval': 'http://127.0.0.1:8140',
      '/data': 'http://127.0.0.1:8141'
    }
  }
})
