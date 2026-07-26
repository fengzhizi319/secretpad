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
  build: {
    // 主包拆分：将体积较大的第三方依赖抽离为独立 chunk，
    // 利用浏览器长期缓存并消除 >500kB 告警。
    rollupOptions: {
      output: {
        manualChunks: (id) => {
          if (!id.includes('node_modules')) return undefined;
          // react 与 @tanstack 存在双向模块引用，合并为同一 framework chunk 以避免循环 chunk 告警。
          if (
            id.includes('react-dom') ||
            id.includes('/react/') ||
            id.includes('scheduler') ||
            id.includes('@tanstack')
          ) {
            return 'framework';
          }
          if (id.includes('/zod/')) {
            return 'zod-vendor';
          }
          // 其余依赖（zustand 等）与 react 存在双向引用，一并归入 framework 避免循环 chunk。
          return 'framework';
        },
      },
    },
  },
});
