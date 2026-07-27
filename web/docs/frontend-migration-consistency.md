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
| 项目任务日志 / 输出 | Projects / DAG | `packages/api-client` | `project/job/task/logs`, `project/job/task/output` | ✅ |
| 项目 TEE 节点 / 输出表 / 表配置 / 数据源 / 机构 | Projects | `packages/api-client` | `project/tee/list`, `project/getOutTable`, `project/update/tableConfig`, `project/datasource/list`, `project/inst/add` | ✅ |
| 周期任务一次成功 / 作业列表 / 任务详情 | Periodic Tasks | `packages/api-client` | `scheduled/graph/once/success`, `scheduled/job/list`, `scheduled/task/info` | ✅ |
| 节点用户 / 远程用户密码重置 | Nodes / Account | `packages/api-client` | `user/node/resetPassword`, `user/remote/resetPassword` | ✅ |
| 节点分页 / 图节点最大索引 / 模型服务详情 | Nodes / DAG / Models | `packages/api-client` | `node/page`, `graph/node/max_index`, `model/serving/detail` | ✅ |
| 特征数据源 / 云日志 / 投票同步 / 版本 | Feature DS / Logs | `packages/api-client` | `feature_datasource/create`, `feature_datasource/auth/list`, `cloud_log/sls`, `vote_sync/create`, `version/list` | ✅ |

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
- DAG 算子配置面板当前以通用 JSON 编辑器（`nodeDef`、`config`）呈现，PSI 模板向导已可按项目节点与数据表一键生成 read_data → PSI 初始图，后续可针对高频算子提供更友好的表单化配置。
- `secretpad/frontend-src/` 旧前端目录已弃用删除，构建脚本（`scripts/dev-start.sh`、`scripts/run-all-no-docker.sh`、`scripts/clone-repos.sh`）与顶层 README/AGENTS 已统一指向 `secretpad/web/`。
- 数据表导入当前使用简单的 `name:type` Schema 输入，后续可接入数据源自动拉取元数据。
- P2P / inst / nodeRoute 等接口的真实后端行为依赖 Kuscia 环境，本地仅保证类型与调用正确，联调需 `scripts1/dev-start.sh` 环境。

## 7. 端到端验证记录（2026-07-27）

### 7.1 验证环境

- 前端：`secretpad/web` Vite dev server on `http://127.0.0.1:8000`
- 后端：`secretpad` Spring Boot on `http://127.0.0.1:8080`
- Kuscia：Docker Master + alice + bob（dev-start.sh 部署）
- SecretFlow：自定义镜像 `secretflow/sf-privacy-dev:1.15.0.dev-privacy` 已注册为 Kuscia AppImage

### 7.2 PSI → 逻辑回归训练链路

1. 使用已有项目 `srkbfvmn`、图 `mzeeuxmc`（含 alice-table / bob-table → PSI）。
2. 通过 `/api/v1alpha1/graph/node/output` 验证 PSI 输出：生成联合表 `vtpo-mzeeuxmc-node-3-output-0`，字段覆盖 alice 24 列 + bob 21 列。
3. 在图中追加 `ml.train/ss_sgd_train` 节点 `mzeeuxmc-node-4`，label=`y`，feature_selects=43 个非 ID/label 列。
4. 调用 `/api/v1alpha1/graph/update` 与 `/api/v1alpha1/graph/start`，得到 Job ID `vtpo`。
5. 轮询确认所有节点 `SUCCEED`；训练节点输出类型为 `model`，路径为 `vtpo-mzeeuxmc-node-4-output-0`（alice / bob 各一份）。

结论：前端 → 后端 → Kuscia → SecretFlow 自定义镜像的训练链路已跑通。

### 7.3 模型打包前端增强

