# SecretPad 新前端迁移一致性保障指南

> 本文档说明如何确保旧前端 `secretpad/frontend-src` 的功能与接口在 `secretpad/web` 新前端上保持一致并正确运行。
> 适用对象：参与新前端迁移、测试、Review 的开发人员。

---

## 1. 迁移目标

将旧前端（Umi 4 + Ant Design 5 + Valtio）完整迁移到新前端（Vite 5 + Tailwind + Zustand）后，必须满足：

1. **接口契约一致**：新前端调用与旧前端相同的 REST 接口，请求/响应字段一致。
2. **功能行为一致**：页面交互、表单校验、权限控制、平台适配与旧前端对齐。
3. **视觉与信息架构一致**：页面路由、菜单、关键操作流程（登录 → 项目 → DAG → 结果）保持一致。
4. **回归可验证**：每次改动都有单元/集成/E2E 测试兜底，关键链路可通过自动化脚本复跑。

---

## 2. 当前状态

| 维度 | 旧前端 `frontend-src` | 新前端 `web` |
|---|---|---|
| 框架 | Umi 4 + Ant Design 5 + Valtio | Vite 5 + Tailwind + Zustand |
| API 层 | `@umijs/openapi` 生成真实后端调用 | ✅ `openapi-typescript` + `openapi-fetch` 已接入真实后端 |
| 路由/页面 | ~17 个路由，覆盖 CENTER/EDGE/P2P/AUTONOMY | 8 个页面骨架，P0 页面已接后端 |
| 后端接口 | 约 100+ 个 | P0 关键接口已接入（login、node/list、project/list、datasource/list、datatable/list） |
| 权限/平台适配 | 完整 route wrappers + `hasAccess` | 已支持登录态持久化、401 拦截、平台信息保存；细粒度平台 guard 待补充 |

---

## 3. 分阶段迁移策略

### Phase 1：API 契约层（接口一致性）— 已完成

目标：新前端拿到跟旧前端完全一致、带类型的后端 API 客户端。

#### 3.1 生成 OpenAPI 文档

后端已集成 SpringDoc，启动后访问：

```bash
curl http://127.0.0.1:8080/v3/api-docs \
  > /home/charles/code/sfwork/secretpad/web/openapi/secretpad.openapi.json
```

生成文件已提交到仓库，作为前后端契约的“快照”。

#### 3.2 生成 TypeScript 客户端

在 `web/scripts/codegen-openapi.ts` 中调用 `openapi-typescript`，将 spec 转为类型定义：

```bash
cd /home/charles/code/sfwork/secretpad/web
pnpm codegen:openapi
```

输出：`packages/api-client/src/generated/secretpad.d.ts`。

#### 3.3 接入真实请求

`packages/api-client` 使用 `openapi-fetch` + 生成的类型封装统一请求客户端：

- 复用 `apps/secretpad/vite.config.ts` 中的 `/api` proxy。
- 请求头注入 `User-Token`（从 `localStorage` 读取）和 `Trace-Id`。
- 401 / token 过期统一拦截并跳转 `/login`。
- 返回数据用 Zod schema 做运行时映射（`packages/api-client/src/schemas`）。

### Phase 2：功能对账矩阵（功能一致性）— P0 主链路已完成

| 优先级 | 旧页面/功能 | 新前端目标页 | 关键接口 | 状态 |
|---|---|---|---|---|
| P0 | Login | `/login` | `POST /api/login`, `POST /api/logout` | ✅ |
| P0 | Dashboard / Home | `/dashboard` | `node/list`, `project/list`（jobs 暂时为空） | ✅ |
| P0 | Node Management | `/nodes` | `node/list` | ✅ 列表 |
| P0 | Data Source | `/data-sources` | `datasource/list` | ✅ 列表 |
| P0 | Data Table | `/data-tables` | `datatable/list` | ✅ 列表 |
| P0 | Project List | `/projects` | `project/list`, `project/create` | ✅ 列表 + 创建 |
| P1 | DAG Canvas | `/dag` | `graph/*`, `component/*`, `project/datatable/*` | 待补充 |
| P1 | Graph/Pipeline List | 新增 `/graphs` | `graph/list/start/stop` | 待补充 |
| P1 | Record/Result | 新增 `/record` | `project/job/*`, `graph/node/output`, `data/download` | 待补充 |
| P2 | Message Center | `/messages` | `message/list/reply/pending` | 待补充 |
| P2 | Model Manager | `/models` | `model/page/*`, `model/serving/*` | 待补充 |
| P2 | Periodic Tasks | `/periodic-tasks` | `scheduled/*` | 待补充 |
| P2 | Edge / P2P / AUTONOMY 专属页 | 视平台类型动态渲染 | `p2p/*`, `inst/*`, `nodeRoute/*` | 待补充 |

