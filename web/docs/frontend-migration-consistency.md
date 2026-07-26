# SecretPad 新前端迁移一致性说明

> 本文档说明如何保证 `secretpad/frontend-src`（旧前端）的功能与接口在 `secretpad/web`（新前端）上保持一致，并持续可验证。

## 1. 迁移原则

- **接口真实化**：新前端所有 CRUD、Job、权限、平台类型判断均调用后端真实接口，不保留 mock 数据。
- **接口契约对齐**：以 `secretpad/web/openapi/secretpad.openapi.json` 为唯一源，通过 `openapi-typescript` + `openapi-fetch` 生成类型与请求客户端。
- **功能逐页映射**：旧前端每个一级页面（Dashboard / Projects / Nodes / DataSources / DataTables / DAG / ...）的字段、按钮、权限在新前端都有对应实现。
- **双语支持**：所有用户可见文案通过 `shared/lib/i18n` 字典统一维护，默认跟随浏览器，支持中英切换。
- **权限模型复刻**：复用旧前端的 `Platform` / `PadMode` / `useHasAccess` / `AccessGuard` / `RouteGuard` 模型。

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
| DAG 图列表 / 创建 / 删除 / 运行 | DAG Editor | `pages/dag` | `graph/*`, `component/list` | ✅ |

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

后续随功能迭代继续补充 Projects / DataSources / DataTables 的创建-删除闭环 E2E。

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
5. 是否在 `App.tsx` 路由分发中通过 `RouteGuard` 校验平台类型？
6. 是否补充单元测试 / Playwright E2E？

## 6. 已知限制与后续阶段

- 所有一级页面已接入真实接口，DAG 画布目前支持按项目加载图、展示节点与运行；复杂算子配置、节点拖拽保存、运行时日志需后续逐步增强。
- 节点 Token 弹窗目前仅展示，未提供复制按钮；后续可基于 `navigator.clipboard` 增强。
- 数据表导入当前使用简单的 `name:type` Schema 输入，后续可接入数据源自动拉取元数据。
- 模型部署、周期任务创建等流程需配合 DAG 训练/打包结果，当前以提示引导用户先完成 DAG 运行。