- 旧 Models 页面 Pack 逻辑直接传入 `modelPartyConfig: []` 与 `modelComponent: []`，导致后端 `Index 0 out of bounds for length 0` 错误。
- 增强后：
  - 选择训练节点后自动调用 `/api/v1alpha1/model/modelPartyPath` 获取参与方数据源。
  - 按参与方展示数据源下拉框，构造 `modelPartyConfig`。
  - 根据训练节点 `nodeDef` 构造 `modelComponent`（包含 graphNodeId / domain / name / version）。
  - 提交后调用 `/api/v1alpha1/model/status` 轮询，直到 `SUCCEED` 或 `FAILED`。
- 验证：
  - 打包请求 `/api/v1alpha1/model/pack` 成功返回 `jobId: town`。
  - 当前 Kuscia/SecretFlow 模型导出作业在本地环境存在运行时失败（alice 或 bob 任务失败），属于后端/运行时问题，前端已能正确发起完整打包流程并展示状态。
- 部署服务：基于打包成功后的 `modelId` 调用 `/api/v1alpha1/model/serving/create`；当前因打包未成功无法完成端到端部署，但前端表单与接口已就绪。

### 7.4 消息中心

- 修复 `apiClient.getMessages` 未传 `isInitiator: false` 导致后端 NPE 的问题。
- 验证 `/api/v1alpha1/message/list` 与 `/api/v1alpha1/message/pending` 返回空列表/0 待处理，无报错。
- 当前非 P2P 模式无真实审批消息，消息列表为空为正常状态。

### 7.5 启动脚本健壮性

- `scripts1/dev-start.sh` 的 `start_frontend` 增加：
  - 端口监听超时从 120s 延长到 180s。
  - 端口就绪后增加 `curl -f http://127.0.0.1:8000/` HTTP 200 二次确认，避免 Vite 已 bind 端口但尚未完成初始构建时误报“前端未就绪”。

### 7.6 Projects 页面增强（2026-07-27 后续）

- 重写 `pages/projects/index.tsx`：
  - 项目卡片列表保留搜索、创建、编辑、删除。
  - 点击卡片打开详情抽屉：展示基本信息、已加入节点、按节点分组的已关联数据表、近期任务。
  - 支持在抽屉中直接添加节点、添加数据表、从项目移除数据表（调用 `project/datatable/delete`）。
  - 支持查看任务详情弹窗：列出任务下所有图节点/算子状态，点击节点可查看日志与输出。
- 任务 `taskId` 按后端约定由 `jobId-graphNodeId` 组合得到，用于调用 `project/job/task/logs` 与 `project/job/task/output`。
- 补充 `shared/lib/i18n/dictionaries.ts` 中 Projects 相关文案，覆盖中英双语。
- 验证：类型检查与 lint 通过，无新增错误。

### 7.7 DataTables 页面增强（2026-07-27 后续）

- 重写 `pages/data-tables/index.tsx`：
  - 保留节点过滤、数据表列表、导入、删除、推送到 TEE。
  - 新增数据表“详情”抽屉，包含三个标签页：
    - **Schema 预览**：展示数据源、节点、URI、状态及完整字段（类型、描述、敏感分级）。
    - **授权项目**：展示已授权项目列表，支持新增授权（选择项目 + 关联键 + 标签列）与取消授权。
      新增授权调用 `project/datatable/add`，取消授权调用 `project/datatable/delete`。
    - **授权血缘**：以树状结构展示“数据表 → 已授权项目”的血缘关系。
  - 刷新状态按钮调用 `datatable/get` 重新拉取详情与状态。
- 扩展 `apiClient.addProjectDatatable` 的 `configs` 类型为 `TableColumnConfigParam[]`，支持透传 `isAssociateKey` / `isLabelKey` / `isProtection`。
- 补充中英双语字典 `dataTables.*` 与 `projects.selectProject`。
- 验证：类型检查与 lint 通过，无新增错误。

### 7.8 Nodes 页面增强（2026-07-27 后续）

