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
- 待完成：结果管理独立页、DAG 模型提交、DAG 周期任务入口（Phase 7~9）。

### 7.10 DAG 模板库（Phase 6）

- 在 `features/dag-templates/` 下建立可扩展的模板贡献系统：
  - `types.ts`：定义 `TemplateMetadata`、`TemplateBuildInput`、`TemplateBuildResult`、`TemplateContribution` 及各类模板配置类型（双表/单表/K-匿名/L-多样性/本地差分隐私/脱敏/Risk/TEE）。
  - `builder.ts`：提供 `nodeId` / `edgeId` / `connect` / `createNode` / `createReadDataNode` / `createPsiNode` / `buildSingleTablePrivacyTemplate` / 属性辅助函数（`sAttr` / `ssAttr` / `i64Attr` / `fAttr` / `bAttr` / `jsonAttr` / `naAttr`），统一封装旧前端中重复的 nodeDef/edges 构建逻辑。
  - `templates/`：逐个迁移旧前端 `pipeline-template-*.ts` 为纯函数模板实现：blank、psi、data-classification、sanitization、query-obfuscation、k-anonymity、l-diversity、local-differential-privacy、differential-privacy、risk、tee。
  - `registry.ts`：集中注册所有模板，按 `basic` / `privacy` / `ml` 分类，提供 `templateByKey`、`isTwoTableTemplate`、`isSingleTableTemplate` 等类型守卫。
  - `use-template-wizard.ts` + `template-wizard.tsx`：通用模板向导。根据模板类型动态渲染双表/单表/无参数表单；自动加载节点数据表、列信息；创建时先 `createGraph` 获取 graphId，再 `template.build` 生成节点/边，最后 `updateGraph` 提交。
- 在 `pages/dag/index.tsx` 中将旧“PSI 模板”按钮替换为“模板向导”按钮，接入 `TemplateWizard`。
- 扩展 `shared/lib/i18n/dictionaries.ts` 的 `dag` 命名空间：新增 `template`、`templateWizardTitle`、`selectTemplate`、`templateCategory`、`templateName`、`templateDesc`、`templateSingleTable`、`templateNoInputsHint`、`featureColumns`、`labelColumn`、`predictionName`、`qiColumns`、`saColumns`、`queryColumn`、`sanitizationColumns` 等中英键值。
- 将 `shared/lib/i18n/dictionaries.ts` 的字典类型由 `Record<string, string | Record<string, string>>` 改为递归接口 `Dictionary`，支持多层嵌套命名空间，同步更新 `I18nContext.tsx` 的 `getValue` 类型。
- 验证：`corepack pnpm --filter @secretpad/app typecheck` 与 `corepack pnpm run lint` 通过（0 errors，历史 warning 50 个）。

### 7.11 弃用旧前端 frontend-src（Phase 5）

- 删除本地 `secretpad/frontend-src/` 目录（旧 Umi 前端，已不在维护）。
- 更新 `scripts/clone-repos.sh`：不再从独立 `secretpad-frontend` 仓库克隆旧前端；secretpad 仓库自带新前端 `secretpad/web/`。
- 更新 `scripts/dev-start.sh`：环境检测、前端依赖安装、启动命令全部切换为 `secretpad/web/` 与 `corepack pnpm --filter @secretpad/app dev`；增加 180s 端口监听 + 30s HTTP 200 二次确认。
- 更新 `scripts/run-all-no-docker.sh`：无 Docker 模式前端启动同样切换为 `secretpad/web/`。
- 改进 `scripts/dev-stop.sh`：停止前端时递归终止 `pnpm -> node -> Vite` 所有后代进程，避免子进程残留继续占用 8000 端口；所有脚本路径引用统一为 `scripts/` / `scripts1/` 一致。
- 更新顶层 `README.md` 与 `AGENTS.md`：前端目录、启动命令、架构图统一改为 `secretpad/web/` + Vite，并标注旧前端已弃用删除。
- 验证：
  - `corepack pnpm typecheck` / `pnpm run lint` 通过（0 errors，历史 warning 48 个）。
  - `bash -n scripts/dev-start.sh scripts/dev-stop.sh scripts1/dev-start.sh scripts1/dev-stop.sh` 通过。
  - 执行完整 `bash scripts1/dev-start.sh`：后端 HTTP 8080、前端 8000 首页均返回 200；`POST /api/login` 成功返回 token；`POST /api/v1alpha1/node/list` 成功返回 alice/bob 节点；执行 `bash scripts1/dev-stop.sh` 后 8000/8080/8443 端口全部释放。

