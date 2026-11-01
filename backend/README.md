# HookFlow 后端开发与交接

核查日期：2026-11-01（Asia/Shanghai）。本页仅为 P10-03 开发/演示交接，不是生产部署方案，也不表示 P10-04 整体验收通过。

## 入口与依据

- 实际 Git 仓库为 `HookFlow/`，后端为其下 `backend/`；本页命令默认从项目根目录（`HookFlow/` 的父目录）执行。根目录本身不是 Git 仓库。
- Java 21、Spring Boot **4.1.2**，版本以 [pom.xml](pom.xml) 为准；本轮实际 Maven 3.10.0、Temurin Java 21.0.13。Maven Wrapper 首次使用可能下载 Maven，不等于离线可用。
- 模块化单体：auth、endpoint、event、delivery、simulator、audit、overview。MySQL **8.4** 是事件、轮次、尝试和幂等状态的事实来源；Redis 仅为可选 Pub/Sub 唤醒，不是唯一队列。
- REST 规范复用根目录 [P0-02](../../doc/后端%20REST%20接口契约-P0-02.md)，状态规则见 [P0-01](../../doc/后端接口与状态契约-P0-01.md)，锁/事务见 [P0-03](../../doc/后端并发与事务约定-P0-03.md)。相对本 README 到项目根 doc 需经过两级目录。
- 当前实现补充、字段与前端注意事项见 [P10-03 接口与运行交接](doc/P10-03-接口与运行交接.md)。P0 文档中的“尚未实现”是当时阶段说明，不是当前功能清单；不因此改写已确认规则。

## 本地条件

需要 Java 21、Maven、MySQL 8.4；测试及下述一次性空库入口需要 Docker 守护进程和 `mysql:8.4.13` 镜像。Redis 默认关闭；启用时还需可访问的 Redis（含 ACL 时注入用户名/密码）。curl 和 Python 3 标准库足够执行交接操作，不要求宿主安装 jq 或 mysql CLI。MySQL CLI 可使用专用数据库容器内的版本。

```bash
mvn -version
```

```bash
docker version --format '{{.Server.Version}}'
```

离线 Maven `-o` 只允许使用已经缓存的依赖、父 POM 和插件；Docker 镜像也必须已缓存。缺失则记录环境阻塞，在具备网络授权的环境准备所需依赖，不跳过真实数据库测试冒充通过。

## 配置清单与秘密管理

[.env.example](.env.example) 是**清单，不会被 Spring 自动读取**。正式 [application.yaml](src/main/resources/application.yaml) 使用环境变量占位符。通过当前进程环境或可信外部注入提供；不要将秘密填回仓库、命令历史、URL、浏览器持久存储或日志，不使用 `set -x`、`env` 全量打印或 HTTP wire 日志。

| 环境变量 | 必填 / 默认 | 约束与作用 |
| --- | --- | --- |
| `DB_URL` | 必填 | 指向专用 MySQL JDBC 库；建议显式 `connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true` |
| `DB_USERNAME`、`DB_PASSWORD` | 必填 | 即使已有账户/库也必须提供，不能为空 |
| `HOOKFLOW_ADMIN_USERNAME` | 必填 | 首次管理员，1～64 Unicode 码点、非全空白 |
| `HOOKFLOW_ADMIN_PASSWORD` | 必填 | 首次密码，1～1024 Unicode 码点、非全空白 |
| `HOOKFLOW_SECRET_ENCRYPTION_KEY` | 必填 | **32 个随机字节的规范标准 Base64（带标准填充）**，不是接收端签名密钥、不是 URL-safe Base64 |
| `HOOKFLOW_REDIS_ENABLED` | `false` | 仅加速提交后的扫描 |
| `REDIS_HOST`、`REDIS_PORT` | `localhost`、`6379` | 仅 Redis 启用时需要服务可达 |
| `REDIS_USERNAME`、`REDIS_PASSWORD` | 空 | Redis ACL / 认证；不在示例中填写实际值 |
| `HOOKFLOW_MAX_ATTEMPTS` | `3` | 整数 1～3，含首次，创建轮次时快照 |
| `HOOKFLOW_RETRY_DELAYS` | `10s,30s` | 必须恰好两档正数 Spring Duration，创建轮次时快照 |

