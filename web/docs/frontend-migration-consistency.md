# SecretPad 新前端迁移一致性说明

> 本文档说明如何保证 `secretpad/frontend-src`（旧前端）的功能与接口在 `secretpad/web`（新前端）上保持一致，并持续可验证。

## 1. 迁移原则

- **接口真实化**：新前端所有 CRUD、Job、权限、平台类型判断均调用后端真实接口，不保留 mock 数据。
- **接口契约对齐**：以 `secretpad/web/openapi/secretpad.openapi.json` 为唯一源，通过 `openapi-typescript` + `openapi-fetch` 生成类型与请求客户端。
- **功能逐页映射**：旧前端每个一级页面（Dashboard / Projects / Nodes / DataSources / DataTables / DAG / ...）的字段、按钮、权限在新前端都有对应实现。
- **双语支持**：所有用户可见文案通过 `shared/lib/i18n` 字典统一维护，默认跟随浏览器，支持中英切换。
- **权限模型复刻**：复用旧前端的 `Platform` / `PadMode` / `useHasAccess` / `AccessGuard` / `RouteGuard` 模型。
- **现代架构**：路由采用 TanStack Router（代码式路由 + `beforeLoad` 守卫），服务端状态采用 TanStack Query v5（`useQuery` / `useMutation` + 缓存失效，DAG 轮询用 `refetchInterval`）；API 响应统一经 Zod `safeParse` 运行时校验（`validated`/`unwrapValidated`），契约不符时抛出带字段路径的错误。
- **通知统一**：所有 `alert`/`window.confirm` 已替换为 design-system 的 `Toast` 与 `ConfirmDialog`。

## 2. 接口一致性检查清单

| 能力 | 旧前端页面/模块 | 新前端页面 | 真实接口 | 状态 |
|---|---|---|---|---|
| 登录 / 登出 | Login | `pages/login` | `POST /api/login` / `/api/logout` | ✅ |
| Dashboard 统计与 Recent Jobs | Dashboard | `pages/dashboard` | `node/list`, `project/list`, `project/job/list` | ✅ |
| 项目列表 / 创建 | Projects | `pages/projects` | `project/list`, `project/create` | ✅ |
| 节点注册 / 编辑 / 删除 / 刷新 / Token | Nodes | `pages/nodes` | `node/*` | ✅ |
| 数据源添加 / 删除 | DataSources | `pages/data-sources` | `datasource/*` | ✅ |
| 数据表导入 / 删除 / Schema | DataTables | `pages/data-tables` | `datatable/*` | ✅ |
| 平台类型路由守卫 | route guards | `features/auth/ui/access-guard.tsx` | auth-store 中 platformType | ✅ |
| 按钮级权限 | AccessControl | `features/auth/ui/access-guard.tsx` | `useHasAccess` + `AccessGuard` | ✅ |
| 中英双语切换 | Locale Switch | `widgets/AppHeader` + `shared/lib/i18n` | 本地字典 | ✅ |
| 模型产物列表 / 删除 / 详情 | Model Manager | `pages/models` | `model/page`, `model/delete`, `model/info`, `model/serving/*` | ✅ |
| 消息与审批列表 | Message Center | `pages/messages` | `message/list`, `message/pending` | ✅ |
| 周期任务列表 / 下线 / 删除 | Periodic Tasks | `pages/periodic-tasks` | `scheduled/page`, `scheduled/offline`, `scheduled/del` | ✅ |
| DAG 图列表 / 创建 / 删除 / 运行 | DAG Editor | `pages/dag` | `graph/*`, `component/list`, `component/batch`, `component/i18n` | ✅ |
| DAG 节点拖拽保存 / 算子配置 / 连线 | DAG Editor | `packages/dag-next` | `graph/update`, `graph/node/update` | ✅ |
| DAG 运行时日志 / 节点输出 | DAG Editor | `packages/dag-next` | `graph/node/logs`, `graph/node/output` | ✅ |
| DAG 节点状态轮询 | DAG Editor | `pages/dag` | `graph/node/status` | ✅ |
| 项目详情 / 编辑 / 删除 / 参与方 | Projects | `pages/projects` | `project/get`, `project/update`, `project/delete`, `project/node/add`, `project/datatable/*` | ✅ |
| 项目内任务详情 / 停止 | Projects | `pages/projects` | `project/job/get`, `project/job/stop` | ✅ |
| DAG 停止运行 / 重命名 | DAG Editor | `pages/dag` | `graph/stop`, `graph/meta/update` | ✅ |
| 消息详情 / 审批回复 | Message Center | `pages/messages` | `message/detail`, `message/reply` | ✅ |
| 模型打包 / 废弃 / 状态 / 详情 / 部署 | Model Manager | `pages/models` | `model/pack`, `model/discard`, `model/status`, `model/detail`, `model/modelPartyPath`, `model/serving/create` | ✅ |
| 周期任务创建 / 重跑 / 停止 | Periodic Tasks | `pages/periodic-tasks` | `scheduled/graph/create`, `scheduled/info`, `scheduled/task/rerun`, `scheduled/task/stop` | ✅ |
| 节点 Token 复制 / 重置 | Nodes | `pages/nodes` | `node/token`, `node/newToken` | ✅ |
| 数据表详情 / 推送 TEE | DataTables | `pages/data-tables` | `datatable/get`, `datatable/pushToTee` | ✅ |
| 数据上传 / 下载 / 同步 | DataTables | `packages/api-client` | `data/create`, `data/download`, `data/upload`, `data/sync` | ✅ |
| 节点路由列表 / 编辑 / 删除 / 刷新 | Node Route | `pages/node-routes` | `nodeRoute/page`, `nodeRoute/get`, `nodeRoute/update`, `nodeRoute/delete`, `nodeRoute/refresh` | ✅ |
| 机构信息 / 机构节点管理 | Institution | `pages/institutions` | `inst/get`, `inst/node/list`, `inst/node/add`, `inst/node/delete`, `inst/node/token`, `inst/node/register` | ✅ |
| P2P 节点注册 / 删除 | P2P My Node | `pages/p2p/my-node` | `p2p/node/create`, `p2p/node/delete` | ✅ |
| P2P 项目列表 / 创建 / 编辑 / 归档 / 参与方 | P2P Projects | `pages/p2p/projects` | `p2p/project/list`, `p2p/project/create`, `p2p/project/update`, `p2p/project/archive`, `p2p/project/participants` | ✅ |
| 用户信息 / 修改密码 | Account | `pages/account` | `user/get`, `user/updatePwd` | ✅ |
| 数据源详情 / 节点 | DataSources | `pages/data-sources/detail` | `datasource/detail`, `datasource/nodes` | ✅ |
| 审批创建 / 状态轮询 | Approval | `packages/api-client` | `approval/create`, `approval/status` | ✅ |

