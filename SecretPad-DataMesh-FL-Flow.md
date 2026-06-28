# SecretPad / Kuscia / DataMesh / SecretFlow 跨项目数据流全景文档

> 本文档旨在把分散在 4 个项目中的“新建 DataMesh 数据源”与“新建并执行联邦学习任务”两条主链路串起来，从 SecretPad 前端页面开始，一直到 SecretFlow 容器真正执行组件，覆盖接口、数据结构与关键代码位置。

---

## 1. 背景与范围

| 项目 | 职责 | 技术栈 | 本链路角色 |
|---|---|---|---|
| **secretpad/frontend-src** | SecretPad 前端 | React + TypeScript + umi-request | 用户交互入口、DAG 画布、表单提交 |
| **secretpad** | SecretPad 后端 | Java / Spring Boot / gRPC | 业务编排、转译、调用 KusciaAPI |
| **kuscia** | 隐私计算基础设施 | Go / Kubernetes CRD / Envoy | 资源管理、任务调度、DataMesh 服务 |
| **secretflow** | 联邦学习引擎 | Python / Ray / Arrow Flight | 组件执行、数据读写、模型训练 |

本文档聚焦两条主线：

1. **DataMesh 数据源创建**：前端注册数据源 → SecretPad 后端 → KusciaAPI → DataMesh 元数据服务 → 生成 `DomainDataSource` CRD。
2. **联邦学习任务创建与执行**：前端创建项目/训练流/提交运行 → SecretPad 后端 → KusciaAPI 创建 `KusciaJob` → Kuscia 调度 `KusciaTask` → 拉起 SecretFlow Pod → SecretFlow 读写 DataMesh → 结果回写。

---

## 2. 整体架构与调用关系

```text
┌─────────────────────────────────────────────────────────────────────────────┐
│                           SecretPad 前端 (React)                            │
│  页面: data-source / dag / project-list / component-tree / toolbar          │
│  请求库: umi-request (拦截器加 User-Token / Trace-Id)                       │
└─────────────────────────────────────────────────────────────────────────────┘
                                    │ REST /api/v1alpha1/...
                                    ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                         SecretPad 后端 (Java/Spring)                        │
│  Controller: DataSourceController / ProjectController / GraphController      │
│  Service: DatasourceServiceImpl / ProjectServiceImpl / GraphServiceImpl      │
│  Kuscia 调用: KusciaGrpcClientAdapter (gRPC)                                │
└─────────────────────────────────────────────────────────────────────────────┘
                                    │ gRPC (8083) / HTTP (8082)
                                    ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                        KusciaAPI (Go)                                       │
│  Service: JobService / DomainDataService / DomainDataSourceService           │
│  鉴权: Token / Kuscia-Source + Casbin                                       │
│  输出: 创建 KusciaJob / DomainData / DomainDataSource CRD                    │
└─────────────────────────────────────────────────────────────────────────────┘
                                    │ 内部 Controller
                                    ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                   Kuscia Controllers + DataMesh (Go)                        │
│  KusciaJob Controller → KusciaTask Controller → Pod/Service/ConfigMap        │
│  DataMesh MetaServer (HTTP 8070 / Flight 8071)                              │
│  元数据: DomainData / DomainDataSource / DomainDataGrant                     │
└─────────────────────────────────────────────────────────────────────────────┘
                                    │ 容器启动 / Arrow Flight
                                    ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                      SecretFlow (Python / Ray)                              │
│  入口: secretflow.kuscia.entry                                              │
│  解析: KusciaTaskConfig / SFClusterConfig                                   │
│  读写: DataMesh Connector (Arrow Flight / DataProxy)                        │
│  执行: component.core.entry.comp_eval                                       │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 3. 场景一：新建 DataMesh 数据源完整流程

### 3.1 SecretPad 前端

#### 3.1.1 页面与组件

| 作用 | 文件路径 |
|---|---|
| 数据源列表 + 注册抽屉 | `secretpad/frontend-src/apps/platform/src/modules/data-source-list/index.tsx` |
| 注册数据源表单抽屉 | `secretpad/frontend-src/apps/platform/src/modules/data-source-list/components/create-data-source/index.tsx` |
| 数据源 Service | `secretpad/frontend-src/apps/platform/src/modules/data-source-list/data-source-list.service.ts` |
| 页面路由入口 | `secretpad/frontend-src/apps/platform/src/pages/data-source.tsx` |

路由配置：`secretpad/frontend-src/apps/platform/config/routes.ts`

```ts
{ path: '/data-source', component: 'data-source' },
{ path: '/data-source/:id', component: 'data-source-detail' },
```

#### 3.1.2 表单字段

`CreateDataSourceModal` 按类型渲染动态表单：

- `type`：`OSS / ODPS / MYSQL / HTTP`
- `name`：显示名称
- `dataSourceInfo`：类型相关连接信息
  - **OSS**：`endpoint`、`ak`、`sk`、`virtualhost`、`bucket`、`prefix`
  - **ODPS**：`endpoint`、`accessId`、`accessKey`、`project`
  - **MYSQL**：`endpoint`、`user`、`password`、`database`
- `nodeIds`：节点连接配置（P2P/Autonomy 模式可多个）

提交时调用：

```ts
const { status, data } = await dataSourceService.addDataSource({
  ...value,
  ownerId: ownerId,
});
```

#### 3.1.3 API 封装

`secretpad/frontend-src/apps/platform/src/services/secretpad/DataSourceController.ts`：

```ts
export async function create(body?: API.CreateDatasourceRequest) {
  return request<API.SecretPadResponse_CreateDatasourceVO_>(
    '/api/v1alpha1/datasource/create',
    { method: 'POST', data: body },
  );
}
```

请求库是 **umi-request**，封装入口：`secretpad/frontend-src/apps/platform/src/app.ts`

```ts
import request from 'umi-request';

request.interceptors.request.use((url, options) => {
  const traceId = uuidv4();
  const token = localStorage.getItem('User-Token') || '';
  return {
    url,
    options: {
      ...options,
      mode: 'cors',
      credentials: 'include',
      headers: {
        'Content-Type': 'application/json',
        'User-Token': token,
        'Trace-Id': traceId,
      },
    },
  };
});
```

#### 3.1.4 关键 TypeScript 类型

`secretpad/frontend-src/apps/platform/src/services/secretpad/typings.d.ts`：

```ts
interface CreateDatasourceRequest {
  ownerId?: string;
  nodeIds?: Array<string>;
  type?: string;
  name?: string;
  dataSourceInfo: DataSourceInfo;   // Record<string, any>
}

interface DatasourceDetailAggregateVO {
  nodes?: Array<DataSourceRelatedNode>;
  datasourceId?: string;
  name?: string;
  type?: string;
  status?: string;
  info?: DataSourceInfo;
}

interface DataSourceRelatedNode {
  nodeId?: string;
  nodeName?: string;
  status?: string;
}
```

---

### 3.2 SecretPad 后端

#### 3.2.1 Controller 入口

- **文件**：`secretpad/secretpad-web/src/main/java/org/secretflow/secretpad/web/controller/DataSourceController.java`
- **接口**：`POST /api/v1alpha1/datasource/create`

```java
@PostMapping("/create")
public SecretPadResponse<CreateDatasourceVO> create(
        @RequestBody @Valid CreateDatasourceRequest createDatasourceRequest)
