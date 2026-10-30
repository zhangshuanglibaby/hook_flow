// 引入 Vite 配置函数，让开发服务器与构建工具识别配置类型。
import { defineConfig } from 'vite';
// 引入 React 插件，使 TSX 页面支持编译和开发时热更新。
import react from '@vitejs/plugin-react';

// 导出仅用于静态页面的配置；不设置 API 代理或模拟接口。
export default defineConfig({
  // 启用 React 编译，不附加业务请求插件。
  plugins: [react()],
  // 固定开发端口，方便浏览器验收和说明文档复用同一地址。
  server: { port: 5173, strictPort: true },
});