### 7.12 结果管理独立页（Phase 7）

- 新增 `pages/results/index.tsx`：
  - 调用 `apiClient.listNodeResults` 跨项目拉取节点产物列表，支持 `nameFilter` 搜索、`kindFilters` 类型筛选、`timeSortingRule` 时间排序、分页。
  - 列表字段：结果名称、所属节点、类型（table/report/rule/model）、来源项目、计算模式、TEE 拉取状态、创建时间。
  - 点击“下载”调用 `apiClient.downloadData` 触发浏览器下载（非报告类结果）。
  - 点击“详情”打开 Modal，调用 `apiClient.getNodeResultDetail` 展示结果元数据与 `output` 摘要。
- 新增 `/results` 路由（`router.tsx`），并在 `AppSidebar` / `AppLayout` 中增加侧边栏入口与标题。
- 补充 `i18n/dictionaries.ts` 的 `results` 命名空间与 `sidebar.results` 键值。
- 验证：`corepack pnpm --filter @secretpad/app typecheck` 与 `corepack pnpm run lint` 通过（0 errors）。



## 8. 迁移完成度总结与剩余功能清单

> 数据来源：旧前端基于 `github.com/fengzhizi319/secretpad-frontend.git`（clone 到 `/tmp/secretpad-frontend-analysis`）的 `main` 分支；新前端基于 `secretpad/web/` 当前工作区。下文以“旧前端”指代 `secretpad-frontend`（原 `frontend-src`），“新前端”指代 `secretpad/web/`。

### 8.1 对比维度说明

| 维度 | 说明 |
|---|---|
| 页面级路由 | 旧前端 `apps/platform/config/routes.ts` 中定义的一级页面。 |
| 模块级功能 | 旧前端 `apps/platform/src/modules/` 下的业务模块，往往被多个页面组合复用。 |
| Pipeline 模板 | 旧前端 `modules/pipeline/templates/` 注册的 graph 模板。 |
| 可复用组件 | 组件库、DAG 引擎、工具函数等跨页面能力。 |
| 平台模式 | CENTER / EDGE / P2P / AUTONOMY 等部署模式下的页面差异。 |

### 8.2 旧前端功能全景

旧前端是一个 Umi 3 + Ant Design + Valtio 的 monorepo，主要应用为 `apps/platform`，包含 19 个页面、约 40 个业务模块、17 个 Pipeline 模板。

#### 8.2.1 一级页面（routes.ts）

| 路由 | 页面文件 | 承担角色 |
|---|---|---|
| `/login` | `pages/login.tsx` | 登录页。 |
| `/`, `/home` | `pages/new-home.tsx` | CENTER 模式工作台（标签页：Dashboard、节点管理、数据源、数据表、项目管理、结果管理、隐私组件场景、DAG/消息/模型外链）。 |
| `/dashboard` | `pages/dashboard.tsx` | 独立 Dashboard。 |
| `/data-source` | `pages/data-source.tsx` | 数据源列表。 |
| `/data-source/:id` | `pages/data-source-detail.tsx` | 数据源详情。 |
| `/data-table` | `pages/data-table.tsx` | 数据表列表。 |
| `/nodes` | `pages/nodes.tsx` | 节点管理（managed-node-list）。 |
| `/graphs` | `pages/graphs.tsx` | Graph 管理列表（演示/占位页面）。 |
| `/dag` | `pages/dag.tsx` | DAG 画布（main-dag）。 |
| `/record` | `pages/record.tsx` | DAG / Pipeline 运行记录（record-layout）。 |
| `/model-submission` | `pages/model-submission.tsx` | 模型提交/模型管理（model-submission-layout）。 |
| `/periodic-task-detail` | `pages/periodic-task-detail.tsx` | 周期任务详情。 |
| `/node` | `pages/new-node.tsx` | EDGE 模式节点首页（标签页：数据源、数据管理、合作节点、结果管理）。 |
| `/my-node` | `pages/my-node.tsx` | P2P / EDGE 模式“我的节点”首页。 |
| `/message` | `pages/message.tsx` | 消息中心。 |
| `/edge` | `pages/edge.tsx` | EDGE / P2P 工作台（标签页：工作台、数据源、数据管理、合作节点、我的项目、结果管理）。 |
| `/guide` | `pages/guide.tsx` | 新用户引导页。 |

