# SecretPad 前后端接口与 Kuscia/DataMesh 集成详解

本文档系统梳理 SecretPad 前端与后端之间的所有 HTTP 接口、主要功能与后端处理逻辑，并深入解释后端如何通过 gRPC 调用 Kuscia，以及如何通过 Kuscia DataMesh 完成数据的上传、下载、注册、授权与查询。

---

## 1. 架构概述

SecretPad 采用经典的前后端分离架构：

- **前端**：基于 [UmiJS 4](https://umijs.org/) 的 React 单页应用，源码位于 `frontend-src/apps/platform`。
- **后端**：基于 Spring Boot 的 Java 服务，源码按模块拆分：
  - `secretpad-web`：REST 控制器（Controller）、拦截器、全局异常处理。
  - `secretpad-service`：业务服务接口与实现（Service）、DTO、Graph 执行链。
  - `secretpad-manager`：Kuscia 集成层（Manager），负责把 SecretPad 的业务对象转换为 Kuscia 的 protobuf 请求。
  - `secretpad-persistence`：数据访问层（Repository/DO）。
  - `secretpad-common`：通用工具、常量、注解、响应包装类。
  - `secretpad-api/client-java-kusciaapi`：Kuscia v1alpha1 gRPC 客户端封装。

前后端之间通过 REST API 通信；后端与 Kuscia 之间通过 gRPC（protobuf）通信；DataMesh 是 Kuscia 的数据访问层，后端不直接连接 DataMesh，而是通过 Kuscia 的 `DomainDataService`、`DomainDataSourceService`、`DomainDataGrantService` 等 gRPC 服务间接操作 DataMesh。

---

## 2. 前端 API 调用体系

### 2.1 HTTP 客户端与拦截器

前端统一使用 UmiJS 内置的 `umi-request` 发送请求，入口配置在：

- `frontend-src/apps/platform/src/app.ts`：全局请求/响应拦截器。
- `frontend-src/apps/platform/config/config.ts`：开发代理配置。
- `frontend-src/apps/platform/config/openapi.config.js`：OneAPI / `@umijs/openapi` 代码生成配置。

每个请求自动携带：

- `User-Token`：从 `localStorage` 读取的登录令牌。
- `Trace-Id`：UUID，用于全链路追踪。
- `Content-Type: application/json`（文件上传除外）。

响应拦截器检测到后端返回 `status.code === 202011602`（`AUTH_FAILED`）时，会自动跳转到登录页。

### 2.2 自动生成的 API 客户端

所有前端调用后端的 API 客户端都通过 `@umijs/openapi` 自动生成，位于：

```
frontend-src/apps/platform/src/services/secretpad/
├── index.ts              # 汇总导出所有控制器
├── typings.d.ts          # 所有请求/响应 TypeScript 类型定义
├── AuthController.ts
├── UserController.ts
├── NodeController.ts
├── ProjectController.ts
├── GraphController.ts
├── DataController.ts
├── DatatableController.ts
├── ...（共 26 个控制器文件）
```

生成的函数模式示例：

```ts
export async function login(body?: API.LoginRequest, options?: { [key: string]: any }) {
  return request<API.SecretPadResponse_UserContextDTO_>('/api/login', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    data: body,
    ...(options || {}),
  });
}
```

业务模块通常会在生成的客户端之上再做一层 service 封装，例如 `src/modules/login/login.service.ts`。

### 2.3 请求与响应格式

**请求**：绝大多数接口为 `POST`，参数放在 JSON Body 中。少量接口使用 multipart/form-data（文件上传、节点注册）或 query 参数。

**响应**：统一 envelope 结构：

```ts
interface SecretPadResponse<T = any> {
  status?: {
    code: number;   // 0 表示成功
    msg?: string;
  };
  data?: T;
}
```

前端通常这样处理：

```ts
const { status, data } = await API.ProjectController.listProject();
if (status?.code === 0) {
  // 使用 data
} else {
  message.error(status?.msg);
}
```

### 2.4 认证与路由守卫

登录成功后，前端把 token 写入 `localStorage.setItem('User-Token', token)`。后续所有请求通过拦截器自动携带。

路由级权限守卫位于 `frontend-src/apps/platform/src/wrappers/`，包括：

| 文件 | 作用 |
|---|---|
| `login-auth.tsx` | 校验 `User-Token` 与 `neverLogined`，缺失则跳转登录页 |
| `edge-auth.tsx` | 校验当前用户是否为 EDGE 平台账号 |
| `center-auth.tsx` / `p2p-center-auth.tsx` / `p2p-edge-center-auth.tsx` / `p2p-login-auth.tsx` | 不同部署模式下的访问控制 |
| `node-auth.tsx` | 校验节点是否存在 |

---

## 3. 后端 REST API 清单

后端 Controller 统一放在 `secretpad-web/src/main/java/org/secretflow/secretpad/web/controller/` 下，含 P2P 专属子包 `p2p/`。

### 3.1 通用约定

- **响应包装**：所有 Controller 返回 `SecretPadResponse<T>`，由 `secretpad-service/.../service/model/common/SecretPadResponse.java` 定义。
- **参数校验**：在 `@RequestBody` 上使用 `@Valid`，配合 Jakarta Bean Validation。
- **分页**：存在两种风格：
  - `SecretPadPageRequest` / `SecretPadPageResponse`（JPA 风格，用于节点、路由、定时任务）。
  - `PageRequest` / `PageResponse`（自定义风格，用于项目任务列表）。
- **认证**：`LoginInterceptor` 读取 `User-Token` 头或 `kuscia-origin-source` 头，把当前用户写入 `UserContext` 的 ThreadLocal。
- **鉴权**：
  - 接口级：`@ApiResource(code = "...")` + `InterfaceResourceAspect`。
  - 数据级：`@DataResource(field = "...", resourceType = ...)` + `DataResourceAspect`，从请求体提取字段校验归属权。

### 3.2 接口总览

| 控制器 | 基础路径 | 端点数 | 主要功能 |
|---|---|---|---|
| `AuthController` | `/api` | 2 | 登录、登出 |
| `UserController` | `/api/v1alpha1/user` | 2 | 当前用户信息、修改密码 |
| `NodeUserController` | `/api/v1alpha1/user/node` | 1 | 重置节点用户密码 |
| `RemoteUserController` | `/api/v1alpha1/user/remote` | 1 | EDGE 向 CENTER 代理重置密码 |
| `NodeController` | `/api/v1alpha1/node` | 11 | 节点 CRUD、Token、刷新、结果 |
| `P2pNodeController` | `/api/v1alpha1/p2p/node` | 2 | P2P 协作节点创建/删除 |
| `NodeRouteController` | `/api/v1alpha1/nodeRoute` | 6 | 节点路由 CRUD、刷新 |
| `InstController` | `/api/v1alpha1/inst` | 7 | 机构节点管理、注册 |
| `DataController` | `/api/v1alpha1/data` | 3 | 数据上传、下载、创建（已废弃） |
| `DatatableController` | `/api/v1alpha1/datatable` | 5 | 数据表 CRUD、推送 TEE |
| `DataSourceController` | `/api/v1alpha1/datasource` | 5 | 数据源 CRUD、节点映射 |
| `FeatureDatasourceController` | `/api/v1alpha1/feature_datasource` | 2 | 特征数据源（已废弃/授权列表） |
| `ProjectController` | `/api/v1alpha1/project` | 19 | 项目、任务、数据表、节点、机构 |
| `P2PProjectController` | `/api/v1alpha1/p2p/project` | 5 | P2P 项目创建/列表/归档/参与者 |
| `GraphController` | `/api/v1alpha1` | 16 | DAG 图、组件、启动/停止/日志/输出 |
| `ModelManagementController` | `/api/v1alpha1/model` | 8 | 模型包、模型服务 |
| `ModelExportController` | `/api/v1alpha1/model` | 3 | 模型导出包/状态/参与方路径 |
| `MessageController` | `/api/v1alpha1/message` | 4 | 消息/投票列表、详情、回复、待处理数 |
| `ApprovalController` | `/api/v1alpha1/approval` | 2 | 审批创建、拉取状态 |
| `VoteSyncController` | `/api/v1alpha1/vote_sync` | 1 | 投票同步 |
| `ScheduledController` | `/api/v1alpha1/scheduled` | 12 | 周期性/定时图任务 |
| `ComponentVersionController` | `/api/v1alpha1` | 1 | 组件/镜像版本列表 |
| `CloudLogController` | `/api/v1alpha1/cloud_log` | 1 | 云/SLS 日志 |
| `CenterDataSyncController` | `/sync` | 1 | Server-Sent Events 中心-边缘同步 |
| `DataSyncController`（P2P） | `/api/v1alpha1/data/sync` | 1 | P2P 数据同步消费端 |
| `IndexController` | `/` 等 | 13 | SPA 前端路由回退 |

> 前端共生成约 83 个端点（含 13 个 index 回退路由与 1 个 SSE 同步路由）。


### 3.3 按功能模块的详细接口

#### 3.3.1 认证与用户

| 方法 | 端点 | 服务方法 | 说明 |
|---|---|---|---|
| `POST` | `/api/login` | `AuthService.login` | 返回 `UserContextDTO`（含 token） |
| `POST` | `/api/logout` | `AuthService.logout` | 从请求头提取 token 登出 |
| `POST` | `/api/v1alpha1/user/get` | `UserContext.getUser()` | 当前登录用户信息 |
| `POST` | `/api/v1alpha1/user/updatePwd` | `UserService.updatePwd` | 修改当前用户密码 |
| `POST` | `/api/v1alpha1/user/node/resetPassword` | `NodeUserService.resetPassword` | 重置节点用户密码 |
| `POST` | `/api/v1alpha1/user/remote/resetPassword` | 通过 `RestTemplateUtil` 代理 | EDGE 模式下转发给 CENTER |

#### 3.3.2 节点

`NodeController` → `/api/v1alpha1/node`

| 方法 | 端点 | 服务方法 | 响应/说明 |
|---|---|---|---|
| `POST` | `/create` | `NodeService.createNode` | 创建节点，返回 nodeId |
| `POST` | `/update` | `NodeService.updateNode` | 更新节点 |
| `POST` | `/page` | `NodeService.queryPage` | 分页查询节点 |
| `POST` | `/get` | `NodeService.getNode` | 单个节点详情 |
| `POST` | `/delete` | `NodeService.deleteNode` | 删除节点 |
| `POST` | `/token` | `NodeService.getNodeToken(..., false)` | 获取现有 token |
| `POST` | `/newToken` | `NodeService.getNodeToken(..., true)` | 重新生成 token |
| `POST` | `/refresh` | `NodeService.refreshNode` | 刷新节点状态 |
| `POST` | `/list` | `NodeService.listNodes` | 列出所有节点 |
| `POST` | `/result/list` | `NodeService.listAllNodeResults` | 列出节点结果 |
| `POST` | `/result/detail` | `NodeService.getNodeResultDetail` | 节点结果详情 |

`P2pNodeController` → `/api/v1alpha1/p2p/node`

| 方法 | 端点 | 服务方法 |
|---|---|---|
| `POST` | `/create` | `NodeService.createP2pNode` |
| `POST` | `/delete` | `NodeService.deleteP2pNodeRoute` |

#### 3.3.3 节点路由

`NodeRouteController` → `/api/v1alpha1/nodeRoute`

| 方法 | 端点 | 服务方法 |
|---|---|---|
| `POST` | `/page` | `NodeRouterService.queryPage` |
| `POST` | `/get` | `NodeRouterService.getNodeRouter` |
| `POST` | `/update` | `NodeRouterService.updateNodeRouter` |
| `POST` | `/listNode` | `NodeService.listNodes` |
| `POST` | `/refresh` | `NodeRouterService.refreshRouter` |
| `POST` | `/delete` | `NodeRouterService.deleteNodeRouter` |

#### 3.3.4 机构

`InstController` → `/api/v1alpha1/inst`

| 方法 | 端点 | 服务方法 | 说明 |
|---|---|---|---|
| `POST` | `/get` | `InstService.getInst` | 机构信息 |
| `POST` | `/node/list` | `InstService.listNode` | 机构节点列表 |
| `POST` | `/node/add` | `InstService.createNode` | 添加机构节点 |
| `POST` | `/node/token` | `InstService.getToken` | 获取 token |
| `POST` | `/node/newToken` | `InstService.newToken` | 刷新 token |
| `POST` | `/node/delete` | `InstService.deleteNode` | 删除机构节点 |
| `POST` | `/node/register` | `InstService.registerNode` | multipart 注册节点 |

#### 3.3.5 数据 / 数据表 / 数据源

`DataController` → `/api/v1alpha1/data`

| 方法 | 端点 | 服务方法 | 说明 |
|---|---|---|---|
| `POST` | `/upload` | `DataService.upload` | multipart 上传 CSV |
| `POST` | `/create` | `DataService.createData` | 已废弃 |
| `POST` | `/download` | `DataService.download` | 下载结果数据 |

`DatatableController` → `/api/v1alpha1/datatable`

| 方法 | 端点 | 服务方法 |
|---|---|---|
| `POST` | `/create` | `DatatableService.createDataTable` |
| `POST` | `/list` | `DatatableService.listDatatablesByOwnerId` |
| `POST` | `/get` | `DatatableService.getDatatable` |
| `POST` | `/delete` | `DatatableService.deleteDatatable` |
| `POST` | `/pushToTee` | `DatatableService.pushDatatableToTeeNode` |

`DataSourceController` → `/api/v1alpha1/datasource`

| 方法 | 端点 | 服务方法 |
|---|---|---|
| `POST` | `/create` | `DatasourceService.createDatasource` |
| `POST` | `/delete` | `DatasourceService.deleteDatasource` |
| `POST` | `/list` | `DatasourceService.listDatasource` |
| `POST` | `/detail` | `DatasourceService.datasourceDetail` |
| `POST` | `/nodes` | `DatasourceService.datasourceNodes` |

`FeatureDatasourceController` → `/api/v1alpha1/feature_datasource`

| 方法 | 端点 | 服务方法 |
|---|---|---|
| `POST` | `/create` | `FeatureTableService.createFeatureTable`（已废弃） |
| `POST` | `/auth/list` | `FeatureTableService.projectFeatureTableList` |

#### 3.3.6 项目与任务

`ProjectController` → `/api/v1alpha1/project`

| 方法 | 端点 | 服务方法 | 说明 |
|---|---|---|---|
| `POST` | `/create` | `ProjectService.createProject` | 创建项目 |
| `POST` | `/list` | `ProjectService.listProject` | 项目列表 |
| `POST` | `/get` | `ProjectService.getProject` | 项目详情 |
| `POST` | `/update` | `ProjectService.updateProject` | 更新项目 |
| `POST` | `/delete` | `ProjectService.deleteProject` | 删除项目 |
| `POST` | `/inst/add` | `ProjectService.addInstToProject` | 添加机构到项目 |
| `POST` | `/node/add` | `ProjectService.addNodeToProject` | 添加节点到项目 |
| `POST` | `/datatable/add` | `ProjectService.addDatatableToProject` | 添加数据表到项目 |
| `POST` | `/datatable/delete` | `ProjectService.deleteDatatableToProject` | 移除项目数据表 |
| `POST` | `/datatable/get` | `ProjectService.getProjectDatatable` | 项目数据表详情 |
| `POST` | `/job/list` | `ProjectService.listProjectJob` | 任务列表（分页） |
| `POST` | `/job/get` | `ProjectService.getProjectJob` | 任务详情 |
| `POST` | `/job/stop` | `ProjectService.stopProjectJob` | 停止任务 |
| `POST` | `/job/task/logs` | `ProjectService.getProjectJobTaskLogs` | 任务日志 |
| `POST` | `/job/task/output` | `GraphService.getGraphNodeTaskOutputVO` | 任务输出 |
| `POST` | `/tee/list` | `NodeService.listTeeNode` | TEE 节点列表 |
| `POST` | `/getOutTable` | `ProjectService.getProjectAllOutTable` | 项目输出表 |
| `POST` | `/update/tableConfig` | `ProjectService.updateProjectTableConfig` | 更新表配置 |
| `POST` | `/datasource/list` | `ProjectService.getProjectGraphDomainDataSource` | 项目图数据源 |

`P2PProjectController` → `/api/v1alpha1/p2p/project`

| 方法 | 端点 | 服务方法 |
|---|---|---|
| `POST` | `/create` | `ProjectService.createP2PProject` |
| `POST` | `/list` | `ProjectService.listP2PProject` |
| `POST` | `/update` | `ProjectService.updateP2PProject` |
| `POST` | `/archive` | `ProjectService.archiveProject` |
| `POST` | `/participants` | `ProjectService.getProjectParticipants` |

#### 3.3.7 图（DAG）与组件

`GraphController` → `/api/v1alpha1`

| 方法 | 端点 | 服务方法 | 说明 |
|---|---|---|---|
| `POST` | `/component/i18n` | `GraphService.listComponentI18n` | 组件国际化 |
| `POST` | `/component/list` | `GraphService.listComponents` | 组件列表 |
| `POST` | `/component/batch` | `GraphService.batchGetComponent` | 批量组件详情 |
| `POST` | `/graph/create` | `GraphService.createGraph` | 创建图 |
| `POST` | `/graph/delete` | `GraphService.deleteGraph` | 删除图 |
| `POST` | `/graph/list` | `GraphService.listGraph` | 图列表 |
| `POST` | `/graph/meta/update` | `GraphService.updateGraphMeta` | 更新图元信息 |
| `POST` | `/graph/update` | `GraphService.fullUpdateGraph` | 完整更新图 |
| `POST` | `/graph/node/update` | `GraphService.updateGraphNode` | 更新图节点 |
| `POST` | `/graph/start` | `GraphService.startGraph` | 启动图执行 |
| `POST` | `/graph/node/status` | `GraphService.listGraphNodeStatus` | 节点状态 |
| `POST` | `/graph/stop` | `GraphService.stopGraphNode` | 停止图节点 |
| `POST` | `/graph/detail` | `GraphService.getGraphDetail` | 图详情 |
| `POST` | `/graph/node/output` | `GraphService.getGraphNodeOutput` | 节点输出 |
| `POST` | `/graph/node/logs` | `GraphService.getGraphNodeLogs` | 节点日志 |
| `POST` | `/graph/node/max_index` | `GraphService.refreshNodeMaxIndex` | 刷新节点最大索引 |

#### 3.3.8 模型管理

`ModelManagementController` / `ModelExportController` → `/api/v1alpha1/model`

| 方法 | 端点 | 服务方法 | 说明 |
|---|---|---|---|
| `POST` | `/page` | `ModelManagementService.modelPackPage` | 模型包分页 |
| `POST` | `/detail` | `ModelManagementService.modelPackDetail` | 模型包详情 |
| `POST` | `/info` | `ModelManagementService.modelPackInfo` | 模型包信息 |
| `POST` | `/serving/create` | `ModelManagementService.createModelServing` | 创建模型服务 |
| `POST` | `/serving/detail` | `ModelManagementService.queryModelServingDetail` | 服务详情 |
| `POST` | `/serving/delete` | `ModelManagementService.deleteModelServing` | 删除服务 |
| `POST` | `/discard` | `ModelManagementService.discardModelPack` | 废弃模型包 |
| `POST` | `/delete` | `ModelManagementService.deleteModelPack` | 删除模型包 |
| `POST` | `/pack` | `ModelExportService.exportModel` | 导出模型包 |
| `POST` | `/status` | `ModelExportService.queryModel` | 查询导出状态 |
| `POST` | `/modelPartyPath` | `ModelExportService.modelPartyPath` | 参与方路径 |

#### 3.3.9 审批、消息与同步

| 控制器 | 端点 | 服务方法 | 说明 |
|---|---|---|---|
| `ApprovalController` | `/api/v1alpha1/approval/create` | `ApprovalService.createApproval` | 创建审批 |
| `ApprovalController` | `/api/v1alpha1/approval/pull/status` | `ApprovalService.pullStatus` | 拉取审批状态 |
| `MessageController` | `/api/v1alpha1/message/reply` | `MessageService.reply` | 回复投票 |
| `MessageController` | `/api/v1alpha1/message/list` | `MessageService.list` | 消息列表 |
| `MessageController` | `/api/v1alpha1/message/detail` | `MessageService.detail` | 消息详情 |
| `MessageController` | `/api/v1alpha1/message/pending` | `MessageService.pendingCount` | 待处理消息数 |
| `VoteSyncController` | `/api/v1alpha1/vote_sync/create` | `VoteSyncService.sync` | 投票同步 |
| `CenterDataSyncController` | `GET /sync` | `SseServer` / `JpaSyncDataService` | SSE 中心-边缘数据同步 |
| `DataSyncController` | `/api/v1alpha1/data/sync` | P2P 数据同步消费 | P2P 模式 |

#### 3.3.10 日志、版本、定时任务

| 控制器 | 端点 | 服务方法 | 说明 |
|---|---|---|---|
| `CloudLogController` | `/api/v1alpha1/cloud_log/sls` | `ICloudLogService.fetchLog` | SLS 云日志 |
| `ComponentVersionController` | `/api/v1alpha1/version/list` | `ComponentService.listComponentVersion` | 组件版本 |
| `ScheduledController` | `/api/v1alpha1/scheduled/id` | `ScheduledService.buildSchedulerId` | 生成调度 ID |
| `ScheduledController` | `/api/v1alpha1/scheduled/graph/once/success` | `ScheduledService.onceSuccess` | 单次成功 |
| `ScheduledController` | `/api/v1alpha1/scheduled/graph/create` | `ScheduledService.createScheduler` | 创建定时任务 |
| `ScheduledController` | `/api/v1alpha1/scheduled/page` | `ScheduledService.queryPage` | 分页 |
| `ScheduledController` | `/api/v1alpha1/scheduled/offline` | `ScheduledService.offline` | 下线 |
| `ScheduledController` | `/api/v1alpha1/scheduled/del` | `ScheduledService.del` | 删除 |
| `ScheduledController` | `/api/v1alpha1/scheduled/info` | `ScheduledService.info` | 详情 |
| `ScheduledController` | `/api/v1alpha1/scheduled/task/page` | `ScheduledService.taskPage` | 子任务分页 |
| `ScheduledController` | `/api/v1alpha1/scheduled/task/stop` | `ScheduledService.taskStop` | 停止子任务 |
| `ScheduledController` | `/api/v1alpha1/scheduled/task/rerun` | `ScheduledService.taskRerun` | 重跑子任务 |
| `ScheduledController` | `/api/v1alpha1/scheduled/task/info` | `ScheduledService.taskInfo` | 子任务详情 |
| `ScheduledController` | `/api/v1alpha1/scheduled/job/list` | `ScheduledService.listProjectJob` | 调度任务列表 |


---

## 4. 后端业务处理逻辑

### 4.1 分层调用链

后端采用典型的分层架构，调用链为：

```
Controller (@RestController)
    ↓
Service（业务编排、事务、DTO 转换）
    ↓
Manager（Kuscia 集成、protobuf 构造、Kuscia gRPC 调用）
    ↓
Repository（JPA/DO 持久化）
```

例如：

```
ProjectController.createProject
  → ProjectServiceImpl.createProject
    → ProjectRepository.save
    → NodeManager / NodeRouteManager（必要时访问 Kuscia）
```

### 4.2 Service 层职责

| Service | 主要职责 |
|---|---|
| `AuthService` | 登录、登出、token/session 管理 |
| `UserService` / `NodeUserService` | 用户 CRUD、密码管理 |
| `NodeService` | 节点 CRUD、token、刷新、结果查询 |
| `NodeRouterService` | 节点路由管理 |
| `InstService` | 机构与节点注册 |
| `DataService` | 数据上传/下载/创建 |
| `DatatableService` | 数据表生命周期、TEE 推送 |
| `DatasourceService` | 数据源 CRUD |
| `FeatureTableService` | 特征数据源 |
| `ProjectService` | 项目、任务、数据表、节点、机构 |
| `GraphService` | DAG 图执行、组件、日志、输出 |
| `ModelManagementService` / `ModelExportService` | 模型包、模型服务、模型导出 |
| `ComponentService` | SecretFlow 组件元数据 |
| `MessageService` / `ApprovalService` / `VoteSyncService` | 投票、消息、审批、同步 |
| `ScheduledService` | 定时图任务 |
| `ICloudLogService` | 云日志 |

### 4.3 关键业务流程

#### 4.3.1 登录

1. 前端 `POST /api/login`，携带 `name` 与 `passwordHash`（SHA-256）。
2. `AuthService.login` 校验用户名密码，生成 token。
3. 返回 `UserContextDTO`，前端存入 `localStorage`。
4. 后续请求由 `LoginInterceptor` 读取 `User-Token` 头并设置 `UserContext`。

#### 4.3.2 创建节点（CENTER 模式）

1. `NodeController.createNode` → `NodeServiceImpl.createNode`。
2. `NodeServiceImpl` 生成 domain id，持久化 `NodeDO`。
3. 调用 `NodeManager.createNode`，构造 Kuscia `CreateDomainRequest`：
   - `domainId` = nodeId
   - `authCenter.authenticationType = "Token"`
   - `tokenGenMethod = "UID-RSA-GEN"`
4. 通过 `KusciaGrpcClientAdapter.createDomain` 调用 Kuscia。
5. 成功后 Kuscia 返回部署 token，SecretPad 存入 `NodeDO.token`。

#### 4.3.3 上传数据

1. 前端 `POST /api/v1alpha1/data/upload`，multipart 上传 CSV，Header 携带 `Node-Id`。
2. `DataController.upload` → `DataServiceImpl.upload`。
3. 服务把文件保存到 `${secretpad.data.dir-path:/app/data/}{nodeId}/`。
4. 返回 `UploadDataResultVO`（真实文件名、默认数据源）。
5. 前端随后调用 `/api/v1alpha1/datatable/create`（或旧的 `/data/create`），把文件注册为数据表。
6. `DatatableServiceImpl.createDataTable` → `DatatableManager` → 构造 `CreateDomainDataRequest` → 调用 Kuscia `DomainDataService.createDomainData`。

#### 4.3.4 创建项目并添加数据表

1. `ProjectController.createProject` → `ProjectServiceImpl.createProject`。
2. 持久化 `ProjectDO`，建立项目与节点/机构的关联。
3. `ProjectController.addDatatableToProject` → `ProjectServiceImpl.addDatatableToProject`。
4. 对项目中除数据表所有者外的每个节点，调用 `DatatableGrantManager.createDomainGrant`。
5. 该 Manager 构造 `CreateDomainDataGrantRequest`：
   - `domainId` = 数据表所有者 nodeId
   - `domaindataId` = 数据表 id
   - `grantDomain` = 被授权节点 nodeId
   - `domaindatagrantId` = 可选，通常为 `{domaindataId}-{grantNodeId}`
6. 通过 `KusciaGrpcClientAdapter.createDomainDataGrant` 调用 Kuscia。

#### 4.3.5 启动图执行（创建 Kuscia Job）

1. `GraphController.startGraph` → `GraphServiceImpl.startGraph`。
2. 解析每个选中图节点对应的参与方，校验节点就绪与路由就绪。
3. 生成 `ProjectJob` 与 `ProjectJob.JobTask`。
4. 进入执行链：`jobChain.proceed(projectJob)` → `JobSubmittedHandler`。
5. `KusciaJobConverter.converter(job)`：
   - 把每个 `JobTask` 转为 Kuscia `Job.Task`。
   - 渲染 `TaskInputConfig`（JSON protobuf），包含：
     - `sfClusterDesc`
     - 数据源配置
     - 输入/输出 ID（`dm://` URI）
     - `sfNodeEvalParam`
     - SCQL 配置（如使用 SCQL 组件）
6. 构造 `Job.CreateJobRequest`：
   - `jobId`、`initiator`、`maxParallelism`、`tasks`
7. `JobManager.createJob` 调用 Kuscia `JobService.createJob`。
8. 在 AUTONOMY 模式下，通过 initiator 的 domain channel 发送；否则通过本地 channel。
9. `JobManager.startSync()` 启动长连接 `watchJob`，监听任务状态变化并同步到本地数据库。

#### 4.3.6 推送数据到 TEE

1. `DatatableController.pushToTee` → `DatatableServiceImpl.pushDatatableToTeeNode`。
2. 确保从数据所有者到 `teeNodeId` 的 `DomainDataGrant` 存在。
3. 持久化 `TeeNodeDatatableManagementDO`。
4. 构造 TEE 任务（`TeeJob`），经 `KusciaTeeDataManagerConverter` 转为 `CreateJobRequest`。
5. `JobManager.createJob` 提交给 Kuscia。

---

## 5. Kuscia 集成详解

### 5.1 设计思想

SecretPad 不直接调用 Kuscia 的 HTTP API，而是通过一个独立的 Java 模块 `secretpad-api/client-java-kusciaapi` 封装所有 Kuscia v1alpha1 gRPC 服务。该模块提供：

- 基于 protobuf 生成的 Java stub。
- 动态的、按 domain 缓存的 gRPC 通道管理。
- TLS/mTLS/明文三种传输模式。
- Token 认证与统一的日志拦截。

### 5.2 Kuscia gRPC 客户端初始化

#### 5.2.1 配置模型

每个 Kuscia 节点对应一个 `KusciaGrpcConfig`：

```java
public class KusciaGrpcConfig implements Serializable {
    private String domainId;          // Kuscia domain id
    private String host;              // Kuscia API 地址
    private int port;                 // Kuscia API 端口
    private KusciaProtocolEnum protocol; // TLS / MTLS / NOTLS
    private KusciaModeEnum mode;         // MASTER / LITE / P2P
    private String token;
    private String certFile;
    private String keyFile;
}
```

多节点配置通过 `DynamicKusciaGrpcConfig` 绑定 Spring `ConfigurationProperties(prefix = "kuscia")`：

```java
@ConfigurationProperties(prefix = "kuscia")
public class DynamicKusciaGrpcConfig {
    private CopyOnWriteArraySet<KusciaGrpcConfig> nodes;
}
```

典型 YAML 配置（`config/application.yaml`）：

```yaml
kusciaapi:
  protocol: ${KUSCIA_PROTOCOL:tls}

kuscia:
  nodes:
    - domainId: ${NODE_ID:kuscia-system}
      mode: master
      host: ${KUSCIA_API_ADDRESS:root-kuscia-master}
      port: ${KUSCIA_API_PORT:8083}
      protocol: ${KUSCIA_PROTOCOL:tls}
      cert-file: config/certs/client.crt
      key-file: config/certs/client.pem
      token: config/certs/token
```

不同部署模式对应不同 profile：

- `application.yaml`：默认 CENTER。
- `application-edge.yaml`：EDGE 节点。
- `application-p2p.yaml`：AUTONOMY / P2P。
- `application-dev.yaml`：本地开发。

#### 5.2.2 动态通道提供者

`DynamicKusciaChannelProvider` 是核心 Spring 服务，维护一个 `ConcurrentHashMap<String, KusciaApiChannelFactory>`：

- `@PostConstruct init()`：读取 YAML 中的节点列表注册；并从 `./config/kuscia/` 加载历史序列化配置。
- `@PreDestroy destroy()`：关闭所有通道。
- `registerKuscia(config)`：创建 `GrpcKusciaApiChannelFactory`，发布 `RegisterKusciaEvent`。
- `unRegisterKuscia(domainId)`：移除并关闭通道，发布 `UnRegisterKusciaEvent`。
- `createStub(domainId, clazz)`：按需创建 blocking / future / async stub。
  - blocking/future 默认超时 **5 秒**。
  - streaming（async）默认超时 **365 天**（用于 `watchJob`）。
- `currentStub(clazz)`：使用本地 `secretpad.node-id` 对应的通道。

#### 5.2.3 通道工厂

`GrpcKusciaApiChannelFactory` 基于 Netty 构建 `ManagedChannel`：

```java
NettyChannelBuilder nettyChannelBuilder = NettyChannelBuilder
        .forAddress(kusciaGrpcConfig.getHost(), kusciaGrpcConfig.getPort())
        .intercept(loggingInterceptor)
        .maxInboundMessageSize(256 * 1024 * 1024); // 256 MB

if (kusciaGrpcConfig.getProtocol() == KusciaProtocolEnum.NOTLS) {
    nettyChannelBuilder.usePlaintext();
} else {
    // TLS/mTLS：使用 client cert + key，信任 InsecureTrustManagerFactory
    SslContext sslContext = SslContextBuilder.forClient()
            .keyManager(cert, key)
            .trustManager(InsecureTrustManagerFactory.INSTANCE)
            .build();
    nettyChannelBuilder.sslContext(sslContext)
            .intercept(tokenAuthClientInterceptor)
            .useTransportSecurity();
}
```

#### 5.2.4 拦截器

- `TokenAuthClientInterceptor`：在 gRPC 请求头中注入 `Token: <token>`（常量 `KusciaAPIConstants.TOKEN_HEADER`）。
- `KusciaGrpcLoggingInterceptor`：记录方法名、请求/响应、耗时、取消事件。

#### 5.2.5 生命周期监听

- `KusciaRegisterListener`：收到 `RegisterKusciaEvent` 后：
  - CENTER/EDGE：启动 `JobManager.startSync(domainId)`。
  - AUTONOMY：加入 P2P 同步集合，更新数据源使用 data proxy。
  - 将配置序列化到 `./config/kuscia/{domainId}`。
- `KusciaUnRegisterListener`：收到 `UnRegisterKusciaEvent` 后移除同步集合并删除序列化文件。

### 5.3 统一适配器：KusciaGrpcClientAdapter

`KusciaGrpcClientAdapter` 是一个 Spring `@Service`，实现所有 Kuscia 服务接口：

```java
@Service
public class KusciaGrpcClientAdapter implements
        DomainService, DomainRouteService, DomainDataService, DomainDataSourceService,
        DomainDataGrantService, HealthService, KusciaJobService, ServingService, CertificateService {

    @Resource
    private DynamicKusciaChannelProvider dynamicKusciaChannelProvider;

    @Override
    public Domaindata.CreateDomainDataResponse createDomainData(Domaindata.CreateDomainDataRequest request) {
        return dynamicKusciaChannelProvider
                .currentStub(DomainDataServiceGrpc.DomainDataServiceBlockingStub.class)
                .createDomainData(request);
    }

    @Override
    public Domaindata.CreateDomainDataResponse createDomainData(Domaindata.CreateDomainDataRequest request, String domainId) {
        return dynamicKusciaChannelProvider
                .createStub(domainId, DomainDataServiceGrpc.DomainDataServiceBlockingStub.class)
                .createDomainData(request);
    }
}
```

所有 Manager 都注入 `KusciaGrpcClientAdapter`，通过它发起 Kuscia 调用。


### 5.4 调用的 Kuscia API

| Kuscia 服务 | 接口文件 | 主要操作 |
|---|---|---|
| DomainService | `service/DomainService.java` | `createDomain`, `updateDomain`, `deleteDomain`, `queryDomain`, `batchQueryDomain` |
| DomainDataService | `service/DomainDataService.java` | `createDomainData`, `updateDomainData`, `deleteDomainData`, `queryDomainData`, `batchQueryDomainData`, `listDomainData` |
| DomainDataSourceService | `service/DomainDataSourceService.java` | `create/update/delete/query/batchQuery/listDomainDataSource` |
| DomainDataGrantService | `service/DomainDataGrantService.java` | `create/update/delete/query/batchQueryDomainDataGrant` |
| DomainRouteService | `service/DomainRouteService.java` | `createDomainRoute`, `deleteDomainRoute`, `queryDomainRoute`, `batchQueryDomainRouteStatus` |
| KusciaJobService | `service/KusciaJobService.java` | `createJob`, `queryJob`, `batchQueryJobStatus`, `deleteJob`, `stopJob`, `watchJob`, `approveJob`, `suspendJob`, `restartJob`, `cancelJob` |
| ServingService | `service/ServingService.java` | `create/update/delete/query/batchQueryServingStatus` |
| HealthService | `service/HealthService.java` | `healthZ` |
| CertificateService | `service/CertificateService.java` | `generateKeyCerts` |

对应 protobuf 定义位于 `proto/kuscia/proto/api/v1alpha1/kusciaapi/`：

- `domain.proto`
- `domaindata.proto`
- `domaindatasource.proto`
- `domaindatagrant.proto`
- `domain_route.proto`
- `job.proto`
- `serving.proto`
- `health.proto`
- `certificate.proto`
- `common.proto`
- `error_code.proto`

`client-java-kusciaapi/pom.xml` 通过 `protobuf-maven-plugin` 从 `../../proto` 生成 Java 代码。

### 5.5 SecretPad 概念到 Kuscia 资源的映射

| SecretPad 概念 | Kuscia 概念 | 说明 |
|---|---|---|
| 节点（Node） | Domain | `NodeDO.nodeId` ↔ `Domain.domainId` |
| 节点实例/Pod 状态 | `Domain.nodeStatuses` | Ready/NotReady 由 `NodeStatus.status` 推导 |
| 节点部署 Token | `Domain.deployTokenStatuses` | Kuscia 生成，存入 `NodeDO.token` |
| 节点路由 | DomainRoute | source/destination domains、token 认证、endpoint host/port |
| 数据表（Datatable） | DomainData | `DatatableDTO.datatableId` ↔ `DomainData.domaindataId`，type 为 `table` |
| 数据源（Datasource） | DomainDataSource | OSS/MySQL/ODPS/LocalFS 等 |
| 数据授权/项目共享 | DomainDataGrant | 把某条 `DomainData` 授权给另一个 domain 读取 |
| 项目任务/图执行 | Job | `ProjectJobDO.jobId` ↔ `Job.jobId`，任务映射为 `Job.Task` |
| TEE 节点 | 特殊 domain id（默认 `tee`） | 用于 TEE 推送/授权任务 |
| 模型服务 | Serving | 由 `KusciaServingManager` 管理 |

### 5.6 关键操作中的 Kuscia 调用流程

#### 5.6.1 创建节点

```java
// NodeManager.createNode
DomainOuterClass.CreateDomainRequest request = DomainOuterClass.CreateDomainRequest.newBuilder()
    .setDomainId(nodeId)
    .setAuthCenter(DomainOuterClass.AuthCenter.newBuilder()
        .setAuthenticationType("Token")
        .setTokenGenMethod("UID-RSA-GEN")
        .build())
    .build();
kusciaGrpcClientAdapter.createDomain(request);
```

#### 5.6.2 创建节点路由

```java
// NodeRouteManager.createNodeRouteInKuscia
DomainRoute.CreateDomainRouteRequest createDomainRouteRequest =
    DomainRoute.CreateDomainRouteRequest.newBuilder()
        .setAuthenticationType("Token")
        .setTokenConfig(tokenConfig)   // tokenGenMethod = "RSA-GEN"
        .setDestination(dstNode.getNodeId())
        .setEndpoint(routeEndpoint)    // host + http/https port
        .setSource(srcNode.getNodeId())
        .build();
kusciaGrpcClientAdapter.createDomainRoute(request); // AUTONOMY 模式会指定 channelNodeId
```

#### 5.6.3 创建 Kuscia Job（图执行）

```java
// JobSubmittedHandler
Job.CreateJobRequest request = jobConverter.converter(job);
jobManager.createJob(request);

// KusciaJobConverter.converter
Job.CreateJobRequest.newBuilder()
    .setJobId(job.getJobId())
    .setInitiator(initiator)
    .setMaxParallelism(job.getMaxParallelism())
    .addAllTasks(jobTasks)
    .build();

// JobManager.createJob
if (PlatformTypeEnum.AUTONOMY.equals(getPlaformType())) {
    response = kusciaGrpcClientAdapter.createJob(request, request.getInitiator());
} else {
    response = kusciaGrpcClientAdapter.createJob(request);
}
```

#### 5.6.4 监听任务状态

```java
// JobManager.startSync
JobServiceGrpc.JobServiceStub jobServiceAsyncStub =
    dynamicKusciaChannelProvider.createStub(nodeId, JobServiceGrpc.JobServiceStub.class);

jobServiceAsyncStub.watchJob(
    Job.WatchJobRequest.newBuilder().build(),
    new StreamObserver<Job.WatchJobEventResponse>() {
        @Override public void onNext(Job.WatchJobEventResponse response) {
            syncJob(response); // 更新 ProjectJobDO / ProjectTaskDO，创建结果授权
        }
        // ... onError / onCompleted
    });
```

---

## 6. DataMesh 集成详解

### 6.1 DataMesh 与 Kuscia 的关系

Kuscia DataMesh 是 Kuscia 内部的数据访问层，为引擎（SecretFlow、SCQL、TEE 等）提供统一的数据读写入口。SecretPad **没有独立的 DataMesh 客户端**，它通过 Kuscia 的以下 gRPC 服务间接操作 DataMesh：

- `DomainDataService`：管理数据对象（DomainData）。
- `DomainDataSourceService`：管理数据源（DomainDataSource）。
- `DomainDataGrantService`：管理跨 domain 的数据授权（DomainDataGrant）。

### 6.2 DataMesh API

#### 6.2.1 DomainDataService

Proto：`proto/kuscia/proto/api/v1alpha1/kusciaapi/domaindata.proto`

```protobuf
service DomainDataService {
  rpc CreateDomainData(CreateDomainDataRequest) returns (CreateDomainDataResponse);
  rpc UpdateDomainData(UpdateDomainDataRequest) returns (UpdateDomainDataResponse);
  rpc DeleteDomainData(DeleteDomainDataRequest) returns (DeleteDomainDataResponse);
  rpc QueryDomainData(QueryDomainDataRequest) returns (QueryDomainDataResponse);
  rpc BatchQueryDomainData(BatchQueryDomainDataRequest) returns (BatchQueryDomainDataResponse);
  rpc ListDomainData(ListDomainDataRequest) returns (ListDomainDataResponse);
}
```

关键字段：

- `domaindata_id`：数据唯一 id。
- `name`：显示名称。
- `type`：`table` / `model` / `rule` / `report`。
- `relative_uri`：相对路径。
- `domain_id` / `datasource_id`：所属 domain 与数据源。
- `attributes`：扩展属性（`DatasourceType`、`DatasourceName`、`description`、`null_strs` 等）。
- `columns`：列 schema。
- `vendor` / `file_format`：厂商与文件格式。

#### 6.2.2 DomainDataSourceService

Proto：`proto/kuscia/proto/api/v1alpha1/kusciaapi/domaindatasource.proto`

```protobuf
service DomainDataSourceService {
  rpc CreateDomainDataSource(CreateDomainDataSourceRequest) returns (CreateDomainDataSourceResponse);
  rpc QueryDomainDataSource(QueryDomainDataSourceRequest) returns (QueryDomainDataSourceResponse);
  rpc UpdateDomainDataSource(UpdateDomainDataSourceRequest) returns (UpdateDomainDataSourceResponse);
  rpc DeleteDomainDataSource(DeleteDomainDataSourceRequest) returns (DeleteDomainDataSourceResponse);
  rpc BatchQueryDomainDataSource(...) returns (...);
  rpc ListDomainDataSource(ListDomainDataSourceRequest) returns (ListDomainDataSourceResponse);
}
```

支持的数据源类型：`localfs`、`oss`、`mysql`、`odps`。

#### 6.2.3 DomainDataGrantService

Proto：`proto/kuscia/proto/api/v1alpha1/kusciaapi/domaindatagrant.proto`

```protobuf
service DomainDataGrantService {
  rpc CreateDomainDataGrant(CreateDomainDataGrantRequest) returns (CreateDomainDataGrantResponse);
  rpc UpdateDomainDataGrant(UpdateDomainDataGrantRequest) returns (UpdateDomainDataGrantResponse);
  rpc DeleteDomainDataGrant(DeleteDomainDataGrantRequest) returns (DeleteDomainDataGrantResponse);
  rpc QueryDomainDataGrant(QueryDomainDataGrantRequest) returns (QueryDomainDataGrantResponse);
  rpc BatchQueryDomainDataGrant(...) returns (...);
  rpc ListDomainDataGrant(...) returns (...);
}
```

用于授权一个 domain 读取另一个 domain 的 `DomainData`。

### 6.3 数据上传、下载、查询流程

#### 6.3.1 数据上传

1. 前端 `POST /api/v1alpha1/data/upload`，multipart 上传 CSV。
2. `DataController` → `DataServiceImpl.upload(file, nodeId)`。
3. 服务把文件保存到本地磁盘：
   ```java
   String dirPath = storeDir + nodeId + FILE_SEPETATOR;
   file.transferTo(target);
   ```
4. 返回 `UploadDataResultVO`。
5. 前端再调用 `/api/v1alpha1/datatable/create` 注册数据表。
6. `DatatableManager` 构造 `CreateDomainDataRequest`：
   ```java
   Domaindata.CreateDomainDataRequest createDomainDataRequest =
       Domaindata.CreateDomainDataRequest.newBuilder()
           .setDomaindataId(domainDataId)
           .setDomainId(domainId)
           .setName(tableName)
           .setType("table")
           .setRelativeUri(realName)
           .setDatasourceId(datasourceName)
           .putAttributes("DatasourceType", datasourceType)
           .putAttributes("DatasourceName", datasourceName)
           .putAttributes("description", description)
           .putAttributes(DomainDataConstants.NULL_STRS, nullstrJson)
           .addAllColumns(...)
           .build();
   kusciaGrpcClientAdapter.createDomainData(createDomainDataRequest);
   ```

> 上传流程 = 本地文件存储 + DataMesh `CreateDomainData` 注册。

#### 6.3.2 数据下载

1. 前端 `POST /api/v1alpha1/data/download`，携带 `nodeId` 与 `domainDataId`。
2. `DataController` → `DataServiceImpl.download(DownloadDataRequest)`。
3. `NodeManager.getNodeResult` 调用 Kuscia `QueryDomainData` 获取 `relative_uri`：
   ```java
   Domaindata.QueryDomainDataRequest request = Domaindata.QueryDomainDataRequest.newBuilder()
       .setData(Domaindata.QueryDomainDataRequestData.newBuilder()
           .setDomainId(nodeId)
           .setDomaindataId(domainDataId)
           .build())
       .build();
   Domaindata.QueryDomainDataResponse response = PlatformTypeEnum.AUTONOMY.equals(...)
       ? kusciaGrpcClientAdapter.queryDomainData(request, nodeId)
       : kusciaGrpcClientAdapter.queryDomainData(request);
   ```
4. 服务根据 `relative_uri` 读取本地文件：`filePath = storeDir + nodeId + "/" + relativeUri`。
5. 返回文件流给前端。

> 下载流程 = DataMesh `QueryDomainData` 取回 `relative_uri` + 本地文件系统读取。

#### 6.3.3 数据表查询

`DatatableManager` 提供三类查询：

- 单表：`queryDomainData(...)` → `QueryDomainData`
- 批量：`batchQueryDomainData(...)` → `BatchQueryDomainData`
- 列表：`listDomainData(...)` → `ListDomainData`

示例：

```java
Domaindata.ListDomainDataRequestData.Builder builder =
    Domaindata.ListDomainDataRequestData.newBuilder()
        .setDomaindataType(DATA_TYPE_TABLE)
        .setDomainId(nodeId);

Domaindata.ListDomainDataResponse responses = kusciaGrpcClientAdapter.listDomainData(
    Domaindata.ListDomainDataRequest.newBuilder().setData(builder.build()).build(),
    PlatformTypeEnum.CENTER.equals(...) ? localNodeId : nodeId);
```

查询结果通过 `DatatableDTO.fromDomainData(...)` 映射为 SecretPad 的 DTO。

#### 6.3.4 数据源创建/查询/删除

`DatasourceManager` 负责把 SecretPad 数据源转换为 Kuscia `DomainDataSource`。

具体类型由 `secretpad-service/.../service/handler/datasource/` 下的 Handler 处理：

- `OssKusciaControlDatasourceHandler`
- `MysqlKusciaControlDatasourceHandler`
- `OdpsKusciaControlDatasourceHandler`

以 OSS 为例：

```java
Domaindatasource.CreateDomainDataSourceRequest createDomainDataSourceRequest =
    Domaindatasource.CreateDomainDataSourceRequest.newBuilder()
        .setDomainId(nodeId)
        .setDatasourceId(datasourceId)
        .setType(DataSourceTypeEnum.OSS.name().toLowerCase(Locale.ROOT))
        .setName(createDatasourceRequest.getName())
        .setAccessDirectly(Boolean.FALSE)
        .setInfo(Domaindatasource.DataSourceInfo.newBuilder().setOss(builder.build()))
        .build();
kusciaGrpcClientAdapter.createDomainDataSource(createDomainDataSourceRequest, nodeId);
```

#### 6.3.5 跨节点数据授权

`DatatableGrantManager` 在以下场景被调用：

- 添加数据表到项目时，给项目内其他参与方授权。
- TEE 推送/拉取时，给 TEE 节点授权。
- 任务结果产出后，对 union / sample-filter 等输出创建授权。

示例：

```java
Domaindatagrant.CreateDomainDataGrantRequest.Builder builder =
    Domaindatagrant.CreateDomainDataGrantRequest.newBuilder()
        .setGrantDomain(grantNodeId)
        .setDomaindataId(domainDataId)
        .setDomainId(nodeId);
if (StringUtils.isNotBlank(domainDataGrantId)) {
    builder.setDomaindatagrantId(domainDataGrantId);
}
Domaindatagrant.CreateDomainDataGrantResponse response =
    PlatformTypeEnum.AUTONOMY.equals(...)
        ? kusciaGrpcClientAdapter.createDomainDataGrant(builder.build(), nodeId)
        : kusciaGrpcClientAdapter.createDomainDataGrant(builder.build());
```

### 6.4 SecretPad 数据概念与 DataMesh 映射

| SecretPad 概念 | DataMesh / Kuscia 概念 | 映射说明 |
|---|---|---|
| 节点 | Domain（`domain_id`） | 每个节点对应一个 Kuscia domain |
| 数据表 | DomainData（`domaindata_id`） | 带 schema、类型、relative URI、datasource 的注册表 |
| 数据源 | DomainDataSource（`datasource_id`） | 存储后端：`default-data-source`、`oss-...`、`mysql-...`、`odps-...` |
| 数据表授权 / 项目共享 | DomainDataGrant（`domaindatagrant_id`） | 授予其他 domain 读取权限 |
| 项目任务输入/输出 | DataMesh URI（`dm://...`） | 在 `TaskConfig` 中作为 `sfInputIds` / `sfOutputUris` |
| TEE 推送/拉取 | TEE 数据管理任务 | 使用 `dm://input?id=` / `dm://output?datasource_id=` |

相关常量：

```java
// DomainDatasourceConstants
public final static String DEFAULT_DATASOURCE = "default-data-source";
public final static String DEFAULT_DATASOURCE_TYPE = "localfs";
public static final String DEFAULT_OSS_DATASOURCE_TYPE = "OSS";
public static final String DEFAULT_ODPS_DATASOURCE_TYPE = "ODPS";
public static final String DEFAULT_MYSQL_DATASOURCE_TYPE = "MYSQL";
```

相关 DTO 映射：

- `DatatableDTO`：从 `DomainData` 映射。
- `DatasourceDTO`：从 `DomainDataSource` 映射。
- `DatatableGrantDTO`：从 `DomainDataGrant` 映射。

### 6.5 DataMesh URI 格式

在任务配置中，SecretPad 使用 `dm://` 协议引用 DataMesh 数据。

`JobConverter` 中的构造方式：

```java
String DM_INPUT  = "dm://input/?";
String DM_OUTPUT = "dm://output/?";
String DEFAULT_DS = "default-data-source";

default String buildDmInputUrl(String dataTableId) {
    return DmVO.builder().id(dataTableId).build().buildDmInputUrl();
    // 结果类似 dm://input/?id={dataTableId}
}

default String buildDmOutputUrl(String dataTableId) {
    return DmVO.builder().id(dataTableId).uri(dataTableId).datasource_id(DEFAULT_DS).build()
            .buildDmOutputUrl();
    // 结果类似 dm://output/?id={dataTableId}&datasource_id=default-data-source&uri={dataTableId}
}
```

TEE 任务中使用的常量：

```java
// TeeJobConstants
public static final String DATA_REF = "dm://input?id=";
public static final String OUTPUT_DATASOURCE_REF = "dm://output?datasource_id=";
public static final String OUTPUT_ID_REF = "&id=";
public static final String OUTPUT_RELATIVE_URI_REF = "&uri=";
```

完整形式也可写作：

```
datamesh:///{relative_path}?domaindata_id={domaindata_id}&datasource_id={datasource_id}&partition_spec={partition_spec}
```

但后端 `JobConverter` 实际生成的是更简短的 `dm://` 形式。

### 6.6 引擎侧 DataMesh 端点配置

SecretPad Java 代码不直接调用 DataMesh HTTP 接口，而是为引擎 Pod 配置 DataMesh 接入参数。

SCQL 引擎模板 `scripts/templates/sf-scql.yaml`：

```yaml
engineConf: |-
  --datasource_router=kusciadatamesh
  --kuscia_datamesh_endpoint=datamesh:8071
  --kuscia_datamesh_client_cert_path={{.CLIENT_CERT_FILE}}
  --kuscia_datamesh_client_key_path={{.CLIENT_PRIVATE_KEY_FILE}}
  --kuscia_datamesh_cacert_path={{.TRUSTED_CA_FILE}}
```

TEE 引擎模板 `scripts/templates/tee-image.yaml`：

```yaml
- "./main ... -data_mesh_endpoint datamesh:8071 --enable_capsule_tls=false"
```

也就是说，DataMesh 的 HTTP/gRPC 端点 `datamesh:8071` 主要供引擎内部消费，SecretPad 只负责把数据注册到 DataMesh 并在任务配置中生成正确的 `dm://` 引用。


---

## 7. 附录

### 7.1 关键文件索引

#### 前端

| 作用 | 路径 |
|---|---|
| 全局请求拦截器 | `frontend-src/apps/platform/src/app.ts` |
| UmiJS 配置与代理 | `frontend-src/apps/platform/config/config.ts` |
| OpenAPI 生成配置 | `frontend-src/apps/platform/config/openapi.config.js` |
| 自动生成的 API 客户端 | `frontend-src/apps/platform/src/services/secretpad/` |
| 路由守卫 | `frontend-src/apps/platform/src/wrappers/` |

#### 后端 Controller

| 作用 | 路径 |
|---|---|
| 所有 REST Controller | `secretpad-web/src/main/java/org/secretflow/secretpad/web/controller/` |
| 登录拦截器 | `secretpad-web/src/main/java/org/secretflow/secretpad/web/interceptor/LoginInterceptor.java` |
| 接口鉴权切面 | `secretpad-web/src/main/java/org/secretflow/secretpad/web/aop/InterfaceResourceAspect.java` |
| 数据鉴权切面 | `secretpad-web/src/main/java/org/secretflow/secretpad/web/aop/DataResourceAspect.java` |
| 拦截器注册 | `secretpad-web/src/main/java/org/secretflow/secretpad/web/configuration/LoginConfiguration.java` |

#### 后端 Service / Manager

| 作用 | 路径 |
|---|---|
| 通用响应包装 | `secretpad-service/src/main/java/org/secretflow/secretpad/service/model/common/SecretPadResponse.java` |
| 用户上下文 | `secretpad-common/src/main/java/org/secretflow/secretpad/common/util/UserContext.java` |
| 节点管理器 | `secretpad-manager/src/main/java/org/secretflow/secretpad/manager/integration/node/NodeManager.java` |
| 路由管理器 | `secretpad-manager/src/main/java/org/secretflow/secretpad/manager/integration/noderoute/NodeRouteManager.java` |
| 数据管理器 | `secretpad-manager/src/main/java/org/secretflow/secretpad/manager/integration/data/DataManager.java` |
| 数据表管理器 | `secretpad-manager/src/main/java/org/secretflow/secretpad/manager/integration/datatable/DatatableManager.java` |
| 数据源管理器 | `secretpad-manager/src/main/java/org/secretflow/secretpad/manager/integration/datasource/DatasourceManager.java` |
| 数据授权管理器 | `secretpad-manager/src/main/java/org/secretflow/secretpad/manager/integration/datatablegrant/DatatableGrantManager.java` |
| 任务管理器 | `secretpad-manager/src/main/java/org/secretflow/secretpad/manager/integration/job/JobManager.java` |
| 模型服务管理器 | `secretpad-manager/src/main/java/org/secretflow/secretpad/manager/integration/serving/impl/KusciaServingManager.java` |
| Manager Bean 装配 | `secretpad-manager/src/main/java/org/secretflow/secretpad/manager/configuration/ManagerConfiguration.java` |
| 项目服务 | `secretpad-service/src/main/java/org/secretflow/secretpad/service/impl/ProjectServiceImpl.java` |
| 图服务 | `secretpad-service/src/main/java/org/secretflow/secretpad/service/impl/GraphServiceImpl.java` |
| 数据服务 | `secretpad-service/src/main/java/org/secretflow/secretpad/service/impl/DataServiceImpl.java` |
| 数据表服务 | `secretpad-service/src/main/java/org/secretflow/secretpad/service/impl/DatatableServiceImpl.java` |
| 任务转换器 | `secretpad-service/src/main/java/org/secretflow/secretpad/service/graph/converter/KusciaJobConverter.java` |
| TEE 转换器 | `secretpad-service/src/main/java/org/secretflow/secretpad/service/graph/converter/KusciaTeeDataManagerConverter.java` |
| DM URI 构造器 | `secretpad-service/src/main/java/org/secretflow/secretpad/service/graph/converter/JobConverter.java` |

#### Kuscia gRPC 客户端

| 作用 | 路径 |
|---|---|
| 统一适配器 | `secretpad-api/client-java-kusciaapi/src/main/java/org/secretflow/secretpad/kuscia/v1alpha1/service/impl/KusciaGrpcClientAdapter.java` |
| 动态通道提供者 | `secretpad-api/client-java-kusciaapi/src/main/java/org/secretflow/secretpad/kuscia/v1alpha1/DynamicKusciaChannelProvider.java` |
| Netty 通道工厂 | `secretpad-api/client-java-kusciaapi/src/main/java/org/secretflow/secretpad/kuscia/v1alpha1/factory/impl/GrpcKusciaApiChannelFactory.java` |
| Token 认证拦截器 | `secretpad-api/client-java-kusciaapi/src/main/java/org/secretflow/secretpad/kuscia/v1alpha1/interceptor/TokenAuthClientInterceptor.java` |
| gRPC 日志拦截器 | `secretpad-api/client-java-kusciaapi/src/main/java/org/secretflow/secretpad/kuscia/v1alpha1/interceptor/KusciaGrpcLoggingInterceptor.java` |
| 单节点配置 | `secretpad-api/client-java-kusciaapi/src/main/java/org/secretflow/secretpad/kuscia/v1alpha1/model/KusciaGrpcConfig.java` |
| 多节点配置 | `secretpad-api/client-java-kusciaapi/src/main/java/org/secretflow/secretpad/kuscia/v1alpha1/model/DynamicKusciaGrpcConfig.java` |
| Kuscia 注册监听器 | `secretpad-service/src/main/java/org/secretflow/secretpad/service/listener/KusciaRegisterListener.java` |
| Kuscia 注销监听器 | `secretpad-service/src/main/java/org/secretflow/secretpad/service/listener/KusciaUnRegisterListener.java` |
| Mock gRPC 服务 | `secretpad-api/client-java-kusciaapi/src/main/java/org/secretflow/secretpad/kuscia/v1alpha1/mock/MockKusciaGrpcServer.java` |

#### Protobuf 与配置

| 作用 | 路径 |
|---|---|
| Kuscia API proto | `proto/kuscia/proto/api/v1alpha1/kusciaapi/*.proto` |
| 主配置 | `config/application.yaml` |
| 开发配置 | `config/application-dev.yaml` |
| EDGE 配置 | `config/application-edge.yaml` |
| P2P 配置 | `config/application-p2p.yaml` |
| 数据常量 | `secretpad-common/src/main/java/org/secretflow/secretpad/common/constant/DomainDataConstants.java` |
| 数据源常量 | `secretpad-common/src/main/java/org/secretflow/secretpad/common/constant/DomainDatasourceConstants.java` |

### 7.2 常用调试与测试入口

- 单元测试可使用 `MockKusciaGrpcServer` 模拟 Kuscia 的 DomainData / DomainDataSource / DomainDataGrant / Job 等服务。
- 本地开发时，前端在 `frontend-src/apps/platform/.env` 中配置 `PROXY_URL=https://backend-server`，UmiJS 会把 `/api/*` 代理到后端。
- 后端启动后，可查看 `http://<host>:<port>/swagger-ui.html`（如启用 SpringDoc）或 `/v3/api-docs` 获取 OpenAPI 定义，用于重新生成前端客户端。

### 7.3 总结

- SecretPad 前端通过 `umi-request` 调用后端 REST API，API 客户端由 `@umijs/openapi` 自动生成。
- 后端 Controller 统一返回 `SecretPadResponse<T>`，通过 `LoginInterceptor` 与 AOP 切面完成认证鉴权。
- 后端 Service 负责业务编排，Manager 负责把业务对象转换为 Kuscia protobuf 请求。
- 后端与 Kuscia 之间所有通信均为 gRPC，通道按 domain 缓存，支持 TLS/mTLS/NOTLS 与 Token 认证。
- DataMesh 是 Kuscia 的数据访问层，SecretPad 通过 `DomainDataService`、`DomainDataSourceService`、`DomainDataGrantService` 管理数据注册、数据源与跨域授权。
- 数据上传/下载采用“本地文件 + DataMesh 元数据”的混合模式；任务执行中通过 `dm://` URI 引用 DataMesh 数据。
