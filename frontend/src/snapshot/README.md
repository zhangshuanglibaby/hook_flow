# 独立事件展示快照

独立前端入口，不是后端数据初始化。固定30条虚构事件，待投递/成功/最终失败/等待重试/暂停中各6条；积分服务、库存服务、内置故障模拟器、订单服务、采购服务各关联6条。五个名称表示演示接收端，不连接真实外部服务。

与现有页面使用同一套冷蓝主题令牌，但CSS、数据和React入口独立，不导入API客户端、认证会话、工作台路由或开发库配置，不使用后端数据库。没有登录要求；没有真实创建/编辑/重投按钮。筛选、日期半开区间（北京时间）、20+10分页、只读详情可操作，刷新仍使用同一固定数据，状态不受时钟/调度影响。

## 启动

从项目根目录（HookFlow/的父目录）执行：

```bash
./HookFlow/frontend/node_modules/.bin/vite --config HookFlow/frontend/vite.snapshot.config.ts
```

打开 http://127.0.0.1:5174/snapshot.html 。桌面配置名为hookflow-snapshot。不要使用原5173地址期待看到此快照；原联调页面/后端/数据库和上一批真实事件均保持原样。

配置显式设置frontend为root、独立端口5174、MPA入口snapshot.html，无API代理。端口被占用则失败，不自动转到其他端口、不停止原服务。查看详情也只读取本地数组；不伪装成后端已投递。快照attempt是用于展示的虚构历史，非数据库执行证据。

## 检查

```bash
npm --prefix HookFlow/frontend run typecheck
```

```bash
npm --prefix HookFlow/frontend run test:unit -- --run tests/unit/snapshot.test.ts
```

五项检查覆盖30条唯一记录、五状态各6条、五名称各6条、20+10分页、筛选边界及状态/虚构历史一致性。只新增快照文件，没有更改现有EventsPage、main.tsx、vite.config.ts或工作台业务请求。

当前验证状态：文件与测试已写入，运行类型检查/测试/preview_start因工具分类服务暂不可用均未执行；不能声称类型检查、五项测试或浏览器实测已通过。当前没有成功启动快照服务器。需实际启动后确认页面、移动端横向滚动、筛选分页和详情；成功启动前5174地址不是已上线页面。
