import { fileURLToPath } from 'node:url';
import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig({
  root: fileURLToPath(new URL('.', import.meta.url)),
  plugins: [react()],
  appType: 'mpa',
  server: {
    host: '127.0.0.1', port: 5174, strictPort: true, open: '/snapshot.html',
    // 快照端口没有业务代理；误发API请求也不会进入联调后端。
    proxy: {},
  },
  build: {
    outDir: 'dist-snapshot',
    rollupOptions: { input: fileURLToPath(new URL('./snapshot.html', import.meta.url)) },
  },
});