```

#### 3.2.2 Service 调用链

```text
DataSourceController.create
  └─ DatasourceServiceImpl.createDatasource(CreateDatasourceRequest)
       ├─ verifyRate()
       ├─ verifyNodes()          // 自治模式下校验 nodeIds 是否属于当前 inst
       └─ datasourceHandlerMap.get(type).createDatasource(request)
            ├─ OSS  → OssKusciaControlDatasourceHandler
            ├─ ODPS → OdpsKusciaControlDatasourceHandler
            ├─ MYSQL→ MysqlKusciaControlDatasourceHandler
            └─ HTTP → HttpDatasourceHandler（仅本地 mock，不调用 Kuscia）
```

#### 3.2.3 DTO / VO

- 请求 DTO：`secretpad/secretpad-service/.../service/model/datasource/CreateDatasourceRequest.java`
  - `dataSourceInfo` 使用 `@JsonTypeInfo` + `@JsonSubTypes` 多态反序列化：
    - `OssDatasourceInfo`
    - `OdpsDatasourceInfo`
    - `MysqlDatasourceInfo`
- 响应 VO：`CreateDatasourceVO`（`datasourceId` + 失败节点映射）

#### 3.2.4 调用 Kuscia 创建 DomainDataSource

`OssKusciaControlDatasourceHandler.createDatasourceInKuscia(...)`：

```java
Domaindatasource.CreateDomainDataSourceRequest.newBuilder()
    .setDomainId(nodeId)
    .setDatasourceId(datasourceId)
    .setType("oss")              // oss / odps / mysql
    .setName(name)
    .setAccessDirectly(Boolean.FALSE)
    .setInfo(Domaindatasource.DataSourceInfo.newBuilder().setOss(...))
    .build();
```

通过 `KusciaGrpcClientAdapter.createDomainDataSource(request, nodeId)` 对每个选中的节点异步创建。

#### 3.2.5 数据库实体

- **数据源类型枚举**：`secretpad/secretpad-common/.../enums/DataSourceTypeEnum.java`
- **项目-图-节点数据源配置**：`secretpad/secretpad-persistence/.../entity/ProjectGraphDomainDatasourceDO.java`
  - 表：`project_graph_domain_datasource`
  - 复合主键：`projectId + graphId + domainId`
  - 字段：`dataSourceId`、`dataSourceName`、`editEnable`

---

### 3.3 KusciaAPI 与 DataMesh

#### 3.3.1 KusciaAPI 入口

| 文件 | 说明 |
|---|---|
| `kuscia/pkg/kusciaapi/bean/http_server_bean.go` | 启动外部/内部 HTTP Server |
| `kuscia/pkg/kusciaapi/bean/grpc_server_bean.go` | 启动 gRPC Server |
| `kuscia/pkg/kusciaapi/config/kusciaapi_config.go` | 默认端口：HTTP `8082`、gRPC `8083`、内部 HTTP `8092` |

**外部 HTTP 路由**：

```text
POST /api/v1/domaindatasource/create
POST /api/v1/domaindatasource/update
POST /api/v1/domaindatasource/delete
POST /api/v1/domaindatasource/query
POST /api/v1/domaindatasource/batchQuery
POST /api/v1/domaindatasource/list
```

**gRPC 服务**：`kusciaapi.RegisterDomainDataSourceServiceServer(...)`

鉴权：外部 HTTP 使用 `interceptor.HTTPTokenAuthInterceptor` + `HTTPSetMasterRoleInterceptor`。

#### 3.3.2 KusciaAPI DomainDataSource 服务

- **文件**：`kuscia/pkg/kusciaapi/service/domaindata_source.go`
- 负责创建 `DomainDataSource` CRD。
- 敏感信息（`Data["encryptedInfo"]`）使用域公钥 **RSA-OAEP** 加密，查询时用域私钥解密。

#### 3.3.3 DataMesh 元数据服务

DataMesh 运行在 **每个 Lite/Autonomy 域** 上：

| 目录 | 职责 |
|---|---|
| `kuscia/pkg/datamesh/metaserver/` | DomainData / DomainDataSource / DomainDataGrant 元信息管理 |
| `kuscia/pkg/datamesh/dataserver/` | Arrow Flight 数据面 |

端口：

| 协议 | 默认端口 | 说明 |
|---|---|---|
| HTTP | `8070` | 元数据 CRUD |
| gRPC / Apache Arrow Flight | `8071` | 元数据 + 数据流 |

HTTP 路由：

```text
api/v1/datamesh/domaindata       {create, delete, query, update}
api/v1/datamesh/domaindatasource {query}
api/v1/datamesh/domaindatagrant  {create, delete, query, update}
```

#### 3.3.4 CRD 定义

| CRD | Go 类型文件 |
|---|---|
| `DomainDataSource` | `kuscia/pkg/crd/apis/kuscia/v1alpha1/domaindatasource_types.go` |
| `DomainData` | `kuscia/pkg/crd/apis/kuscia/v1alpha1/domaindata_types.go` |
| `DomainDataGrant` | `kuscia/pkg/crd/apis/kuscia/v1alpha1/domaindatagrant_types.go` |

`DomainDataSourceSpec` 关键字段：

```go
type DomainDataSourceSpec struct {
    URI            string
    Name           string
    Data           map[string]string   // 加密后的连接信息
    Type           string               // localfs/oss/mysql/postgresql/odps/hive/kingbase/dameng
    InfoKey        string
    AccessDirectly bool
}
```

#### 3.3.5 Proto 定义

KusciaAPI：

```protobuf
// kuscia/proto/api/v1alpha1/kusciaapi/domaindatasource.proto
message CreateDomainDataSourceRequest {
  RequestHeader header = 1;
  string domain_id = 2;
  string datasource_id = 3;
  string type = 4;
  optional string name = 5;
  optional DataSourceInfo info = 6;
  optional bool access_directly = 8;
}

message DataSourceInfo {
  LocalDataSourceInfo localfs = 1;
  OssDataSourceInfo oss = 2;
  DatabaseDataSourceInfo database = 3;
  OdpsDataSourceInfo odps = 4;
}
```

---

### 3.4 数据源创建调用链总结

```text
前端 CreateDataSourceModal
  -> data-source-list.service.ts addDataSource
     -> DataSourceController.create
        -> POST /api/v1alpha1/datasource/create
           -> SecretPad DataSourceController
              -> DatasourceServiceImpl.createDatasource
                 -> OssKusciaControlDatasourceHandler (以 OSS 为例)
                    -> KusciaGrpcClientAdapter.createDomainDataSource
                       -> DomainDataSourceServiceGrpc.CreateDomainDataSource
                          -> KusciaAPI (pkg/kusciaapi/service/domaindata_source.go)
                             -> 创建 DomainDataSource CRD
                                -> DataMesh MetaServer 同步
                                   -> 本域可通过 127.0.0.1:8071 访问该数据源
