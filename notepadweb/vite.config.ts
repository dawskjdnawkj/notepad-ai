import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { join } from 'node:path'
import { tmpdir } from 'node:os'

// https://vite.dev/config/
export default defineConfig({
  plugins: [vue()],
  // 避开可能由管理员权限创建、当前用户无法更新的 node_modules/.vite。
  cacheDir: join(tmpdir(), 'notepad-vite-cache'),
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
      '/uploads': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      }
    }
  }
})