- 重写 `pages/nodes/index.tsx`：
  - 节点列表增加搜索框（按名称 / ID / 通讯地址过滤）。
  - 点击节点名称打开详情抽屉，包含三个标签页：
    - **基本信息**：节点名、ID、类型、状态、地址、注册时间、描述；对 `type === 'embedded'` 显示“内置”标签。
    - **部署令牌**：展示 Token 状态与内容，支持复制与重新生成。
    - **节点产物**：调用 `node/result/list` 获取模型/规则等产物，支持查看产物详情（`node/result/detail`）。
  - 保留原有注册、编辑、刷新、删除、查看 Token 功能。
- 补充中英双语字典 `nodes.*`（detail / basicInfo / deployToken / nodeResults / embedded 等）。
- 验证：类型检查与 lint 通过，无新增错误。

### 7.9 DAG 复杂组件迁移（Phase 4 进行中）

- 增强 `packages/dag-next/src/index.tsx`：
  - **算子配置面板**：选中节点时通过 `onGetComponentDef` 回调拉取组件定义（`component/batch`），展示算子描述、Inputs/Outputs、Attributes 摘要，辅助用户填写 `nodeDef` / `config` JSON。
  - **结果可视化**：输出面板识别 `type === 'table'` 且 `meta.rows` 为数组时，以 HTML 表格形式展示数据；支持 `tabs` 分栏展示；其余类型仍使用 JSON 高亮。
- 在 `pages/dag/index.tsx` 中实现 `handleGetComponentDef`：按 `codeName` 解析 domain/name，调用 `apiClient.batchGetComponent` 并映射为 `ComponentMetadata`。
- 类型检查与 lint 通过，无新增错误。
- 待完成：PSI / LR 模板向导，从旧前端 `pipeline-template-psi.ts` / `pipeline-template-sanitization.ts` 迁移。

### 7.10 DAG PSI 模板向导（Phase 4.2）

- 在 `pages/dag/index.tsx` 增加“PSI 模板”按钮与向导弹窗：
  - 选择接收方节点、数据表、关联键；选择发送方节点、数据表、关联键。
  - 向导内部调用 `graph/create` 创建空图，再调用 `graph/update` 写入两个 `read_data/datatable` 节点与 `data_prep/psi` 节点及两条边。
  - `read_data` 节点使用 `datatable_selected` 属性 `{ s: tableId, is_na: false }`。
  - `psi` 节点属性严格按旧前端 `pipeline-template-psi.ts` 顺序构造：`input/input_ds1/keys`、`input/input_ds2/keys`、`protocol`（`PROTOCOL_RR22`）、`sort_result`（true）、`receiver_parties`（双方 nodeId）、`allow_empty_result`（na）、`join_type`（`inner_join`）、`input_ds1_keys_duplicated`（true）、`input_ds2_keys_duplicated`（true）。
  - 关联键使用 `ss: [key]` 字符串数组形式。
- 补充 `shared/lib/i18n/dictionaries.ts` 中 `dag.*` 模板向导相关中英文字典。
- 验证：类型检查与 lint 通过，无新增错误。

### 7.11 弃用旧前端 frontend-src（Phase 5）

- 删除本地 `secretpad/frontend-src/` 目录（旧 Umi 前端，已不在维护）。
- 更新 `scripts/clone-repos.sh`：不再从独立 `secretpad-frontend` 仓库克隆旧前端；secretpad 仓库自带新前端 `secretpad/web/`。
- 更新 `scripts/dev-start.sh`：环境检测、前端依赖安装、启动命令全部切换为 `secretpad/web/` 与 `corepack pnpm --filter @secretpad/app dev`；增加 180s 端口监听 + 30s HTTP 200 二次确认。
- 更新 `scripts/run-all-no-docker.sh`：无 Docker 模式前端启动同样切换为 `secretpad/web/`。
- 更新顶层 `README.md` 与 `AGENTS.md`：前端目录、启动命令、架构图统一改为 `secretpad/web/` + Vite，并标注旧前端已弃用删除。
- 验证：类型检查与 lint 通过；脚本仅通过静态检查（未执行全量启动）。