另可用 Spring 命令行参数 `--server.address=127.0.0.1 --server.port=8080`，默认 Spring 端口 8080。不要把测试资源中的 `server.address` 当成正式运行设置。清理器的两个高级 Spring 属性为 `hookflow.retention.initial-delay-ms`、`hookflow.retention.fixed-delay-ms`，均默认 60000 毫秒；本交接无需修改，不新增业务环境变量或清理开关。

`RequiredEnvironmentValidator` 在正式启动前检查六个必填变量、主密钥和重试策略。已有管理员时初始化器不重置账户/密码；修改初始环境变量不是密码重置入口。主密钥用于 AES-GCM 解开接收端及历史任务快照，**同库重启必须沿用原值**。丢失或换值会使旧密文无法解密，不能靠重建账户、改 URL 或回退明文恢复；当前没有正式主密钥轮换/找回接口。

### 已有专用开发库的安全注入和启动

以下 Python 片段在当前终端用隐藏输入读取秘密，仅传给 Maven 子进程；不写 `.env`，不打印秘密。DB_URL 不得内嵌密码。应事先从可信渠道取得稳定主密钥；**不能在每次重启重新生成它**。只使用虚构数据，不连接用户持久业务库做验证。

```bash
python3 -I - <<'PY'
import getpass, os, subprocess
values = dict(os.environ)
for name in ('DB_URL', 'DB_USERNAME', 'DB_PASSWORD', 'HOOKFLOW_ADMIN_USERNAME', 'HOOKFLOW_ADMIN_PASSWORD', 'HOOKFLOW_SECRET_ENCRYPTION_KEY'):
    if not values.get(name):
        values[name] = getpass.getpass(name + '（隐藏输入）: ')
values['HOOKFLOW_REDIS_ENABLED'] = 'false'
raise SystemExit(subprocess.call(['mvn', '-o', '-f', 'HookFlow/backend/pom.xml', 'spring-boot:run', '-Dspring-boot.run.arguments=--server.address=127.0.0.1 --server.port=8080'], env=values))
PY
```

首次正式启动由 Flyway 自动执行 [V1](src/main/resources/db/migration/V1__create_initial_schema.sql)，创建 11 张业务表及迁移历史；不需要手工建表或改 V1。已有 schema 不得通过清库或 `flyway clean` 修复。`skeleton` 排除 DataSource/Flyway/正式业务组件；`p2-login-test` 不启用正式周期调度，均不能用于首次投递验收。正式运行不设置这两个 profile。

先检查端口占用，不能停止其他工具来腾端口；必要时另选端口，并同步 API base。macOS 同号不同地址族监听可使 IPv4 请求误投另一服务，须显式绑定回环 IPv4。

```bash
lsof -nP -iTCP:8080 -sTCP:LISTEN
```

```bash
curl --noproxy '*' --connect-timeout 2 --max-time 5 -i http://127.0.0.1:8080/actuator/health
```

要求 200、最小 `{"status":"UP"}` 和非空 `X-Request-Id`。空 404 且无请求 ID 应先检查监听地址/进程，不自动重试掩盖误投。

## 从空库到首次真实投递

P10-03 的可检验链路是：空库 → Flyway → 首次管理员 → 正式 HTTP 登录 200 且 token 非空 → 管理员轮换业务 Key → 创建不含 `url` 的 SIMULATOR → 业务提交 202 → 正常周期扫描 → 固定模拟器真实 HTTP → SUCCESS → API 与数据库证据一致。

