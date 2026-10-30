// 引入 JavaScript 基础检查规则，发现未定义变量等常见错误。
import js from '@eslint/js';
// 引入浏览器与 Node 的全局变量清单，避免把合法环境变量当成错误。
import globals from 'globals';
// 引入 TypeScript 检查规则，用于检查 TS 与 TSX 源码。
import tseslint from 'typescript-eslint';
// 引入 Hook 规则，检查状态和副作用的调用方式。
import hooks from 'eslint-plugin-react-hooks';
// 引入热更新规则，避免组件模块导出破坏开发时更新。
import refresh from 'eslint-plugin-react-refresh';

// 合并项目需要的检查配置，不检查第三方包与生成产物。
export default tseslint.config(
  // 忽略不会手工修改的文件夹，锁文件无需添加注释。
  { ignores: ['dist/**', 'node_modules/**', '.npm-cache/**', '.playwright-browsers/**', 'playwright-report/**', 'test-results/**'] },
  // 为配置文件启用基础 JavaScript 规则。
  js.configs.recommended,
  // 为源码启用推荐 TypeScript 规则。
  ...tseslint.configs.recommended,
  {
    // 同时允许浏览器源码与 Node 配置文件使用各自环境的全局对象。
    languageOptions: { globals: { ...globals.browser, ...globals.node } },
  },
  {
    // Hook 与热更新规则只用于前端源码，不用于测试配置。
    files: ['src/**/*.{ts,tsx}'],
    // 注册两个插件，使下方规则名称可以使用。
    plugins: { 'react-hooks': hooks, 'react-refresh': refresh },
    // 在推荐 Hook 规则基础上检查组件的导出方式。
    rules: { ...hooks.configs.recommended.rules, 'react-refresh/only-export-components': ['error', { allowConstantExport: true }] },
  },
);