```

---

## 4. 场景二：新建并执行联邦学习任务完整流程

### 4.1 SecretPad 前端

#### 4.1.1 项目创建

| 作用 | 文件路径 |
|---|---|
| 项目创建弹窗 | `secretpad/frontend-src/apps/platform/src/modules/create-project/create-project.view.tsx` |
| 项目创建 Service | `secretpad/frontend-src/apps/platform/src/modules/create-project/create-project.service.ts` |
| P2P 项目创建 | `secretpad/frontend-src/apps/platform/src/modules/create-project/p2p-create-project/p2p-create-project.view.tsx` |

表单字段：

- `projectName`：项目名称
- `description`：项目描述
- `computeMode`：计算模式（`MPC / TEE`）
- `templateId`：训练流模板（`blank / psi / risk / tee ...`）
- `nodes`：参与节点

调用链（非 P2P）：

```text
ProjectList/index.tsx
  -> CreateProjectModal
     -> CreateProjectService.createProject(value, showBlank)
        -> ProjectController.createProject        POST /api/v1alpha1/project/create
        -> CreateProjectService.addNodeToProject
           -> ProjectController.addProjectNode    POST /api/v1alpha1/project/node/add
        -> CreateProjectService.addInstToProject
           -> ProjectController.addProjectInst    POST /api/v1alpha1/project/inst/add
        -> CreateProjectService.addTablesToProject (模板非 blank 时)
           -> ProjectController.addProjectDatatable POST /api/v1alpha1/project/datatable/add
        -> DefaultPipelineService.createPipeline
           -> GraphController.createGraph           POST /api/v1alpha1/graph/create
           -> GraphController.fullUpdateGraph       POST /api/v1alpha1/graph/update
        -> history.push('/dag?projectId=...&mode=...')