### Phase 3：权限与平台适配

旧前端的路由 guards 需要在新前端中补齐：

| Guard | 规则 |
|---|---|
| `RequiredAuth` | 未登录拦截，跳 `/login` |
| `CenterAuth` | `platformType === 'CENTER'` |
| `EdgeAuth` | 允许 CENTER 管理员操作嵌入节点，或 `platformType === 'EDGE'` 且 `ownerId` 匹配 |
| `AutonomyAuth` | `platformType === 'AUTONOMY'` 且 `ownerId` 匹配 |
| `P2pCenterAuth` | 允许 `CENTER` 或 `AUTONOMY` 访问 DAG/Record/ModelSubmission |

实现建议：

- 在 `features/auth/lib/platform-type.ts` 中定义平台判断函数。
- 在 `features/auth/ui/required-auth.tsx` / `required-permission.tsx` 中实现路由/按钮级守卫。
- 菜单项根据 `platformType` 动态过滤。

### Phase 4：测试兜底

#### 4.1 单元/集成测试（Vitest）

已覆盖：

- `packages/utils/src/crypto.test.ts` — SHA-256 与旧前端 `crypto-js/sha256` 输出一致。
- `packages/api-client/src/client.test.ts` — `login` / `logout`、`node/list`、`datasource/list` 的响应映射与 token 管理。
- `apps/secretpad/src/pages/login/login.test.tsx` — 表单提交、登录成功回调、失败提示。
- `apps/secretpad/src/app/App.test.tsx` — 登录态持久化后渲染 Dashboard。

后续每个 P0 页面补充：加载状态、空数据、数据渲染、分页/搜索、表单弹窗提交成功/失败。

#### 4.2 E2E 测试（Playwright）

启动真实 backend + Kuscia + 新前端后，跑关键路径：

```
login → create project → add node → create data source
→ upload data table → create graph → run graph → view result
```

E2E 用例放在 `secretpad/web/e2e/`。

#### 4.3 接口契约回归

CI 中执行：

```bash
pnpm codegen:openapi
```

若生成的类型与上一次提交有 diff，则 PR 必须显式说明后端接口变更，并同步前端调用点。

---

## 4. 已知后端字段映射与注意事项

### 4.1 登录

- 旧前端：`passwordHash = sha256(password).toString()`。
- 新前端：`packages/utils/src/crypto.ts` 中的 `sha256` 返回相同小写 hex。
- 后端返回 `UserContextDTO`：`token`, `name`, `ownerId`, `ownerType`, `platformType`, `platformNodeId`。
- 新前端保存 `secretpad-token` 与 `secretpad-user` 到 `localStorage`，并在 `App` 启动时 `rehydrate`。

### 4.2 节点列表

- 接口：`POST /api/v1alpha1/node/list`，请求体可为空对象 `{}`。
- 关键字段：`nodeId`, `nodeName`, `nodeStatus`, `type`, `netAddress`, `gmtCreate`。
- 旧前端部分场景使用 `status`、`name`、`ip`、`createTime`，新前端在 schema 中做了向后兼容映射。

### 4.3 项目列表

- 接口：`POST /api/v1alpha1/project/list`，请求体可为空对象 `{}`。
- 关键字段：`projectId`, `projectName`, `description`, `computeMode`, `status`, `nodes`（`ProjectNodeVO[]`），`gmtCreate`。
- `jobCount` 后端不一定返回，前端默认补 0。

### 4.4 创建项目

- 接口：`POST /api/v1alpha1/project/create`。
- 必填字段：`name`, `computeMode`, `teeNodeId`；非 TEE 场景 `teeNodeId` 传空字符串即可。
- 节点需在创建后通过 `project/node/add` 单独添加，当前创建弹窗仅收集名称/描述/计算模式。

### 4.5 数据源列表

- 接口：`POST /api/v1alpha1/datasource/list`。
- 必须字段：`ownerId`（对应节点 `nodeId`，不是 `platformNodeId`）。
- 返回结构：`{ infos: DatasourceListInfoAggregate[] }`，其中 `infos[].nodes` 为关联节点数组。
- 新前端先调 `node/list`，再用第一个节点的 `nodeId` 作为 `ownerId` 请求数据源。

