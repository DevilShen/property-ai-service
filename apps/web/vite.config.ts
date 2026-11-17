import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vitest/config'
import vue from '@vitejs/plugin-vue'

// W1：仅本地骨架，不代理真实接口；W3 接入 mock API 时在此补 server.proxy。
export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  server: {
    // 绑定 0.0.0.0：localhost / 127.0.0.1 / 局域网 IP 均可访问，方便 QA 与其他智能体联调
    host: '0.0.0.0',
    port: 5173,
    strictPort: true,
    proxy: {
      // W26 临时：Windows Hyper-V 保留了 4000 端口段（3975—4074），本地用 VITE_API_TARGET 指向备用端口
      '/api': process.env.VITE_API_TARGET ?? 'http://localhost:4000',
      '/healthz': process.env.VITE_API_TARGET ?? 'http://localhost:4000',
    },
  },
  test: {
    environment: 'node',
    include: ['src/**/*.spec.ts'],
  },
})
