import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fileViewerRenderers } from '@file-viewer/vite-plugin'
import { rmSync } from 'node:fs'
import path from 'path'

// file-viewer 插件每次 dev 启动都会把渲染器资源复制进 public/vendor（纯生成物，已 gitignore）。
// 目标目录里已有文件时会留下「 2」「 3」后缀的重复副本，长期累积后 vite build 复制 public 会失败
// （曾出现 scandir 报错、镜像体积翻倍）。开发启动前先清空，保证每次都是一份干净副本。
const cleanFileViewerDevAssets = () => ({
  name: 'clean-file-viewer-dev-assets',
  apply: 'serve',
  configureServer() {
    rmSync(path.resolve(__dirname, 'public/vendor'), { recursive: true, force: true })
  }
})

// https://vitejs.dev/config/
export default defineConfig({
  base: process.env.VITE_DESKTOP === 'true' ? './' : '/',
  plugins: [cleanFileViewerDevAssets(), vue(), fileViewerRenderers({ copyAssets: true, inject: false, chunkStrategy: 'none' })],
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