### 4.6 数据表列表

- 接口：`POST /api/v1alpha1/datatable/list`。
- 必须字段：`pageSize`, `pageNumber`；可选 `ownerId`（节点 `nodeId`）。
- 返回结构：`{ datatableNodeVOList: DatatableNodeVO[] }`。
- `DatatableNodeVO.datatableVO` 包含 `datatableId`, `datatableName`, `status`, `schema.columns`, `rowCount`。

---

## 5. 最小闭环验证流程

建议按以下顺序逐个接口替换 mock，每完成一步就通过浏览器 DevTools 验证：

1. 启动后端（`target/secretpad.jar`）和新前端（`pnpm dev`）。
2. 替换 `/api/login` 为真实调用，登录成功后保存 `User-Token`。
3. 替换 Dashboard 的 `node/list`、`project/list`。
4. 替换 `/nodes` 的 `node/list`。
5. 替换 `/data-sources` 和 `/data-tables` 的列表接口。
6. 替换 `/projects` 的列表、创建接口。

每替换一个接口，在 `packages/api-client/src/` 补充：

- Zod response schema。
- 错误处理分支。
- 对应 Vitest 测试。

---

## 6. 检查清单（Checklist）

### API 层

- [x] `openapi/secretpad.openapi.json` 已生成并提交。
- [x] `packages/api-client` 已接入 `openapi-fetch` 并复用 Vite `/api` proxy。
- [x] 请求头自动注入 `User-Token` 和 `Trace-Id`。
- [x] 401 统一拦截并跳转 `/login`。
- [x] 关键响应已加 Zod 运行时映射。

### P0 主链路

- [x] 登录/登出可用。
- [x] Dashboard 展示真实节点、项目统计（jobs 待接入真实接口）。
- [x] 节点管理：列表已接入。
- [x] 数据源：列表已接入。
- [x] 数据表：列表已接入。
- [x] 项目管理：列表、创建已接入。

### 权限与平台

- [x] 未登录访问受保护路由自动跳转登录页（401 拦截 + 刷新后 rehydrate）。
- [ ] `CENTER` 用户看到管理菜单，`EDGE`/`AUTONOMY` 用户看到对应工作台。
- [ ] 按钮级权限（如删除节点、创建项目）与旧前端一致。

### 测试

- [x] P0 页面均有 Vitest 测试（login、api-client、App）。
- [ ] 至少一条 Playwright E2E 关键路径跑通。
- [ ] CI 中运行 `pnpm codegen:openapi` 无未解释的类型 diff。

---

## 7. 相关命令

```bash
# 1. 生成 OpenAPI 客户端
cd /home/charles/code/sfwork/secretpad/web
pnpm codegen:openapi

# 2. 运行类型检查与测试
pnpm run typecheck
pnpm test

# 3. 生产打包并集成到后端
cd /home/charles/code/sfwork/secretpad
./scripts/build/build.sh true

# 4. 全栈本地启动
bash /home/charles/code/sfwork/scripts1/run-all-no-docker.sh
```

---

## 8. 附录：旧前端 API 接口速查

主要接口分类（详见旧前端 `frontend-src/apps/platform/src/services/secretpad/`）：

- **Auth**: `/api/login`, `/api/logout`, `/api/v1alpha1/user/get`, `/api/v1alpha1/user/updatePwd`
- **Node**: `/api/v1alpha1/node/page`, `/api/v1alpha1/node/create`, `/api/v1alpha1/node/update`, `/api/v1alpha1/node/delete`, `/api/v1alpha1/node/refresh`
- **DataSource**: `/api/v1alpha1/datasource/list`, `/api/v1alpha1/datasource/create`, `/api/v1alpha1/datasource/delete`
- **DataTable**: `/api/v1alpha1/datatable/list`, `/api/v1alpha1/datatable/create`, `/api/v1alpha1/datatable/delete`
- **Project**: `/api/v1alpha1/project/list`, `/api/v1alpha1/project/create`, `/api/v1alpha1/project/update`, `/api/v1alpha1/project/delete`
- **Graph/DAG**: `/api/v1alpha1/graph/*`
- **Message**: `/api/v1alpha1/message/*`
- **Model**: `/api/v1alpha1/model/*`
- **Scheduled**: `/api/v1alpha1/scheduled/*`

完整接口列表以 `openapi/secretpad.openapi.json` 为准。
