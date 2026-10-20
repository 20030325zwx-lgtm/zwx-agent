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
    cors: true
  }
})
