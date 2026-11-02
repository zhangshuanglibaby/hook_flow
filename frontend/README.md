# HookFlow 前端

React + TypeScript + Vite 管理工作台。开发根目录为 `HookFlow/frontend/`。沿用已认可的冷蓝主题与登录背景，业务请求直连真实后端，不包含模拟接口或静态业务结果回退。开发和生产构建均预填公开演示账号，不绕过后端认证。

## 环境与启动

Node 要求 >=22.12；本次读到本机 Node v24.16.0。使用 npm，项目依赖不安装到全局。

以下命令在 frontend 目录运行：

```bash
npm install
```

```bash
npm run dev
```

默认浏览器地址 `http://127.0.0.1:5173`。Vite 通过同源代理转发 `/api/v1` 与 `/actuator/health` 到 `http://127.0.0.1:8080`，后端须另行安全启动。不要运行会自动销毁数据库的后端验收助手作为日常开发服务，不需要把数据库密码或主密钥给浏览器。

### dev / pro

- `.env.development`：`npm run dev`，`VITE_APP_ENV=dev`。
- `.env.production`：`npm run build`，`VITE_APP_ENV=pro`。
- `.env.development.local`：本地覆盖，例如 `API_PROXY_TARGET=http://127.0.0.1:8081`，已忽略 Git。
- `VITE_API_BASE_URL` 只支持同源绝对路径，默认 `/api/v1`。
- `API_PROXY_TARGET` 仅供 Vite 服务端使用，不暴露到客户端。
- 所有 `VITE_*` 是公开构建配置，禁止加入密码、Token、业务 Key 或验签/加密密钥。

开发和生产构建均默认填写公开演示账号 `admin123 / 123456`，不自动提交登录。默认值只作用于前端表单，不创建或重置后端账号；必须通过真实登录接口认证。凭证会进入前端产物，任何访客均可获取，仅适用于隔离的虚构数据演示环境，不得接入真实业务或保存真实秘密。

登录后缓存白名单字段 `{token,expiresAt,username}` 到当前标签页的 `sessionStorage`，刷新时恢复未过期会话。不保存密码、JSON业务内容或验签密钥，不使用长期 `localStorage`。后端到期时间不延长，不增加自动登录/刷新 Token 接口；缓存过期、损坏、退出或收到认证失效响应时清理会话。缓存不是服务端认证依据，Token撤销仍由后端判断。关闭标签页通常结束该缓存，浏览器会话恢复行为依浏览器设置；存储被禁用时退回内存会话。

安全边界：sessionStorage 同样可被同源 JavaScript 读取，不能抵抗 XSS。仅用于演示开发便利；正式部署优先考虑后端提供 HttpOnly Cookie 会话。

## 质量命令

```bash
npm run typecheck
```

```bash
npm run lint
```

```bash
npm run test:unit
```

```bash
npm run build
```

```bash
npm run test:e2e
```

```bash
npm run test:integration
```

浏览器测试连接先行启动的 Vite，不自行启动隐藏服务器。无接口替身；普通 e2e 覆盖登录页与守卫，integration 使用实际后端。真实集成需安全注入 `HOOKFLOW_TEST_USERNAME`、`HOOKFLOW_TEST_PASSWORD`，未提供时明确 skip；不启用 trace/video/screenshot 以避免凭据和一次性密钥进入产物。不要把凭据写进命令历史。

**本次执行限制**：新增依赖的安装调用被工具安全检查服务暂时不可用阻断，现有 lockfile 尚未同步新增依赖，因此当前不能宣称 `npm ci` 可复现。先成功执行一次 `npm install` 更新 lockfile，再以 `npm ci` 验证。类型/lint/构建/浏览器检查状态见 `docs/verification.md`，未执行不算通过。

## 分层与性能

- `app/`：路由懒加载、主题、错误边界。
- `features/`：认证、事件、接收端、模拟器、概览、审计。
- `shared/api/`：请求、错误、DTO/Zod 验证、无损 JSON、内存会话与标签页缓存。
- `shared/query.ts`：TanStack Query 配置、会话隔离与写操作失效规则。
- `shared/EndpointSelect.tsx`：分页选择器，20 条/页，无伪服务端搜索。

路由按需加载；服务端分页，不做 N+1 详情补列、不从当前页统计全量。GET 短暂网络/5xx 最多重试一次，写操作不自动重发。查询显式取消且按会话 generation 隔离；会话结束取消请求、清空查询与秘密视图。无常驻轮询，无页面失焦自动刷新。

原静态展示数据文件仅被历史纯函数测试引用，不由业务页面或生产入口导入。状态评审入口不注册。

## 关键业务边界

- `202` 仅表示落库受理，实际投递结果在详情刷新查看。
- 事件列表使用实际承诺的摘要，次数/最近开始时间只在详情获取，不跨列表拼接。
- 最新轮为 `FINAL_FAILED` 且当前端点启用，才允许确认重投；新轮及旧历史取后端结果。
- `UNKNOWN` 说明接收端可能已处理，不能显示为确定未送达。
- 详情接口未公开历史 URL/超时/退避快照，界面明确注明缺失；当前端点配置不冒充历史。
- 日期输入为本地日历，接口/URL UTC 带 Z，范围 `[from,to)`。概览默认最近 7 个本地日历日，列表默认无时间范围。
- payload 支持 null/数组/标量/高精度数值，保留原始数字词法；完整 UTF-8 请求 <=256KiB。
- 数字内部 ID 必须是 JS 安全整数；超界拒绝，不舍入后访问错误对象。完整 Java long 支持需契约迁移。
- 特殊事件 ID 使用路径编码；当前后端对部分保留字符会在安全链拒绝。前端对此禁发详情/重投请求，并提示精确列表查询，不把此类拒绝当会话过期。
- EXTERNAL 创建201的一次性 `signingSecret` 不进 Query/mutation 缓存，只进入即时交付弹窗。复制须主动点击，关闭须确认已保存；退出/卸载清除引用。
- 模拟器配置全局共享，未保存修改时禁止发测试事件。接收记录来自后端，计划响应不覆盖发送端尝试结果。
- 审计仅显示内部用户/对象 ID，不伪造名称或普通测试事件审计。

## 生产构建参考

`deploy/nginx.conf` 为同源静态服务示例：资产长缓存、HTML 不长期缓存、前端路由 fallback，API 错误不 fallback 到 HTML。`Dockerfile` 仅提供独立构建参考，不改动现有后端或 Compose；需要确认网络中的后端服务名并设置 HTTPS。该部署配置本次未实际构建运行，不属于已通过验收。

F0 契约与设计决定见 `docs/implementation.md`。分阶段交付及未覆盖项见 `docs/verification.md`。