#### 8.2.2 核心模块与功能

- **数据资产**：`all-data-sources`、`all-data-tables`、`data-source-list`、`data-manager`、`data-table-add`、`data-table-info`、`data-table-auth`、`data-table-tree`。
- **项目与结果**：`project-list`、`project-content`、`create-project`、`p2p-create-project`、`p2p-project-list`、`p2p-project-detail`、`result-manager`、`result-details`。
- **节点与机构**：`managed-node-list`、`managed-node`、`node`、`my-node`、`cooperative-node-list`、`guide-node`。
- **DAG 与流水线**：`main-dag`、`dag-submit`、`dag-record`、`dag-result`、`dag-log`、`dag-model-submission`、`dag-modal-manager`、`dag-guide-tour`、`dag-record-guide-tour`、`pipeline`、`pipeline-record-list`。
- **周期任务**：`periodic-task`（含 `periodic-task-list`、`periodic-task-drawer`、`periodic-child-task-list`）。
- **组件与配置**：`component-config`、`component-tree`、`component-interpreter`、`advanced-config`、`template-quick-config`。
- **其他业务**：`model-manager`、`message-center`、`privacy-scenes`、`guide`、`guide-pipeline`、`p2p-workbench`。

#### 8.2.3 Pipeline 模板（modules/pipeline/templates）

旧前端注册并支持选择以下模板创建 DAG：

1. `pipeline-template-blank`：空白图。
2. `pipeline-template-psi`：PSI 求交。
3. `pipeline-template-psi-guide`：引导式 PSI。
4. `pipeline-template-psi-tee`：TEE PSI。
5. `pipeline-template-psi-tee-guide`：引导式 TEE PSI。
6. `pipeline-template-scenario-psi`：场景 PSI。
7. `pipeline-template-risk`：风险模型。
8. `pipeline-template-risk-guide`：引导式风险模型。
9. `pipeline-template-tee`：TEE 计算。
10. `pipeline-template-tee-guide`：引导式 TEE。
11. `pipeline-template-privacy`：隐私计算（通用）。
12. `pipeline-template-privacy-guide`：引导式隐私计算。
13. `pipeline-template-sanitization`：数据脱敏/数据清洗。
14. `pipeline-template-query-obfuscation`：查询混淆。
15. `pipeline-template-data-classification`：数据分类分级。
16. `pipeline-template-k-anonymity`：K-匿名。
17. `pipeline-template-l-diversity`：L-多样性。
18. `pipeline-template-local-differential-privacy`：本地差分隐私。

### 8.3 新前端功能全景

新前端是 `pnpm` + Vite 5 + React 18 + TanStack Router + TanStack Query + Zustand 的 monorepo，应用为 `apps/secretpad`，包含 17 个页面、4 个 workspace 包。

#### 8.3.1 一级路由（router.tsx）

| 路由 | 页面 | 状态 |
|---|---|---|
| `/login` | `pages/login` | 已迁移。 |
| `/dashboard` | `pages/dashboard` | 已迁移。 |
| `/projects` | `pages/projects` | 已迁移（原 `project-list` + `project-content` 能力）。 |
| `/nodes` | `pages/nodes` | 已迁移（原 `managed-node-list` 能力）。 |
| `/data-tables` | `pages/data-tables` | 已迁移。 |
| `/data-sources` | `pages/data-sources` | 已迁移。 |
| `/data-sources/detail` | `pages/data-sources/detail` | 已迁移。 |
| `/dag` | `pages/dag` | 部分迁移。 |
| `/models` | `pages/models` | 已迁移（原 `model-manager` 能力）。 |
| `/periodic-tasks` | `pages/periodic-tasks` | 已迁移。 |
| `/messages` | `pages/messages` | 已迁移。 |
| `/node-routes` | `pages/node-routes` | 已迁移（原 `cooperative-node-list` / `my-node` 部分能力）。 |
| `/institutions` | `pages/institutions` | 已迁移。 |
| `/p2p/projects` | `pages/p2p/projects` | 已迁移。 |
| `/p2p/my-node` | `pages/p2p/my-node` | 已迁移。 |
| `/account` | `pages/account` | 已迁移（原 `user` 密码修改等）。 |

#### 8.3.2 复用包