本任务的临时环境入口及本轮证据记录见 [P10-03 运行核验](doc/P10-03-运行核验.md)。如该记录标为未执行/阻塞，不能只因本 README 已写好就认定本项完成。该链路不包含 P10-04 的八场景全复演、独立进程故障、无 Redis 完整验收或耗时目标演示。

### API 操作顺序（适用于已启动的专用环境）

所有秘密响应只在操作程序内存接收，不将 token/apiKey/signingSecret 打到终端。以下是契约示意，`<...>` 不是真实值，不能原样认证。

1. `POST /api/v1/auth/login`，JSON `{username,password}`，检查 200、非空 `token` 和 UTC `expiresAt`。管理员 Token 有效 15 分钟；401 时显式重新登录，不能自动重发写请求。
2. 管理头 `Authorization: Bearer <adminToken>` 调 `POST /api/v1/admin/api-key/rotate`，**不传请求体**，200 的 `apiKey` 只交付一次，旧 Key 立即撤销。共享环境不可随意轮换；本步骤仅授权临时专用库。
3. 管理员 `PUT /api/v1/admin/simulator/config`：`{"mode":"ALWAYS_200","failFirstN":0,"delayMs":0}`；创建端点 `POST /api/v1/admin/endpoints`：`{"name":"首次投递","targetType":"SIMULATOR","timeoutMs":5000}`，201，保存内部 `id`。**连 `url:null` 都不能传。**
4. 业务请求只用 `X-HookFlow-Api-Key: <apiKey>`，不要混发管理员头：

```http
POST /api/v1/events
X-HookFlow-Api-Key: <仅内存中的业务Key>
Content-Type: application/json

{"eventId":"fresh-unique-demo-id","eventType":"order.paid","endpointId":12,"payload":{"fictional":true,"amount":1}}
```

新事件返回 202、`duplicate:false`；**202 只表示事件与首轮任务已原子提交，不保证返回时已经开始或完成投递**。`endpointId:12` 仅是示意，使用实际创建 ID。重复演示生成新的 eventId 和端点，不删历史重置计数。

5. 也可用管理员头调 `POST /api/v1/admin/events/test`，同字段但**省略 eventId**，平台生成 UUID；此操作仍走同一落库/投递链。
6. 有截止地轮询 `GET /api/v1/admin/events/{eventId}` 至 SUCCESS；保存 `rounds[0].taskId`，再 GET `/api/v1/admin/deliveries/{taskId}`。核对第 1 轮、一次 SUCCESS/200 尝试、结束时间/耗时；NULL 不补 0。
7. 分页 GET `/api/v1/admin/simulator/requests`，按本次 eventId 找唯一有效记录（无 eventId 服务端筛选）；核对 `signatureValid:true`、`plannedStatus:200`、`duplicateProcessed:false`。SQL核对 state.received_count=1、processed_at 非空。
8. 分页 GET `/api/v1/admin/audit-logs`，按**原管理请求**响应头 ID 找 `API_KEY_ROTATED`、`ENDPOINT_CREATED`；配置确有变更才有配置审计。业务提交/测试事件/成功查询不新增成功管理审计。退出用 `POST /api/v1/auth/logout`，204；原 token 随即失效。

P10-03已在专用临时环境实际完成空库首次投递，见 [运行核验记录](doc/P10-03-运行核验.md) 末尾。用户手动执行，agent读取本地证据JSON确认PASS及资源回收true，不等于完成P10-04。

可选 [p1002_demo.py](doc/p1002_demo.py) 不作为默认入口：原P10-02仅人工核查，本次用户反馈语法/七项检查执行无报错，但没有助手现场跑完八场景或独立终端报告。不要打印Demo对象、凭据属性或直接显示`api()`的秘密响应。

## 测试与演示

默认测试：

```bash
mvn -f HookFlow/backend/pom.xml test
```

缓存完整时离线执行：

```bash
mvn -o -f HookFlow/backend/pom.xml test
```