## 3. 自动化验证

### 3.1 类型检查

```bash
cd /home/charles/code/sfwork/secretpad/web
corepack pnpm typecheck
```

覆盖所有 `@secretpad/*` workspace 包，确保 OpenAPI 类型、业务类型、TSX 组件类型一致。

### 3.2 单元测试

```bash
cd /home/charles/code/sfwork/secretpad/web
corepack pnpm test
```

包含：
- `packages/utils` 工具函数
- `packages/api-client` 接口映射
- `apps/secretpad` 页面渲染与交互

### 3.3 E2E 关键路径

```bash
cd /home/charles/code/sfwork/secretpad/web/apps/secretpad

# 1. 确保后端在 8080 运行，且 Vite dev server 在 8000 运行
# 2. 运行 Playwright E2E
corepack pnpm exec playwright test
```

当前 E2E 覆盖：
- 登录成功并进入 Dashboard
- 侧边栏导航至 Nodes 页面
- 侧边栏导航至 Messages 页面
- 侧边栏导航至 DAG 页面（Workspace 或空态渲染）

后续随功能迭代继续补充 Projects / DataSources / DataTables 的创建-删除闭环 E2E，以及 DAG 节点拖拽、保存、运行的端到端覆盖。

## 4. 开发-生产构建衔接

采用「同仓并列隔离（Monorepo）」方案：

- 开发期：`secretpad/web/apps/secretpad` 通过 Vite proxy (`/api → 127.0.0.1:8080`) 直连本地后端。
- 生产期：`secretpad/web/scripts/build/build.sh` 先 `pnpm build`，再将 `apps/secretpad/dist/*` 复制到 `secretpad/secretpad-web/src/main/resources/static/`，最后 `mvn package` 出 Fat Jar。

详见顶层 `secretpad/scripts/build/build.sh` 与项目 `README.md`。

## 5. 新增页面/功能时的自查项

1. 是否从真实 API 获取数据（`apiClient.*`）？
2. 是否使用 `useTranslation()` 包裹所有用户可见文案？
3. 是否在 `shared/lib/i18n/dictionaries.ts` 中补充 `zh-CN` / `en-US` 字典？
4. 管理类按钮是否用 `AccessGuard` 包裹，并指定允许的 `Platform` / `PadMode`？
5. 是否在 `router.tsx` 中通过 `createRoute` 注册路由，并由根/`app` 路由的 `beforeLoad` 与 `RouteGuard` 校验登录态与平台类型？
6. 数据获取是否使用 TanStack Query（`useQuery`/`useMutation` + `invalidateQueries`），而非手写 `useEffect`？
7. 是否补充单元测试 / Playwright E2E？

## 6. 已知限制与后续阶段

- 所有一级页面（含 P2P 项目/我的节点、机构管理、节点路由、用户中心）与 DAG 高级能力均已接入真实接口，旧前端约 50 个接口已全量迁移（见第 2 节清单）。
- DAG 画布支持节点拖拽、算子库添加、节点配置/日志/输出面板、连线、保存与运行，并提供节点状态轮询；Mock 演示节点与算子库兑底假数据已移除，接口失败时呈现错误态。
- 数据表分级（classification）改为读取后端真实字段，导入表单支持 L1–L5 选择并透传后端，不再硬编码 `'L1'`。
- 节点 Token 弹窗已支持复制（`navigator.clipboard`）与重置（`node/newToken`）。
- 模型部署/打包、周期任务创建/重跑/停止均已接入真实表单与接口（配合 DAG 训练/打包结果）。
- DAG 算子配置面板当前以通用 JSON 编辑器（`nodeDef`、`config`）呈现，后续可针对高频算子提供更友好的表单化配置。
- 数据表导入当前使用简单的 `name:type` Schema 输入，后续可接入数据源自动拉取元数据。
- P2P / inst / nodeRoute 等接口的真实后端行为依赖 Kuscia 环境，本地仅保证类型与调用正确，联调需 `scripts1/dev-start.sh` 环境。