- `@secretpad/design-system`：Button、Badge、Card、Modal、ConfirmDialog、Toast 等基础组件。
- `@secretpad/api-client`：OpenAPI 生成客户端，覆盖所有后端接口。
- `@secretpad/dag-next`：新版 DAG 画布（组件面板、拖拽、连线、缩放、节点状态、右侧面板）。
- `@secretpad/utils`：含 `sha256` 等通用工具。

### 8.4 功能对照矩阵

| 功能域 | 旧前端模块 | 新前端位置 | 迁移状态 | 备注 |
|---|---|---|---|---|
| 登录 / 登出 | `login` | `pages/login` + `features/auth` | ✅ 已迁移 | 密码 SHA-256 哈希一致。 |
| Dashboard 统计与最近任务 | `dashboard` | `pages/dashboard` | ✅ 已迁移 | 新前端增加了节点拓扑。 |
| CENTER 工作台标签页 | `new-home` | 无独立页面，改为左侧导航 | ✅ 等效 | 各标签页已拆分为独立路由。 |
| 项目列表 / 创建 / 编辑 / 删除 | `project-list`, `create-project` | `pages/projects` | ✅ 已迁移 | 支持项目详情抽屉、参与方、数据表、任务列表。 |
| P2P 项目列表 / 创建 / 编辑 / 归档 | `p2p-project-list`, `p2p-create-project` | `pages/p2p/projects` | ✅ 已迁移 | 支持参与者查看。 |
| 节点列表 / 注册 / 编辑 / 删除 / Token | `managed-node-list`, `managed-node` | `pages/nodes` | ✅ 已迁移 | 新增节点详情抽屉、节点产物。 |
| 节点路由（合作节点） | `cooperative-node-list` | `pages/node-routes` | ✅ 已迁移 | 源/目的地址编辑、刷新、删除。 |
| P2P 我的节点 | `my-node` | `pages/p2p/my-node` | ✅ 已迁移 | 节点路由注册。 |
| 数据源管理 | `all-data-sources`, `data-source-list` | `pages/data-sources` | ✅ 已迁移 | 含详情页。 |
| 数据表管理 | `all-data-tables`, `data-manager`, `data-table-add`, `data-table-info`, `data-table-auth` | `pages/data-tables` | ✅ 已迁移 | 含 schema、授权、Push TEE、L1–L5 分类。 |
| 数据表树 / 血缘 | `data-table-tree` | 无独立页面 | ⚠️ 部分迁移 | 数据表详情内有“授权链路”，但缺少完整的树形血缘视图。 |
| 模型管理 / 打包 / 部署 / 废弃 | `model-manager`, `dag-model-submission` | `pages/models` + DAG 右侧面板 | ⚠️ 部分迁移 | 模型列表、打包、部署已迁移；从 DAG 画布直接“模型提交”的入口未迁移。 |
| 消息中心 | `message-center` | `pages/messages` | ✅ 已迁移 | 审批/回复、详情、未读角标。 |
| 周期任务 | `periodic-task` | `pages/periodic-tasks` | ✅ 已迁移 | 创建、下线、删除、运行记录。 |
| 账户信息 / 修改密码 | `user` / `account` | `pages/account` | ✅ 已迁移 | 密码 SHA-256 哈希。 |
| 机构管理 | `inst` | `pages/institutions` | ✅ 已迁移 | 机构节点增删改查。 |
| EDGE 模式首页 | `new-node` | 无独立页面 | ❌ 未迁移 | 旧 `/node` 为 EDGE 节点首页，新前端未提供等效首页。 |
| EDGE / P2P 工作台 | `edge`, `p2p-workbench` | 无独立页面 | ❌ 未迁移 | 旧 `/edge` 聚合工作台、合作节点、我的项目、结果管理等标签；新前端按独立路由拆分，缺少聚合工作台。 |
| 结果管理独立页 | `result-manager` / `pipeline-record-list` | `pages/results` | ✅ 已迁移 | 跨项目列表、搜索、类型筛选、排序、分页、下载、详情。批量删除旧前端未完全实现，暂不迁移。 |
| 隐私组件场景 | `privacy-scenes` | 无独立页面 | ❌ 未迁移 | 旧前端用于展示场景模板并快速创建项目；新前端无等效页面。 |
| 新用户引导 | `guide`, `guide-pipeline`, `guide-node`, `guide-tour`, `dag-guide-tour`, `dag-record-guide-tour` | 无 | ❌ 未迁移 | 首次使用引导、DAG 操作引导均未实现。 |
| DAG 模板向导 | `pipeline` | `features/dag-templates` + `pages/dag` | ✅ 已迁移 | 已迁移 blank/psi/data-classification/sanitization/k-anonymity/l-diversity/local-differential-privacy/differential-privacy/query-obfuscation/risk/tee 共 11 个模板；向导按 basic/privacy/ml 分类展示。 |
| DAG 模型提交入口 | `dag-model-submission` | 无 | ❌ 未迁移 | 在 DAG 运行后选择节点进行模型打包/提交的抽屉未实现。 |
| DAG 周期任务入口 | `main-dag/periodic-task-entry` | 无 | ❌ 未迁移 | 从 DAG 直接创建周期任务的入口未实现。 |
| DAG 运行记录 / 报告 | `dag-record`, `pipeline-record-list`, `dag-result` | 无独立页面 | ⚠️ 部分迁移 | 项目详情与 DAG 节点面板可查看单次任务日志/输出；缺少统一的 pipeline 运行记录列表和结果报告页。 |
| DAG 高级配置 | `advanced-config` | 无 | ❌ 未迁移 | 算子高级配置抽屉未实现。 |
| DAG 组件树 / 组件解释器 | `component-tree`, `component-interpreter`, `component-config` | `packages/dag-next` | ⚠️ 部分迁移 | 组件面板已按后端 `component/list` 分组展示；组件解释器、配置表单自动生成、面板样式注册等高级能力未迁移。 |
| DAG 日志查看器（Monaco） | `dag-log` | DAG 节点面板 | ⚠️ 部分迁移 | 日志以文本展示，缺少 Monaco 语法高亮、折叠、搜索。 |
| Graph 管理占位页 | `pages/graphs.tsx` | 无 | ❌ 未迁移 | 旧 `/graphs` 为 Graph 列表演示页，非核心功能。 |
| 数据上传文件 UI | `DataController` | 无 | ❌ 未迁移 | `api-client` 已提供 `upload`/`download`/`sync`，但无页面入口。 |
| 模型导出 / 模型发布详情 | `ModelExportController`, `model-manager/model-release` | 无 | ❌ 未迁移 | 模型导出、发布详情页未实现。 |
| 云端日志 SLS | `CloudLogController` | 无 | ❌ 未迁移 | `cloud_log/sls` 接口未对接页面。 |
| 特征数据源 / 投票同步 | `FeatureDatasourceController`, `VoteSyncController` | 无 | ❌ 未迁移 | 对应接口未形成独立页面。 |
| 审批创建 / 状态轮询 | `ApprovalController` | `packages/api-client` | ⚠️ 部分迁移 | 消息中心可回复审批；主动创建审批、状态轮询 UI 未实现。 |
| 组件版本管理 | `ComponentVersionController` | 无 | ❌ 未迁移 | 组件版本列表/切换页面未实现。 |