```

#### 4.1.2 训练流 / Pipeline 创建

| 作用 | 文件路径 |
|---|---|
| 训练流创建按钮/模板选择 | `secretpad/frontend-src/apps/platform/src/modules/pipeline/pipeline-creation-view.tsx` |
| 训练流 Service | `secretpad/frontend-src/apps/platform/src/modules/pipeline/pipeline-service.ts` |
| 模板定义 | `secretpad/frontend-src/apps/platform/src/modules/pipeline/templates/*.ts` |

`DefaultPipelineService.createPipeline` 核心逻辑：

```ts
const { data, status } = await createGraph({
  projectId, name, templateId: templateType,
});
const { graphId: id } = data || {};
const { nodes, edges } = content(id);
await fullUpdateGraph({ projectId, graphId: id, nodes, edges });
```

#### 4.1.3 DAG 画布与组件拖拽

| 作用 | 文件路径 |
|---|---|
| DAG 页面 | `secretpad/frontend-src/apps/platform/src/pages/dag.tsx` |
| DAG 布局 | `secretpad/frontend-src/apps/platform/src/modules/layout/dag-layout/index.tsx` |
| 画布组件 | `secretpad/frontend-src/apps/platform/src/modules/main-dag/graph.tsx` |
| 工具栏 | `secretpad/frontend-src/apps/platform/src/modules/main-dag/toolbar.tsx` |
| 组件树 | `secretpad/frontend-src/apps/platform/src/modules/component-tree/component-tree-view.tsx` |
| 组件配置表单 | `secretpad/frontend-src/apps/platform/src/modules/component-config/config-form-view.tsx` |

组件拖拽调用链：

```text
ComponentTree (onMouseDown)
  -> ComponentTreeView.startDrag
     -> DefaultComponentTreeService.dragComponent
        -> onComponentDraggedEmitter.fire({ component, status, e })
           -> GraphService.onComponentDrag
              -> mainDag.graphManager.executeAction(ActionType.dragNode, ...)
```

组件配置保存：

```text
ConfigFormComponent.onSaveConfig
  -> DefaultComponentConfigService.saveComponentConfig
     -> GraphService.saveNodeConfig
        -> GraphController.updateGraphNode   POST /api/v1alpha1/graph/node/update
```

#### 4.1.4 数据选择

添加数据表（从数据源创建 datatable）：

```text
DataAddDrawer / AddDataSheetService
  -> DataSourceController.list           POST /api/v1alpha1/datasource/list
  -> DataSourceController.nodes          POST /api/v1alpha1/datasource/nodes
  -> DatatableController.createDataTable POST /api/v1alpha1/datatable/create
```

#### 4.1.5 任务提交

执行入口：`ToolbarComponent / ToolbarView.exec`

```text
runAll / runDown / runSingle
  -> mainDag.graphManager.executeAction(ActionType.runAll | runDown | runSingle)
     -> GraphRequestService.startRun
        -> GraphController.startGraph    POST /api/v1alpha1/graph/start
           body: { projectId, graphId, nodes: componentIds[], breakpoint? }
```

状态轮询：

```text
GraphRequestService.queryStatus
  -> GraphController.listGraphNodeStatus POST /api/v1alpha1/graph/node/status
```

关键 Graph 类型：

```ts
interface StartGraphRequest {
  projectId?: string;
  graphId?: string;
  breakpoint?: boolean;
  nodes?: Array<string>;
}

interface GraphNodeDetail {
  codeName?: string;
  graphNodeId?: string;
  label?: string;
  x?: number;
  y?: number;
  inputs?: Array<string>;
  outputs?: Array<string>;
  nodeDef?: Record<string, any>;
  status?: GraphNodeTaskStatus;
  jobId?: string;
}
```

---

### 4.2 SecretPad 后端

#### 4.2.1 创建项目

- **Controller**：`secretpad/secretpad-web/.../controller/ProjectController.java`
- **URL**：`POST /api/v1alpha1/project/create`
- **Service**：`ProjectServiceImpl.createProject(CreateProjectRequest)`
- **实体**：`ProjectDO`（表 `project`）
  - 字段：`projectId`、`name`、`description`、`computeMode`（MPC/TEE）、`projectInfo`、`ownerId`

#### 4.2.2 创建/更新图

- **Controller**：`GraphController.java`
- **URL**：
  - `POST /api/v1alpha1/graph/create`
  - `POST /api/v1alpha1/graph/update`
  - `POST /api/v1alpha1/graph/node/update`
- **Service**：`GraphServiceImpl`
  - `createGraph(CreateGraphRequest)`：保存 `ProjectGraphDO` + `ProjectGraphNodeDO`
  - `fullUpdateGraph(FullUpdateGraphRequest)`：全量更新节点/边
- **实体**：
  - `ProjectGraphDO`（表 `project_graph`）：`upk(projectId, graphId)`、`edges`、`nodes`、`maxParallelism`
  - `ProjectGraphNodeDO`（表 `project_graph_node`）：`graphNodeId`、`codeName`、`inputs`、`outputs`、`nodeDef`

#### 4.2.3 启动图（任务创建入口）

- **URL**：`POST /api/v1alpha1/graph/start`
- **方法**：`GraphServiceImpl.startGraph(StartGraphRequest)`

调用链：

```text
GraphServiceImpl.startGraph
  ├─ ownerCheck
  ├─ findTopNodes / findParties     // 解析参与方
  ├─ GraphContext.set(...)          // 设置 TEE/调度/断点上下文
  ├─ verifyNodeAndRouteHealthy(...)
  ├─ ProjectJob.genProjectJob(graphDO, selectedNodes, parties)
  └─ jobChain.proceed(projectJob)
```

#### 4.2.4 组件解析

- **Service**：`ComponentServiceImpl`
- **文件**：`secretpad/secretpad-service/.../service/impl/ComponentServiceImpl.java`
- 组件列表来源：`List<CompListDef> components`（由 `SecretpadComponentConfig` 从 `config/components` 加载）
- 方法：
  - `listComponents()`：返回 `Map<String, CompListVO>`
  - `batchGetComponent(List<ComponentKey>)`：返回 `List<ComponentDef>`
  - `isSecretpadComponent(GraphNodeInfo)`：判断是否为 SecretPad 本地组件（如读数据）

#### 4.2.5 JobChain 执行链

- **Chain 构造**：`secretpad/secretpad-service/.../service/graph/JobChain.java`
- 3 个 Handler：
  1. `JobPersistentHandler`（order=1）：初始化 task 状态，保存 `ProjectJobDO`
  2. `JobRenderHandler`（order=2）：渲染 inputs/outputs、处理依赖、裁剪 SecretPad 组件
  3. `JobSubmittedHandler`（order=3）：转换并提交到 Kuscia

#### 4.2.6 Kuscia Job 转换

- **普通 MPC**：`KusciaJobConverter.converter(ProjectJob)`
  - 生成 `Job.CreateJobRequest`
  - 每个 task 的 `taskInputConfig` 为 JSON 化的 `TaskConfig.TaskInputConfig`
  - 包含 `sfDatasourceConfig`、`sfInputIds`、`sfOutputIds`、`sfClusterDesc`、`sfNodeEvalParam`
- **TEE 模式**：`KusciaTrustedFlowJobConverter.converter(ProjectJob)`
  - 生成 `TaskConfigOuterClass.TaskConfig`（`teeTaskConfig`）
  - 包含签名、证书、`capsuleManagerEndpoint`

#### 4.2.7 调用 Kuscia 服务

连接管理层：`secretpad/secretpad-api/client-java-kusciaapi/.../DynamicKusciaChannelProvider.java`

- 维护 `Map<String, KusciaApiChannelFactory> CHANNEL_FACTORIES`
- 按 `domainId` 创建 gRPC stub
- 配置模型：`KusciaGrpcConfig`（domainId/host/port/protocol/mode/token/cert/key）

统一 gRPC 客户端适配器：`secretpad/secretpad-api/.../service/impl/KusciaGrpcClientAdapter.java`

SecretPad 调用的 Kuscia API 清单：

| 领域 | Proto Service | 核心 RPC |
|---|---|---|
| Domain | `DomainService` | Create/Update/Delete/Query/BatchQueryDomain |
| DomainData | `DomainDataService` | Create/Update/Delete/Query/BatchQuery/ListDomainData |
| DomainDataSource | `DomainDataSourceService` | Create/Update/Delete/Query/BatchQuery/ListDomainDataSource |
| DomainDataGrant | `DomainDataGrantService` | Create/Update/Delete/Query/BatchQuery/ListDomainDataGrant |
| DomainRoute | `DomainRouteService` | Create/Delete/Query/BatchQueryDomainRoute |
| Job | `JobService` | CreateJob / QueryJob / WatchJob / StopJob / DeleteJob / ApproveJob / Suspend / Restart / Cancel |
| Serving | `ServingService` | Create/Update/Delete/Query/BatchQueryServing |
| Health | `HealthService` | healthZ |
| Certificate | `CertificateService` | GenerateKeyCerts |

#### 4.2.8 状态同步与结果回写

**状态同步（WatchJob 流）**：

- 启动时机：`KusciaRegisterListener.onApplicationEvent(RegisterKusciaEvent)`
- 核心方法：`JobManager.startSync()`
  - 创建异步 stub：`dynamicKusciaChannelProvider.createStub(nodeId, JobServiceGrpc.JobServiceStub.class)`
  - 调用 `watchJob(WatchJobRequest)` 建立 server-side stream
- 事件处理：`JobManager.syncJob(Job.WatchJobEventResponse)`
  - 事件类型：`ADDED / MODIFIED / DELETED`
  - 更新 `ProjectJobDO` / `ProjectTaskDO` 状态
  - 状态枚举映射：`GraphJobStatus.formKusciaJobStatus`、`GraphNodeTaskStatus.formKusciaTaskStatus`

**结果回写**：

任务成功后，`JobManager.syncResult(ProjectTaskDO)` 根据输出类型写入不同表：

| 输出类型 | 判定 | 写入实体 | 表 |
|---|---|---|---|
| FedTable | `ResultKind.FedTable` | `ProjectDatatableDO` + `ProjectFedTableDO` | project_datatable / project_fed_table |
| Model | `ResultKind.Model` | `ProjectModelDO` | project_model |
| Rule | `ResultKind.Rule` | `ProjectRuleDO` | project_rule |
| Report | `ResultKind.Report` | `ProjectReportDO` | project_report |
| ReadData | `ResultKind.READ_DATA` | `ProjectReadDataDO` | project_read_data |
| 通用 | — | `ProjectResultDO` | project_result |

结果查询回前端：

- `GraphServiceImpl.getGraphNodeOutput`
- `GraphServiceImpl.getGraphNodeTaskOutputVO`
- `ProjectController.getJob`
- `ProjectController.getJobTaskOutput`

---

### 4.3 Kuscia Job / Task 调度

#### 4.3.1 整体流程

```text
SecretPad
  └─ KusciaAPI CreateJob
       └─ 创建 KusciaJob CRD (namespace: cross-domain)
            └─ kusciajob controller 调度
                 └─ 创建 KusciaTask CRD
                      └─ kusciatask controller PendingHandler
                           └─ 创建 Pod / Service / ConfigMap / TaskResourceGroup
                                └─ SecretFlow 容器启动
                                     └─ 从 DataMesh 读/写 DomainData
```

#### 4.3.2 关键 CRD

| CRD | 文件 | 说明 |
|---|---|---|
| `KusciaJob` | `kuscia/pkg/crd/apis/kuscia/v1alpha1/kusciajob_types.go` | DAG 定义，包含 `KusciaTaskTemplate` |
| `KusciaTask` | `kuscia/pkg/crd/apis/kuscia/v1alpha1/kusciatask_types.go` | 运行时任务，包含 `TaskInputConfig`、参与方、资源 |
| `AppImage` | `kuscia/pkg/crd/apis/kuscia/v1alpha1/appimage_types.go` | 应用镜像与容器模板 |

`KusciaTaskTemplate` 关键字段：

```go
type KusciaTaskTemplate struct {
    TaskID          string
    Alias           string
    Dependencies    []string
    AppImage        string
    TaskInputConfig string
    Parties         []Party
    ScheduleConfig  *ScheduleConfig
    Priority        int
    Tolerable       *bool
}
```

#### 4.3.3 调度器

| 文件 | 职责 |
|---|---|
| `kuscia/pkg/controllers/kusciajob/controller.go` | 监听 KusciaJob / KusciaTask，按 phase 分发 |
| `kuscia/pkg/controllers/kusciajob/handler/scheduler.go` | 校验 job、计算 ready tasks、创建 KusciaTask |
| `kuscia/pkg/controllers/kusciatask/controller.go` | 监听 KusciaTask，驱动生命周期 |
| `kuscia/pkg/controllers/kusciatask/handler/pending_handler.go` | Pending 阶段创建 Pod/Service/CM/TRG |
| `kuscia/pkg/controllers/kusciatask/handler/running_handler.go` | 监控 Pod 与 TRG，切换 Succeeded/Failed |

#### 4.3.4 任务注入：SecretFlow 容器拿到什么

`PendingHandler` 生成一个 **ConfigMap**，把 Kuscia 生成的运行时信息注入为环境变量/文件：

```go
confMap[common.EnvDomainID]          = partyKit.DomainID
confMap[common.EnvTaskID]            = partyKit.KusciaTask.Name
confMap[common.EnvTaskClusterDefine] = string(clusterDefine)
confMap[common.EnvAllocatedPorts]    = string(allocatedPorts)
confMapBinaryData[common.EnvTaskInputConfig] = compressInputConf
```

关键环境变量：

| 变量 | 含义 |
|---|---|
| `TASK_INPUT_CONFIG` | SecretFlow 任务输入 JSON（压缩后放在 ConfigMap `BinaryData`） |
| `TASK_CLUSTER_DEFINE` | 参与方 endpoint、端口等网络拓扑 |
| `ALLOCATED_PORTS` | 本 Pod 被分配的端口 |
| `DOMAIN_ID` / `TASK_ID` | 当前域与任务标识 |

容器 spec 从 `AppImage` 部署模板复制：

```go
Container.Command = deployTemplate.Command
Container.Args    = deployTemplate.Args
Container.Env     = deployTemplate.Env
Container.Resources = deployTemplate.Resources
Container.VolumeMounts = deployTemplate.VolumeMounts
```

#### 4.3.5 Config 模板渲染

如果 AppImage 使用了 `configTemplateVolumesAnnotationKey`，Agent 的 **config-render** 插件会：

1. 读取上面生成的 ConfigMap
2. 将 `TASK_INPUT_CONFIG`、`TASK_CLUSTER_DEFINE`、`ALLOCATED_PORTS` 等解压/填充
3. 渲染模板为最终配置文件，挂到容器内

代码：`kuscia/pkg/agent/middleware/plugins/hook/configrender/config_render.go`。

#### 4.3.6 DomainData 是如何“注入”任务的

不是作为文件直接挂载，而是：

1. SecretPad/KusciaAPI 创建 `KusciaJob` 时，`TaskInputConfig.sf_input_ids` 里填写 `domaindata_id`。
2. `KusciaTask` 创建后，`TASK_INPUT_CONFIG` 被压缩进 ConfigMap。
3. SecretFlow 容器启动后读取 `TASK_INPUT_CONFIG`，得到输入/输出的 `domaindata_id`。
4. SecretFlow 通过本域 DataMesh（`127.0.0.1:8071` 或 `datamesh` service）的 Arrow Flight 接口：
   - `download_to_file(domaindata_id)` 读取输入
   - `upload_file(domaindata_id)` 写出结果
   - `create_domaindata(...)` 注册输出元数据

---

### 4.4 SecretFlow 执行

#### 4.4.1 入口与主流程

Kuscia 实际启动 SecretFlow 的 Python 入口是：

- **文件**：`secretflow/secretflow/kuscia/entry.py`
- **函数**：`main(task_config_path, datamesh_addr, enable_plugins)`（`@click.command()` 装饰的 CLI）
- **模块常量**：`DEFAULT_DATAMESH_ADDRESS = "datamesh:8071"`

主调用链：

```text
secretflow/kuscia/entry.py::main()
    ├─ load_plugins()                       # secretflow/component/core/plugin.py
    ├─ KusciaTaskConfig.from_file(path)     # secretflow/kuscia/task_config.py
    ├─ create_channel(datamesh_addr)        # secretflow/kuscia/datamesh.py
    ├─ get_domain_data_source(...)          # 查询 DataMesh 数据源
    ├─ get_storage_config(...)              # 构建本地/S3 StorageConfig
    ├─ preprocess_sf_node_eval_param(...)   # 把 Kuscia DomainData 转为 DistData
    ├─ get_sf_cluster_config(...)           # secretflow/kuscia/sf_config.py
    ├─ comp_eval(...)                       # secretflow/component/core/entry.py
    └─ postprocess_sf_node_eval_result(...) # 把结果写回 DataMesh
```

#### 4.4.2 Kuscia 任务配置解析

- **文件**：`secretflow/secretflow/kuscia/task_config.py`
- **类**：`KusciaTaskConfig`（dataclass）

字段：

```python
@dataclass
class KusciaTaskConfig:
    task_id: str
    task_cluster_def: ClusterDefine
    task_allocated_ports: AllocatedPorts
    task_progress_url: str
    sf_node_eval_param: NodeEvalParam
    sf_cluster_desc: SFClusterDesc
    sf_storage_config: StorageConfig
    sf_input_ids: List[str]
    sf_input_partitions_spec: Dict[str, str]
    sf_output_ids: List[str]
    sf_output_uris: Dict[str, str]
    sf_output_partitions_spec: Dict[str, str]
    sf_datasource_config: Dict[str, str]
    table_attrs: Dict[str, TableAttr]
```

`party_name` 通过 `task_cluster_def.self_party_idx` 推导当前 party。

#### 4.4.3 SFConfig / SFClusterDesc / SFClusterConfig

- **数据结构定义**：`secretflow/secretflow/spec/extend/cluster_pb2.pyi`
- `SFClusterDesc`：静态集群描述，包含 `parties`、`devices`（SPU/HEU 设备描述）、`ray_fed_config`
- `SFClusterConfig`：运行时动态配置，包含：
  - `desc`（SFClusterDesc）
  - `public_config`（RayFedConfig / SPUConfig / InferenceConfig / WebhookConfig）
  - `private_config`（`self_party`、`ray_head_addr`）

由 Kuscia 配置构造 SFClusterConfig：`secretflow/secretflow/kuscia/sf_config.py::get_sf_cluster_config(...)`

#### 4.4.4 组件执行入口

- **核心调度入口**：`secretflow/secretflow/component/core/entry.py::comp_eval(param, storage_config, cluster_config, tracer_report=False)`
  - 调用 `setup_sf_cluster(config)` 初始化 Ray/RayFed 集群
  - 通过 `Registry.get_definition_by_id(param.comp_id)` 定位组件
  - 创建 `Context`（`secretflow/secretflow/component/core/context.py`）
  - 调用 `comp.evaluate(ctx)`
  - 收集 `Output` 上的 `DistData`，封装为 `NodeEvalResult`
  - 最终 `shutdown()`

#### 4.4.5 读取 DataMesh 数据

**DataMesh 连接器**：

- **抽象接口**：`secretflow/secretflow/component/core/connector/connector.py`
- **DataMesh 连接器实现**：`secretflow/secretflow/component/core/connector/datamesh.py`
  - 类：`DataMesh(IConnector)`
  - 方法：
    - `download_table(...)`：通过 `domaindata_id` + `partition_spec` 从 DataMesh 下载，转为 ORC/CSV
    - `upload_table(...)`：把 ORC 表上传回 DataMesh
    - `upload_file(...)`：上传模型/二进制文件
  - 底层客户端：`dataproxy.DataProxyFileAdapter`（来自 `secretflow-dataproxy`）

**DataSource 组件**：

- **文件**：`secretflow/secretflow/component/io/data_source.py`
- 注册名：`io/data_source:1.0.0`
- URI 格式：`datamesh:///{relative_path}?domaindata_id={id}&datasource_id={id}&partition_spec={spec}`
- `evaluate()` 内调用 `new_connector(uri.scheme)` 并 `download_table()`，生成 `VTable` -> `DistData`

**Kuscia 适配层读取**：

- **文件**：`secretflow/secretflow/kuscia/datamesh.py`
  - `create_channel(address)`：建立 gRPC channel，支持 mTLS
  - `get_domain_data(stub, id)` -> `DomainData`
  - `create_dm_flight_client(dm_address)` -> `DataProxyFileAdapter`
  - `get_file_from_dp(...)` / `put_file_to_dp(...)`

- **文件**：`secretflow/secretflow/kuscia/entry.py`
  - `domaindata_id_to_dist_data(...)`：把 `DomainData` 转为 `DistData`
  - `download_dist_data_from_dp(...)`：按 `sf.table` / `sf.model` / `sf.rule` 等类型下载 CSV/ORC/二进制

#### 4.4.6 联邦学习组件

| 组件 | 文件 | 注册 ID |
|---|---|---|
| PSI | `secretflow/component/preprocessing/data_prep/psi.py` | `data_prep/psi:1.0.0` |
| SGB 训练 | `secretflow/component/ml/boost/sgb_train.py` | `ml.train/sgb_train:1.1.0` |
| SGB 预测 | `secretflow/component/ml/boost/sgb_predict.py` | `ml.predict/sgb_predict:1.0.0` |
| SS-XGB 训练 | `secretflow/component/ml/boost/ss_xgb_train.py` | `ml.train/ss_xgb_train:1.0.0` |
| SS-GLM 训练 | `secretflow/component/ml/linear/ss_glm_train.py` | `ml.train/ss_glm_train:1.1.0` |
| SS-SGD 训练 | `secretflow/component/ml/linear/ss_sgd_train.py` | `ml.train/ss_sgd_train:1.0.0` |
| KNN 训练 | `secretflow/component/ml/neighbors/k_neighbors_classifier.py` | `ml.train/knn_train:1.0.0` |
| 高斯朴素贝叶斯 | `secretflow/component/ml/naive_bayes/gaussian_naive_bayes.py` | `ml.train/gnb_train:1.0.0` |
| KMeans | `secretflow/component/ml/cluster/kmeans.py` | `ml.train/kmeans_train:1.0.0` |

组件执行流程示例（以 SGBTrain 为例）：

```text
comp_eval(NodeEvalParam)
    ├─ Registry.get_definition_by_id("ml.train/sgb_train:1.1.0")
    ├─ comp_def.parse_param(param)          # 解析 attr / input / output
    ├─ ctx = Context(storage_config, cluster_config, checkpoint)
    ├─ comp = SGBTrain(...)                 # secretflow/component/ml/boost/sgb_train.py
    ├─ comp.evaluate(ctx)
    │   ├─ VTable.from_distdata(self.input_ds)
    │   ├─ ctx.load_table(...).to_pandas()  # -> VDataFrame
    │   ├─ ctx.make_heu(...)                # 创建 HEU
    │   ├─ Sgb(heu).train(...)              # secretflow/ml/boost/sgb_v/sgb.py
    │   ├─ Model(...)                       # 封装模型
    │   └─ ctx.dump_to(model, self.output_model)
    └─ NodeEvalResult(outputs=[...])
```

#### 4.4.7 结果写回 DataMesh

**Kuscia 适配层输出**：

- **文件**：`secretflow/secretflow/kuscia/entry.py`
- **函数**：`postprocess_sf_node_eval_result(...)`
  - 遍历 `sf_output_ids`、`res.outputs`、`sf_output_uris`
  - 调用 `convert_dist_data_to_domain_data(...)`（`secretflow/secretflow/kuscia/meta_conversion.py`）把 `DistData` 转为 `DomainData`
  - 调用 `create_domain_data_in_dm(...)` 在 DataMesh 注册 DomainData
  - 若非 `access_directly`，调用 `upload_dist_data_to_dp(...)` 上传文件

**上传类型处理**：

- `sf.table` -> CSV/ORC
- `sf.model` / `sf.rule` -> 打包为 `.tar.gz` 二进制
- `sf.serving.model` -> 二进制
- `sf.report` / `sf.read_data` -> 无文件上传

**组件层输出**：

- **DataSink 组件**：`secretflow/secretflow/component/io/data_sink.py`
  - 注册名：`io/data_sink:1.0.0`
  - URI 格式：`datamesh:///{path}?domaindata_id=...&datasource_id=...&partition_spec=...`
  - `evaluate()` 调用 `DataMesh.upload_table(...)`

---

## 5. 跨项目接口与数据结构汇总

### 5.1 端口一览

| 组件 | HTTP | gRPC/Flight |
|---|---|---|
| SecretPad 前端 | — | — |
| SecretPad 后端 | `8080`（Spring Boot 默认） | gRPC 到 KusciaAPI |
| KusciaAPI（外部） | `8082` | `8083` |
| KusciaAPI（内部） | `8092` | — |
| DataMesh | `8070` | `8071` |

### 5.2 Proto 文件位置

SecretPad 引用的 Kuscia Proto：

```textnsecretpad/proto/kuscia/proto/api/v1alpha1/kusciaapi/
├── domain.proto
├── domaindata.proto
├── domaindatasource.proto
├── domaindatagrant.proto
├── domain_route.proto
├── job.proto
├── serving.proto
├── health.proto
├── certificate.proto
└── error_code.proto
```

Kuscia 内部 Proto：

```text
kuscia/proto/api/v1alpha1/kusciaapi/*.proto
kuscia/proto/api/v1alpha1/datamesh/*.proto
kuscia/proto/api/v1alpha1/common.proto
```

### 5.3 SecretPad 前端 REST 接口

| 功能 | URL |
|---|---|
| 创建数据源 | `POST /api/v1alpha1/datasource/create` |
| 数据源列表 | `POST /api/v1alpha1/datasource/list` |
| 创建项目 | `POST /api/v1alpha1/project/create` |
| 创建图 | `POST /api/v1alpha1/graph/create` |
| 更新图 | `POST /api/v1alpha1/graph/update` |
| 启动图 | `POST /api/v1alpha1/graph/start` |
| 查询图节点状态 | `POST /api/v1alpha1/graph/node/status` |
| 查询项目 Job 列表 | `POST /api/v1alpha1/project/job/list` |
| 查询 Job 详情 | `POST /api/v1alpha1/project/job/get` |
| 查询 Job 日志 | `POST /api/v1alpha1/project/job/task/logs` |
| 查询 Job 输出 | `POST /api/v1alpha1/project/job/task/output` |

### 5.4 KusciaAPI 接口

| 资源 | URL 前缀 |
|---|---|
| DomainDataSource | `POST /api/v1/domaindatasource/{create,update,delete,query,batchQuery,list}` |
| DomainData | `POST /api/v1/domaindata/{create,update,delete,deleteDataAndRaw,query,batchQuery,list}` |
| DomainDataGrant | `POST /api/v1/domaindatagrant/{create,update,delete,query,batchQuery,list}` |
| Job | `POST /api/v1/job/{create,delete,query,stop,status/batchQuery,watch,approve,suspend,restart,cancel}` |

### 5.5 KusciaJob Proto 关键结构

```protobuf
// kuscia/proto/api/v1alpha1/kusciaapi/job.proto
message CreateJobRequest {
  string job_id = 2;
  string initiator = 3;
  int32 max_parallelism = 4;
  repeated Task tasks = 5;
}

message Task {
  string app_image = 1;
  repeated Party parties = 2;
  string alias = 3;
  string task_id = 4;
  repeated string dependencies = 5;
  string task_input_config = 6;
}

message WatchJobEventResponse {
  EventType type = 1;
  JobStatus object = 2;
}
```

### 5.6 DomainData Proto 关键结构

```protobuf
// kuscia/proto/api/v1alpha1/kusciaapi/domaindata.proto
message CreateDomainDataRequest {
  string domaindata_id = 2;
  string name = 3;
  string type = 4;          // table/model/rule/report
  string relative_uri = 5;
  string domain_id = 6;
  string datasource_id = 7;
  map<string, string> attributes = 8;
  repeated DataColumn columns = 10;
}
```

### 5.7 SecretFlow TaskInputConfig 关键字段

```json
{
  "sf_node_eval_param": { ... },
  "sf_cluster_desc": { ... },
  "sf_input_ids": ["alice-table-1", "bob-table-1"],
  "sf_output_ids": ["output-1"],
  "sf_output_uris": { "output-1": "dm://output/?datasource_id=default-data-source&id=output-1" },
  "sf_datasource_config": { ... },
  "table_attrs": { ... }
}
```

---

## 6. 关键文件索引

### 6.1 SecretPad 前端

| 主题 | 文件 |
|---|---|
| 数据源列表/注册 | `secretpad/frontend-src/apps/platform/src/modules/data-source-list/index.tsx` |
| 注册数据源表单 | `secretpad/frontend-src/apps/platform/src/modules/data-source-list/components/create-data-source/index.tsx` |
| 数据源 API | `secretpad/frontend-src/apps/platform/src/services/secretpad/DataSourceController.ts` |
| 项目创建 | `secretpad/frontend-src/apps/platform/src/modules/create-project/create-project.view.tsx` |
| DAG 页面 | `secretpad/frontend-src/apps/platform/src/pages/dag.tsx` |
| 画布/工具栏 | `secretpad/frontend-src/apps/platform/src/modules/main-dag/graph.tsx` / `toolbar.tsx` |
| 组件树 | `secretpad/frontend-src/apps/platform/src/modules/component-tree/component-tree-view.tsx` |
| 组件配置 | `secretpad/frontend-src/apps/platform/src/modules/component-config/config-form-view.tsx` |
| 类型定义 | `secretpad/frontend-src/apps/platform/src/services/secretpad/typings.d.ts` |
| 请求封装 | `secretpad/frontend-src/apps/platform/src/app.ts` |

### 6.2 SecretPad 后端

| 主题 | 文件 |
|---|---|
| 数据源 Controller | `secretpad/secretpad-web/src/main/java/org/secretflow/secretpad/web/controller/DataSourceController.java` |
| 数据源 Service | `secretpad/secretpad-service/.../service/impl/DatasourceServiceImpl.java` |
| OSS 数据源 Handler | `secretpad/secretpad-service/.../service/handler/datasource/OssKusciaControlDatasourceHandler.java` |
| 项目 Controller | `secretpad/secretpad-web/.../controller/ProjectController.java` |
| 图 Controller | `secretpad/secretpad-web/.../controller/GraphController.java` |
| 图 Service | `secretpad/secretpad-service/.../service/impl/GraphServiceImpl.java` |
| JobChain | `secretpad/secretpad-service/.../service/graph/JobChain.java` |
| Kuscia Job 转换器 | `secretpad/secretpad-service/.../service/graph/converter/KusciaJobConverter.java` |
| TEE Job 转换器 | `secretpad/secretpad-service/.../service/graph/converter/KusciaTrustedFlowJobConverter.java` |
| Kuscia gRPC 客户端 | `secretpad/secretpad-api/.../service/impl/KusciaGrpcClientAdapter.java` |
| gRPC 连接管理 | `secretpad/secretpad-api/client-java-kusciaapi/.../DynamicKusciaChannelProvider.java` |
| Job 状态同步 | `secretpad/secretpad-service/.../service/impl/JobManager.java` |
| Proto 文件 | `secretpad/proto/kuscia/proto/api/v1alpha1/kusciaapi/*.proto` |

### 6.3 Kuscia

| 主题 | 文件 |
|---|---|
| KusciaAPI HTTP 启动/路由/鉴权 | `kuscia/pkg/kusciaapi/bean/http_server_bean.go` |
| KusciaAPI gRPC 启动 | `kuscia/pkg/kusciaapi/bean/grpc_server_bean.go` |
| KusciaAPI DomainDataSource 服务 | `kuscia/pkg/kusciaapi/service/domaindata_source.go` |
| KusciaAPI DomainData 服务 | `kuscia/pkg/kusciaapi/service/domaindata_service.go` |
| KusciaAPI Job 服务 | `kuscia/pkg/kusciaapi/service/job_service.go` |
| DataMesh HTTP 启动 | `kuscia/pkg/datamesh/bean/http_server_bean.go` |
| DataMesh gRPC/Flight 启动 | `kuscia/pkg/datamesh/bean/grpc_server_bean.go` |
| DataMesh Flight Handler | `kuscia/pkg/datamesh/dataserver/handler/handler.go` |
| DataMesh Flight IO | `kuscia/pkg/datamesh/dataserver/service/flight_io.go` |
| DataMesh 默认数据源 | `kuscia/pkg/datamesh/metaserver/service/domaindatasource.go` |
| Job Controller | `kuscia/pkg/controllers/kusciajob/controller.go` |
| Job Scheduler | `kuscia/pkg/controllers/kusciajob/handler/scheduler.go` |
| Task Controller | `kuscia/pkg/controllers/kusciatask/controller.go` |
| Task Pending 阶段 | `kuscia/pkg/controllers/kusciatask/handler/pending_handler.go` |
| Config 模板渲染 | `kuscia/pkg/agent/middleware/plugins/hook/configrender/config_render.go` |
| Gateway Master 集群路由 | `kuscia/pkg/gateway/clusters/master.go` |
| Gateway DomainRoute | `kuscia/pkg/gateway/controller/domain_route.go` |
| DomainDataGrant 同步 | `kuscia/pkg/controllers/domaindata/controller.go` |
| Python DataMesh Client | `kuscia/python/kuscia/datamesh/api.py` |
| DomainDataSource CRD | `kuscia/pkg/crd/apis/kuscia/v1alpha1/domaindatasource_types.go` |
| DomainData CRD | `kuscia/pkg/crd/apis/kuscia/v1alpha1/domaindata_types.go` |
| KusciaJob CRD | `kuscia/pkg/crd/apis/kuscia/v1alpha1/kusciajob_types.go` |
| KusciaTask CRD | `kuscia/pkg/crd/apis/kuscia/v1alpha1/kusciatask_types.go` |

### 6.4 SecretFlow

| 主题 | 文件 |
|---|---|
| Kuscia 任务主入口 | `secretflow/secretflow/kuscia/entry.py` |
| Kuscia 任务配置 | `secretflow/secretflow/kuscia/task_config.py` |
| SF 集群配置 | `secretflow/secretflow/kuscia/sf_config.py` |
| Ray 配置 | `secretflow/secretflow/kuscia/ray_config.py` |
| DataMesh gRPC/Flight 客户端 | `secretflow/secretflow/kuscia/datamesh.py` |
| DomainData <=> DistData 转换 | `secretflow/secretflow/kuscia/meta_conversion.py` |
| 组件执行入口 | `secretflow/secretflow/component/core/entry.py` |
| Context | `secretflow/secretflow/component/core/context.py` |
| DataMesh Connector | `secretflow/secretflow/component/core/connector/datamesh.py` |
| Connector 工厂 | `secretflow/secretflow/component/core/connector/__init__.py` |
| DataSource 组件 | `secretflow/secretflow/component/io/data_source.py` |
| DataSink 组件 | `secretflow/secretflow/component/io/data_sink.py` |
| PSI 组件 | `secretflow/secretflow/component/preprocessing/data_prep/psi.py` |
| SGB 训练 | `secretflow/secretflow/component/ml/boost/sgb_train.py` |
| SS-GLM 训练 | `secretflow/secretflow/component/ml/linear/ss_glm_train.py` |
| SS-SGD 训练 | `secretflow/secretflow/component/ml/linear/ss_sgd_train.py` |
| Kuscia 测试用例 | `secretflow/tests/kuscia/test_kuscia.py` |

---

## 7. 完整端到端调用链总图

### 7.1 DataMesh 数据源创建

```text
SecretPad 前端
  -> apps/platform/src/modules/data-source-list/components/create-data-source/index.tsx
     -> data-source-list.service.ts addDataSource
        -> services/secretpad/DataSourceController.ts create
           -> POST /api/v1alpha1/datasource/create
              -> SecretPad DataSourceController
                 -> DatasourceServiceImpl.createDatasource
                    -> OssKusciaControlDatasourceHandler (或 ODPS/MYSQL)
                       -> KusciaGrpcClientAdapter.createDomainDataSource
                          -> DomainDataSourceServiceGrpc.CreateDomainDataSource (8083)
                             -> KusciaAPI pkg/kusciaapi/service/domaindata_source.go
                                -> 创建 DomainDataSource CRD
                                   -> kuscia/pkg/datamesh/metaserver/service/domaindatasource.go
                                      -> 本域 DataMesh 可访问 127.0.0.1:8071
```

### 7.2 联邦学习任务创建与执行

```text
SecretPad 前端
  -> create-project.view.tsx
     -> create-project.service.ts createProject
        -> ProjectController.createProject       POST /api/v1alpha1/project/create
        -> GraphController.createGraph           POST /api/v1alpha1/graph/create
        -> GraphController.fullUpdateGraph       POST /api/v1alpha1/graph/update
  -> dag.tsx 画布
     -> 拖拽组件 / 配置参数 / 选择数据表
     -> GraphController.startGraph              POST /api/v1alpha1/graph/start
        -> SecretPad GraphServiceImpl.startGraph
           -> ProjectJob.genProjectJob
           -> JobChain.proceed
              ├─ JobPersistentHandler
              ├─ JobRenderHandler
              └─ JobSubmittedHandler
                 -> KusciaJobConverter.converter
                    -> KusciaGrpcClientAdapter.createJob
                       -> JobServiceGrpc.CreateJob (8083)
                          -> KusciaAPI pkg/kusciaapi/service/job_service.go
                             -> 创建 KusciaJob CRD (cross-domain)
                                -> kuscia/pkg/controllers/kusciajob/controller.go
                                   -> kuscia/pkg/controllers/kusciajob/handler/scheduler.go
                                      -> 创建 KusciaTask CRD
                                         -> kuscia/pkg/controllers/kusciatask/controller.go
                                            -> kuscia/pkg/controllers/kusciatask/handler/pending_handler.go
                                               -> 创建 Pod / Service / ConfigMap / TaskResourceGroup
                                                  -> 容器启动: python -m secretflow.kuscia.entry <task_config.json>
                                                     -> secretflow/secretflow/kuscia/entry.py::main()
                                                        -> KusciaTaskConfig.from_file()
                                                        -> get_storage_config()
                                                        -> preprocess_sf_node_eval_param()
                                                           -> download_dist_data_from_dp()  // 从 DataMesh 读
                                                        -> get_sf_cluster_config()
                                                        -> comp_eval()
                                                           -> setup_sf_cluster()
                                                           -> Component.evaluate(ctx)       // 训练/求交/预测
                                                           -> NodeEvalResult(outputs=[...])
                                                        -> postprocess_sf_node_eval_result()
                                                           -> create_domain_data_in_dm()     // 注册输出
                                                           -> upload_dist_data_to_dp()       // 写回 DataMesh

                                              -> kuscia/pkg/controllers/kusciatask/handler/running_handler.go
                                                 -> Pod Succeeded/Failed
                          <- KusciaAPI WatchJob stream
        <- SecretPad JobManager.startSync()
           -> JobManager.syncJob()
              -> updateJob / syncResult
                 -> ProjectResultDO / ProjectDatatableDO / ProjectModelDO / ProjectReportDO
        <- 前端轮询 GraphController.listGraphNodeStatus
           -> GraphServiceImpl.getGraphNodeOutput / getGraphNodeTaskOutputVO
```

---

## 8. 总结

- **SecretPad 前端**负责把业务操作（数据源注册、项目创建、DAG 编排、任务运行）通过 REST 提交给后端。
- **SecretPad 后端**负责业务校验、状态持久化、把 DAG/组件转译为 Kuscia 任务配置，并通过 **gRPC** 调用 KusciaAPI。
- **KusciaAPI** 是 Kuscia 暴露给外部的统一控制面（HTTP 8082 / gRPC 8083），负责把外部请求转化为 K8s CRD（`DomainDataSource`、`DomainData`、`KusciaJob`、`KusciaTask` 等）。
- **DataMesh** 运行在每个域内（HTTP 8070 / Arrow Flight 8071），负责数据源与数据集的元数据管理，以及向引擎提供统一的数据读写接口。
- **Kuscia Controllers** 监听 CRD，把 `KusciaJob` 拆分为 `KusciaTask`，最终创建 SecretFlow Pod，并把任务配置注入为 ConfigMap / 环境变量。
- **SecretFlow** 在容器内读取 Kuscia 注入的配置，通过 DataMesh Connector 下载输入数据，执行联邦学习组件，再把结果上传回 DataMesh 注册为新的 `DomainData`。
- 任务状态通过 KusciaAPI 的 `WatchJob` 流实时回写到 SecretPad 后端，前端通过轮询接口展示进度与结果。

后续如需进一步细化某一段（例如 TEE 任务流、P2P 项目创建、DataMesh Flight IO 的具体 Arrow 协议、或 SecretFlow 某个组件的内部实现），可基于本文档的索引继续深挖对应文件。