Surefire 使用父 POM 的默认选择：`Test*`、`*Test`、`*Tests`、`*TestCase`；没有自定义并行、fork、失败重试或跳过。P10-01实际为 Surefire 3.5.6，默认单 fork / reuseForks，JUnit 默认串行。`SingleDeliveryTcpConnectTimeoutScenario` 不匹配默认命名，属于独立 Linux 网络隔离专项，旧 XML 不计默认套件结果。不要只汇总目录所有历史报告或用旧测试数签新验收；按本次执行类、XML mtime 和 Maven 汇总核对，不清理旧报告。

- 组件/MockMvc 测试不证明真实数据库或真实 HTTP。
- Testcontainers MySQL 8.4.13 + Flyway 测试证明事务、锁竞争及 schema；Docker 不可用时未运行就是阻塞，不靠 `skipTests` 或排除数据库验收。
- 正式应用随机端口 HTTP 测试经过 Security/Controller；部分本机套接字和 TestHttpReceiver 是受控接收器，不等于公网 HTTPS。
- 应用上下文重启不等于独立 OS 进程崩溃；连接拒绝/读取超时不等于纯 TCP 握手超时。
- [P10-01矩阵](doc/P10-01-整体测试矩阵.md) 定位规则/测试/证据层级；[P10-02手册](doc/P10-02-八场景演示验收手册.md) 是八场景操作说明，不是已现场全部通过记录。模拟器配置全局共享，串行演示；不要把历史 413 或 46 个结果写成本轮结果。

## 运行限制与安全边界

- **至少一次**投递，不保证外部恰好一次；外部接收端必须自行按 **eventId 幂等**。超时/断连/执行器中断记 UNKNOWN，可能已处理；FINAL_FAILED 表示未确认成功，不等于未送达。2xx 也不代表接收端业务一定处理完毕。
- 每轮含首次最多 3 次，默认失败后 10/30 秒，不读 Retry-After；408/429/5xx 和 UNKNOWN 可重试，3xx/其他4xx不重试。禁代理、重定向和客户端暗中重试；每次显式尝试先落 STARTED。
- EXTERNAL 只允许公开 HTTPS；当前输入策略还拒绝所有 IP 字面地址、URL userinfo、查询串、fragment、内部后缀。保存时校验不替代连接时全部实际 IPv4/IPv6 地址校验。SIMULATOR 仅固定 `/internal/hookflow/simulator/receive`，不从用户 URL 放行回环。
- 首次提交/人工新轮各取当时配置和加密密钥快照；编辑不污染旧任务。停用暂停未发任务，在途可结束；恢复沿原快照和原 due。仅最新轮 FINAL_FAILED 且端点启用可人工新轮，旧历史保留。
- 4 工作线程 + 16 队列，领取前共20许可；扫描每次最多16候选，完成后约1秒再扫。租约=领取时间+任务超时+60秒；恢复在启动时及约5秒周期检查真正过期记录，不因重启强制过期。claim token/租约仅保护数据库迟到写，不能保证外部无重复。
- 最新轮终态满30天且无活跃历史才清理整事件，人工新轮重新计时；暂停/待重试/在途完整历史保护。每次至多16事件及各100条到期模拟器/审计日志，单事件历史整体原子删除；端点不硬删。模拟器幂等 state 随合格事件删除，不随旧请求日志提前丢弃。不承诺长期审计。
- 仅演示虚构数据；5秒开始投递/1分钟识别恢复是待现场测量的演示目标，不是生产 SLA。

## 交接文件与 Git

`backend/README.md` 不在 doc 忽略规则内；`backend/doc/` 全部被实际仓库既有 `**/doc/` 忽略。P10-01/02/03手册、助手和进度即使存在也不会出现在普通 status 中；`HELP.md` 也被 backend 的既有规则忽略。本任务不改忽略规则、不强制加入、不提交。交接时须同时保留这些本地文档；实际完成和未验证边界见 [技术实施进度](doc/技术实施进度.md) 的最新追加。