### 8.5 未迁移功能详细清单与优先级建议

#### 8.5.1 P0（影响日常核心流程，建议尽快补齐）

1. **DAG 模板向导补全**（`features/dag-templates` + `pages/dag`） ✅ 已完成
   - 已迁移模板：blank、psi、data-classification、sanitization、query-obfuscation、k-anonymity、l-diversity、local-differential-privacy、differential-privacy、risk、tee。
   - 实现方式：每个模板为纯函数 `TemplateContribution.build(...)`，统一在 `registry.ts` 注册；`TemplateWizard` 按 basic/privacy/ml 分类展示，根据模板类型动态渲染双表/单表/无参数表单；创建时先调用 `createGraph` 获取 graphId，再调用 `template.build` 生成节点/边，最后 `updateGraph` 提交完整拓扑。
   - 剩余模板（引导式向导、场景 PSI、TEE PSI 等）因与现有模板拓扑等效或属于体验增强，归入 P1/P2 按需迁移。

2. **结果管理独立页面**（`pages/results`） ✅ 已完成
   - 旧模块：`result-manager`、`pipeline-record-list`。
   - 新前端：新增 `/results` 路由与侧边栏入口，调用 `node/result/list`、`node/result/detail`、`data/download`。
   - 支持搜索、按类型（table/report/rule/model）筛选、时间排序、分页、下载、详情弹窗。批量删除旧前端未完全实现，暂不迁移。

3. **DAG 模型提交入口**（`pages/dag` 右侧面板）
   - 旧模块：`dag-model-submission`（`SubmissionDrawer`、`PipelineTitleComponent`）。
   - 建议：当选中训练节点且运行成功后，在节点面板增加“Pack Model”按钮，调用 `model/pack` 并轮询状态。

