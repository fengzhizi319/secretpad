import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import path from 'path';

// 开发期可通过 API_PROXY_URL 覆盖后端代理地址，例如：
// API_PROXY_URL=http://127.0.0.1:18083 pnpm dev
const apiProxyTarget = process.env.API_PROXY_URL || 'http://127.0.0.1:8080';

export default defineConfig({
  plugins: [react()],
  resolve: {
    alias: {
      '@': path.resolve(__dirname, './src'),
    },
  },
  server: {
    port: 8000,
    proxy: {
      '/api': {
        target: apiProxyTarget,
        changeOrigin: true,
      },
    },
  },
});