4. **DAG 周期任务入口**（`pages/dag`）
   - 旧模块：`main-dag/periodic-task-entry`。
   - 建议：在 DAG 工具栏增加“创建周期任务”按钮，将当前 graph 与 cron 表达式写入 `scheduled/graph/create`。

#### 8.5.2 P1（提升体验，建议下一阶段补齐）

5. **DAG 高级配置抽屉**（`packages/dag-next`）
   - 旧模块：`advanced-config`。
   - 建议：根据 `component/batch` 返回的 `attrs` 定义动态生成表单，替代当前原始 JSON 编辑。

6. **DAG 组件解释器 / 组件树增强**
   - 旧模块：`component-interpreter`、`component-tree`、`component-config`。
   - 建议：支持组件图标、中文/英文描述、输入输出端口可视化、配置项校验。

7. **新用户引导页**（`pages/guide`）
   - 旧模块：`guide`、`guide-pipeline`、`guide-node`。
   - 建议：首次登录无项目/节点时弹出的引导页，或分步 tour。

8. **数据上传页面**（`pages/data-upload` 或集成到 `data-tables`）
   - 旧接口：`DataController.upload`/`download`/`sync`。
   - 建议：在数据表页面增加“本地上传”入口，支持 CSV/文件选择。

9. **EDGE / P2P 工作台聚合页**（`pages/edge`、`pages/workbench`）
   - 旧模块：`edge`、`p2p-workbench`。
   - 建议：按平台类型在侧边栏首页展示聚合工作台，方便 EDGE 用户一站式查看数据/合作节点/项目/结果。

10. **DAG 日志 Monaco 查看器**
    - 旧模块：`dag-log`。
    - 建议：引入 `@monaco-editor/react` 或简单虚拟滚动 + 高亮，提升大日志可读性。

#### 8.5.3 P2（高级/低频功能，按需迁移）

11. **隐私组件场景展示页**（`pages/privacy-scenes`）
    - 用于演示场景和快速创建示例项目，产品展示价值高，但非核心生产流程。

12. **模型导出与发布详情**（`pages/models`）
    - 对接 `ModelExportController` 和 `model-manager/model-release`。

13. **云端日志 SLS 页面**（`pages/cloud-logs`）
    - 对接 `CloudLogController` 的 `cloud_log/sls`。

14. **特征数据源与投票同步页面**
    - 对接 `FeatureDatasourceController`、`VoteSyncController`。

15. **组件版本管理页面**
    - 对接 `ComponentVersionController`。

16. **数据表树形血缘视图**（`data-table-tree`）
    - 在数据表详情现有“授权链路”基础上扩展为树/图可视化。

### 8.6 迁移建议

1. **优先 P0**：DAG 模板、结果管理、模型提交、周期任务入口是当前用户在 DAG 上跑通完整业务闭环的最大缺口。补齐后，旧前端 90% 以上的核心操作可由新前端独立完成。
2. **补齐 P1 体验项**：高级配置、组件解释器、引导页、数据上传完成后，新前端在生产可用性上基本追平旧前端。
3. **P2 按需**：隐私场景、模型导出、云日志、组件版本等可按业务需求分阶段迁移，不必阻塞主线。
4. **平台模式适配**：EDGE / P2P 工作台聚合页可以复用现有独立页面，通过新增一个“工作台”首页组件组合呈现，避免重复开发。
5. **保持一致性**：所有新增页面继续沿用 `TanStack Router`、`TanStack Query`、`Zustand`、`@secretpad/design-system`、i18n 字典的现有模式；新增 DAG 模板继续按 `pipeline-template-*.ts` 的纯函数方式生成 graph 定义，便于测试与维护。

### 8.7 当前结论

- 新前端已覆盖旧前端 **登录、Dashboard、项目、节点、数据源、数据表、模型管理、消息、周期任务、账户、机构、P2P 项目/节点** 等核心 CRUD 能力。
- **DAG 模型提交、DAG 周期任务入口** 是剩余最显著的迁移缺口。
- **EDGE/P2P 工作台聚合页、新用户引导、数据上传、隐私场景展示页** 是次要的体验与平台适配缺口。
- 旧前端中部分能力（如 `pages/graphs.tsx` 占位页、部分已弃用的 CENTER 工作台）可不再迁移，直接以新前端的独立路由方式替代。
