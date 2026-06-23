# SecretPad 核心模块完整测试流程指南

本文档覆盖 **项目管理、节点管理、数据管理、任务流编排** 四个核心模块的前后端的：

- 源码与测试文件定位；
- 现有单元测试（UT）/集成测试清单；
- 缺失测试说明；
- 环境配置；
- 可复现的运行命令与预期结果。

> 文档基于 SecretPad 前端 `frontend-src` + Java 后端 `secretpad-*` 模块整理。

---

## 1. 环境准备

### 1.1 前端

| 依赖 | 版本/要求 |
|------|-----------|
| Node.js | `>= 16.14.0` |
| pnpm | `8.8.0`（与 `packageManager` 字段锁定一致） |

安装依赖：

```bash
cd /home/charles/code/secretpad/frontend-src
pnpm install
```

> 若未安装 pnpm，可执行 `npm install -g pnpm@8.8.0`。

### 1.2 后端

| 依赖 | 版本/要求 |
|------|-----------|
| JDK | 17（项目已内置 `.tools/jdk-17`） |
| Maven | 3.8.x（项目已内置 `.tools/maven`） |
| 系统工具 | `openssl`、`keytool`（测试启动脚本 `secretpad-web/config/setup.sh` 会自动调用） |

> 后端测试首次运行会自动生成证书目录 `config/certs/` 与数据库目录 `db/`。

---

## 2. 测试框架与配置

### 2.1 前端

- **测试框架**：Jest 29 + ts-jest + React Testing Library + jsdom
- **共享配置**：`frontend-src/tooling/jest/config/react.js`
- **Platform 应用配置**：`frontend-src/apps/platform/jest.config.js`

`apps/platform/jest.config.js` 关键配置：

```js
module.exports = {
  ...require('@secretflow/testing/config/react'),
  transform: {
    '^.+\\.tsx?$': [
      'ts-jest',
      {
        isolatedModules: true,
      },
    ],
  },
  moduleNameMapper: {
    '\\.(css|less|sass|scss)$': 'identity-obj-proxy',
    '\\.(gif|ttf|eot|svg|png|jpg|jpeg)$': 'identity-obj-proxy',
    '^@/(.*)$': '<rootDir>/src/$1',
    '^@@/(.*)$': '<rootDir>/src/.umi/$1',
  },
};
```

说明：

- `isolatedModules: true`：关闭全量类型检查，避免无关源码类型错误阻塞测试执行；
- `moduleNameMapper`：处理 `umi` 的 `@/`、`@@/` 路径别名，并将样式/图片资源 mock 为 identity proxy。

### 2.2 后端

- **测试框架**：JUnit 5 + Spring Boot Test + Mockito + MockMvc
- **测试基类**：`secretpad-web/src/test/java/org/secretflow/secretpad/web/controller/ControllerTest.java`
- **激活 Profile**：`test`（`secretpad-web/config/application-test.yaml`）

后端测试特点：

- 使用 `@SpringBootTest` + `@AutoConfigureMockMvc` 做 Controller 层集成测试；
- 使用 SQLite 文件库 `./db/secretpad.sqlite` 与 H2 Quartz 文件库；
- `ControllerTest` 在每个测试前注入测试用户并 mock 数据同步/限流工具类。

---

## 3. 项目管理（Project Management）

### 3.1 前端源码

| 功能 | 路径 |
|------|------|
| 项目列表页 | `frontend-src/apps/platform/src/modules/project-list/index.tsx` |
| 项目列表 Service | `frontend-src/apps/platform/src/modules/project-list/project-list.service.ts` |
| 创建项目弹窗 | `frontend-src/apps/platform/src/modules/create-project/create-project.view.tsx` |
| 创建项目 Service | `frontend-src/apps/platform/src/modules/create-project/create-project.service.ts` |
| P2P 项目列表 Service | `frontend-src/apps/platform/src/modules/p2p-project-list/p2p-project-list.service.ts` |
| API 客户端 | `frontend-src/apps/platform/src/services/secretpad/ProjectController.ts` |
| P2P API 客户端 | `frontend-src/apps/platform/src/services/secretpad/P2PProjectController.ts` |

### 3.2 后端源码

| 类型 | 路径 |
|------|------|
| Controller | `secretpad-web/src/main/java/org/secretflow/secretpad/web/controller/ProjectController.java` |
| P2P Controller | `secretpad-web/src/main/java/org/secretflow/secretpad/web/controller/p2p/P2PProjectController.java` |
| Service 接口 | `secretpad-service/src/main/java/org/secretflow/secretpad/service/ProjectService.java` |
| Service 实现 | `secretpad-service/src/main/java/org/secretflow/secretpad/service/impl/ProjectServiceImpl.java` |

### 3.3 前端测试

新增/现有测试文件：

| 文件 | 覆盖功能 |
|------|----------|
| `frontend-src/apps/platform/src/modules/create-project/create-project.service.test.ts` | 创建项目成功路径、接口异常 |
| `frontend-src/apps/platform/src/modules/project-list/project-list.service.test.ts` | 项目列表、训练流列表、任务列表、删除、更新 |

运行命令：

```bash
cd /home/charles/code/secretpad/frontend-src/apps/platform

# 仅跑项目管理相关测试
pnpm test -- create-project.service.test.ts project-list.service.test.ts --no-coverage

# 跑 platform 全部测试
pnpm test
```

### 3.4 后端测试

| 测试类 | 路径 | 覆盖说明 |
|--------|------|----------|
| `ProjectControllerTest` | `secretpad-web/src/test/java/org/secretflow/secretpad/web/controller/ProjectControllerTest.java` | 创建、列表、详情、更新、删除、添加机构/节点/数据表、job/task 等 |
| `P2PProjectControllerTest` | `secretpad-web/src/test/java/org/secretflow/secretpad/web/controller/P2PProjectControllerTest.java` | P2P 创建、列表、更新、归档、参与方 |
| `RepositoryTest` | `secretpad-web/src/test/java/org/secretflow/secretpad/web/controller/RepositoryTest.java` | ProjectRepository 等持久层冒烟测试 |

运行命令：

```bash
cd /home/charles/code/secretpad

# 项目管理相关后端测试
.tools/maven/bin/mvn test -pl secretpad-web \
  -Dtest=ProjectControllerTest,P2PProjectControllerTest,RepositoryTest \
  -DfailIfNoTests=false

# 仅创建项目测试
.tools/maven/bin/mvn test -pl secretpad-web \
  -Dtest=ProjectControllerTest#createProject,P2PProjectControllerTest#createProject \
  -DfailIfNoTests=false
```

### 3.5 缺失测试

- 前端：`p2p-project-list.service.ts`、项目编辑弹窗、P2P 创建项目等暂无 UT；
- 后端：`ProjectServiceImpl` 无纯单元测试，当前仅通过 Controller 集成测试间接覆盖；`getTeeNodeList`、`getProjectAllOutTable` 等接口未单独覆盖。

---

## 4. 节点管理（Node Management）

### 4.1 前端源码

| 功能 | 路径 |
|------|------|
| 节点 Service | `frontend-src/apps/platform/src/modules/node/node.service.ts` |
| 节点列表 | `frontend-src/apps/platform/src/modules/managed-node-list/index.tsx` |
| 我的节点 | `frontend-src/apps/platform/src/modules/my-node/index.tsx` |
| 合作节点（路由） | `frontend-src/apps/platform/src/modules/cooperative-node-list/index.tsx` |
| API 客户端 | `frontend-src/apps/platform/src/services/secretpad/NodeController.ts` |
| 路由 API 客户端 | `frontend-src/apps/platform/src/services/secretpad/NodeRouteController.ts` |

### 4.2 后端源码

| 类型 | 路径 |
|------|------|
| Controller | `secretpad-web/src/main/java/org/secretflow/secretpad/web/controller/NodeController.java` |
| P2P Node Controller | `secretpad-web/src/main/java/org/secretflow/secretpad/web/controller/p2p/P2pNodeController.java` |
| 路由 Controller | `secretpad-web/src/main/java/org/secretflow/secretpad/web/controller/NodeRouteController.java` |
| Service | `secretpad-service/src/main/java/org/secretflow/secretpad/service/NodeService.java` / `impl/NodeServiceImpl.java` |
| Manager | `secretpad-manager/src/main/java/org/secretflow/secretpad/manager/integration/node/NodeManager.java` |

### 4.3 前端测试

新增测试文件：

| 文件 | 覆盖功能 |
|------|----------|
| `frontend-src/apps/platform/src/modules/node/node.service.test.ts` | 节点列表查询、Edge 节点列表、设置当前节点并触发事件 |

运行命令：

```bash
cd /home/charles/code/secretpad/frontend-src/apps/platform
pnpm test -- node.service.test.ts --no-coverage
```

### 4.4 后端测试

| 测试类 | 路径 | 覆盖说明 |
|--------|------|----------|
| `NodeControllerTest` | `secretpad-web/src/test/java/org/secretflow/secretpad/web/controller/NodeControllerTest.java` | 节点创建、删除、更新、分页、详情、Token、刷新、结果列表/详情等 |
| `P2pNodeControllerTest` | `secretpad-web/src/test/java/org/secretflow/secretpad/web/controller/P2pNodeControllerTest.java` | P2P 节点创建/删除异常分支 |
| `NodeRouteControllerTest` | `secretpad-web/src/test/java/org/secretflow/secretpad/web/controller/NodeRouteControllerTest.java` | 合作节点路由增删改查 |
| `NodeManagerTest` | `secretpad-manager/src/test/java/org/secretflow/secretpad/manager/integration/node/NodeManagerTest.java` | NodeManager 部分工具方法 |
| `NodeRouteManagerTest` | `secretpad-manager/src/test/java/org/secretflow/secretpad/manager/integration/node/NodeRouteManagerTest.java` | 路由在 Kuscia 中的创建/删除 |

运行命令：

```bash
cd /home/charles/code/secretpad

# Controller 层节点测试
.tools/maven/bin/mvn test -pl secretpad-web \
  -Dtest=NodeControllerTest,P2pNodeControllerTest,NodeRouteControllerTest \
  -DfailIfNoTests=false

# Manager 层节点测试
.tools/maven/bin/mvn test -pl secretpad-manager \
  -Dtest=NodeManagerTest,NodeRouteManagerTest \
  -DfailIfNoTests=false
```

### 4.5 缺失测试

- 前端：`managed-node-list`、`my-node`、`cooperative-node-list` 等页面/组件暂无 UT；
- 后端：`NodeServiceImpl`、`NodeRouterServiceImpl` 无专门 Service 层 UT；`NodeManager` 大量 CRUD 方法未覆盖。

---

## 5. 数据管理（Data Management）

### 5.1 前端源码

| 功能 | 路径 |
|------|------|
| 数据列表 Service | `frontend-src/apps/platform/src/modules/data-manager/data-manager.service.ts` |
| 数据列表视图 | `frontend-src/apps/platform/src/modules/data-manager/data-manager.view.tsx` |
| 添加数据 Service | `frontend-src/apps/platform/src/modules/data-table-add/add-data/add-data-service.ts` |
| 数据源列表 | `frontend-src/apps/platform/src/modules/data-source-list/index.tsx` |
| 数据详情 | `frontend-src/apps/platform/src/modules/data-table-info/data-table-info.view.tsx` |
| API 客户端 | `frontend-src/apps/platform/src/services/secretpad/DatatableController.ts` |
| 数据源 API 客户端 | `frontend-src/apps/platform/src/services/secretpad/DataSourceController.ts` |
| 数据文件 API 客户端 | `frontend-src/apps/platform/src/services/secretpad/DataController.ts` |

### 5.2 后端源码

| 类型 | 路径 |
|------|------|
| 数据表 Controller | `secretpad-web/src/main/java/org/secretflow/secretpad/web/controller/DatatableController.java` |
| 数据源 Controller | `secretpad-web/src/main/java/org/secretflow/secretpad/web/controller/DataSourceController.java` |
| 数据文件 Controller | `secretpad-web/src/main/java/org/secretflow/secretpad/web/controller/DataController.java` |
| Service | `secretpad-service/src/main/java/org/secretflow/secretpad/service/DatatableService.java` 等 |

### 5.3 前端测试

新增测试文件：

| 文件 | 覆盖功能 |
|------|----------|
| `frontend-src/apps/platform/src/modules/data-manager/data-manager.service.test.ts` | 带过滤条件的数据表分页查询 |

运行命令：

```bash
cd /home/charles/code/secretpad/frontend-src/apps/platform
pnpm test -- data-manager.service.test.ts --no-coverage
```

### 5.4 后端测试

| 测试类 | 路径 | 覆盖说明 |
|--------|------|----------|
| `DatatableControllerTest` | `secretpad-web/src/test/java/org/secretflow/secretpad/web/controller/DatatableControllerTest.java` | CSV/HTTP/ODPS/MySQL 数据表创建、查询、删除 |
| `DataControllerTest` | `secretpad-web/src/test/java/org/secretflow/secretpad/web/controller/DataControllerTest.java` | 文件上传、创建数据、下载 |
| `DatasourceControllerTest` | `secretpad-web/src/test/java/org/secretflow/secretpad/web/controller/DatasourceControllerTest.java` | 数据源列表、详情、节点、删除 |
| `DatasourceControllerAutonomyTest` | `secretpad-web/src/test/java/org/secretflow/secretpad/web/controller/DatasourceControllerAutonomyTest.java` | AUTONOMY 模式下创建数据源 |
| `P2PDatatableControllerTest` | `secretpad-web/src/test/java/org/secretflow/secretpad/web/controller/P2PDatatableControllerTest.java` | P2P 数据表创建/列表 |
| `P2PDatasourceControllerTest` | `secretpad-web/src/test/java/org/secretflow/secretpad/web/controller/P2PDatasourceControllerTest.java` | P2P 数据源节点查询 |
| `DatatableGrantManagerTest` | `secretpad-manager/src/test/java/org/secretflow/secretpad/manager/integration/node/DatatableGrantManagerTest.java` | 数据授权域内/跨域查询 |
| `KusciaDomainDatasourceRpcImplTest` | `secretpad-manager/src/test/java/org/secretflow/secretpad/manager/integration/node/KusciaDomainDatasourceRpcImplTest.java` | Kuscia 数据源 RPC |

运行命令：

```bash
cd /home/charles/code/secretpad

# Controller 层数据测试
.tools/maven/bin/mvn test -pl secretpad-web \
  -Dtest=DatatableControllerTest,DataControllerTest,DatasourceControllerTest,DatasourceControllerAutonomyTest,P2PDatatableControllerTest,P2PDatasourceControllerTest \
  -DfailIfNoTests=false

# Manager 层数据测试
.tools/maven/bin/mvn test -pl secretpad-manager \
  -Dtest=DatatableGrantManagerTest,KusciaDomainDatasourceRpcImplTest \
  -DfailIfNoTests=false
```

### 5.5 缺失测试

- 前端：数据上传组件、数据源管理、数据授权等页面/组件暂无 UT；
- 后端：`DatatableServiceImpl`、`DatasourceServiceImpl`、`DataServiceImpl` 无专门 Service 层 UT；`pushDatatableToTeeNode` 接口未单独覆盖。

---

## 6. 任务流编排（Pipeline / DAG / Job）

### 6.1 前端源码

| 功能 | 路径 |
|------|------|
| 训练流 Service | `frontend-src/apps/platform/src/modules/pipeline/pipeline-service.ts` |
| DAG 画布请求 Service | `frontend-src/apps/platform/src/modules/main-dag/graph-request-service.tsx` |
| 执行记录 Service | `frontend-src/apps/platform/src/modules/pipeline-record-list/record-service.ts` |
| 周期任务 | `frontend-src/apps/platform/src/modules/periodic-task/` |
| API 客户端 | `frontend-src/apps/platform/src/services/secretpad/GraphController.ts` |
| 任务 API 客户端 | `frontend-src/apps/platform/src/services/secretpad/ProjectController.ts` |
| 周期任务 API 客户端 | `frontend-src/apps/platform/src/services/secretpad/ScheduledController.ts` |

### 6.2 后端源码

| 类型 | 路径 |
|------|------|
| Graph Controller | `secretpad-web/src/main/java/org/secretflow/secretpad/web/controller/GraphController.java` |
| Project Controller (job/task) | `secretpad-web/src/main/java/org/secretflow/secretpad/web/controller/ProjectController.java` |
| Scheduled Controller | `secretpad-web/src/main/java/org/secretflow/secretpad/web/controller/ScheduledController.java` |
| Graph Service | `secretpad-service/src/main/java/org/secretflow/secretpad/service/GraphService.java` / `impl/GraphServiceImpl.java` |
| Scheduled Service | `secretpad-service/src/main/java/org/secretflow/secretpad/service/ScheduledService.java` / `impl/ScheduledServiceImpl.java` |

### 6.3 前端测试

新增测试文件：

| 文件 | 覆盖功能 |
|------|----------|
| `frontend-src/apps/platform/src/modules/pipeline-record-list/record-service.test.ts` | 执行记录列表（普通/周期子任务）、按 jobId 查找记录、筛选图节点 |

运行命令：

```bash
cd /home/charles/code/secretpad/frontend-src/apps/platform
pnpm test -- record-service.test.ts --no-coverage
```

### 6.4 后端测试

| 测试类 | 路径 | 覆盖说明 |
|--------|------|----------|
| `GraphControllerTest` | `secretpad-web/src/test/java/org/secretflow/secretpad/web/controller/GraphControllerTest.java` | 图创建/删除/更新/详情、组件列表、节点状态、启动/停止、日志/输出等 |
| `ProjectControllerTest` | `secretpad-web/src/test/java/org/secretflow/secretpad/web/controller/ProjectControllerTest.java` | job 列表、任务详情、停止、日志、输出 |
| `ScheduledControllerTest` | `secretpad-web/src/test/java/org/secretflow/secretpad/web/controller/ScheduledControllerTest.java` | 周期任务创建/下线/删除/详情/任务分页/停止/重跑等 |
| `GraphServiceImplTest` | `secretpad-service/src/test/java/org/secretflow/secretpad/service/test/GraphServiceImplTest.java` | `updateGraphMeta` 分支 |

运行命令：

```bash
cd /home/charles/code/secretpad

# Controller 层任务流测试
.tools/maven/bin/mvn test -pl secretpad-web \
  -Dtest=GraphControllerTest,ProjectControllerTest,ScheduledControllerTest \
  -DfailIfNoTests=false

# Service 层 Graph 测试
.tools/maven/bin/mvn test -pl secretpad-service \
  -Dtest=GraphServiceImplTest \
  -DfailIfNoTests=false
```

### 6.5 缺失测试

- 前端：DAG 画布、训练流创建/运行、周期任务等页面/Service 暂无 UT；
- 后端：`GraphServiceImpl` 仅 `updateGraphMeta` 有 UT，其余方法缺失；`ProjectService` 的 job/task 方法、`ScheduledServiceImpl` 全部缺失 Service 层 UT；`ProjectControllerTest.getJob()` 缺少 `@Test` 注解。

---

## 7. 一键运行全量相关测试

### 7.1 前端全量测试

```bash
cd /home/charles/code/secretpad/frontend-src/apps/platform
pnpm test
```

当前 platform 应用共 5 个测试文件、17 个测试用例（均已验证通过）。

### 7.2 后端核心模块 Controller 测试

```bash
cd /home/charles/code/secretpad

.tools/maven/bin/mvn test -pl secretpad-web \
  -Dtest=ProjectControllerTest,P2PProjectControllerTest,NodeControllerTest,P2pNodeControllerTest,NodeRouteControllerTest,DatatableControllerTest,DataControllerTest,DatasourceControllerTest,DatasourceControllerAutonomyTest,P2PDatatableControllerTest,P2PDatasourceControllerTest,GraphControllerTest,ScheduledControllerTest \
  -DfailIfNoTests=false
```

### 7.3 全量后端测试

```bash
cd /home/charles/code/secretpad
make test
# 等价于 .tools/maven/bin/mvn clean test
```

---

## 8. 验证结果参考

### 8.1 前端 Platform 全量测试

```bash
cd /home/charles/code/secretpad/frontend-src/apps/platform
pnpm test -- --no-coverage
```

预期输出：

```text
Test Suites: 5 passed, 5 total
Tests:       17 passed, 17 total
```

### 8.2 后端核心模块 Controller 测试

```bash
.tools/maven/bin/mvn test -pl secretpad-web \
  -Dtest=ProjectControllerTest,NodeControllerTest,DatatableControllerTest,GraphControllerTest,ScheduledControllerTest \
  -DfailIfNoTests=false
```

预期结果（节选自 surefire 报告）：

```text
Tests run: 46, Failures: 0, Errors: 0, Skipped: 0 - ProjectControllerTest
Tests run: 34, Failures: 0, Errors: 0, Skipped: 0 - NodeControllerTest
Tests run: 16, Failures: 0, Errors: 0, Skipped: 0 - DatatableControllerTest
Tests run: 18, Failures: 0, Errors: 0, Skipped: 0 - GraphControllerTest
Tests run: 20, Failures: 0, Errors: 0, Skipped: 0 - ScheduledControllerTest
```

---

## 9. 新增测试文件清单

本次为完善测试流程新增的前端测试文件：

| 模块 | 文件 |
|------|------|
| 项目管理 | `frontend-src/apps/platform/src/modules/project-list/project-list.service.test.ts` |
| 节点管理 | `frontend-src/apps/platform/src/modules/node/node.service.test.ts` |
| 数据管理 | `frontend-src/apps/platform/src/modules/data-manager/data-manager.service.test.ts` |
| 任务流编排 | `frontend-src/apps/platform/src/modules/pipeline-record-list/record-service.test.ts` |

新增/调整的前端测试基础设施：

| 文件 | 说明 |
|------|------|
| `frontend-src/apps/platform/jest.config.js` | Platform 应用 Jest 配置 |
| `frontend-src/apps/platform/package.json` | 新增 `@secretflow/testing` 工作区依赖 |

---

## 10. 常见问题

### 10.1 前端测试找不到 `@secretflow/testing`

```text
Error: Cannot find module '@secretflow/testing/config/react'
```

确认已添加依赖并重新安装：

```bash
cd /home/charles/code/secretpad/frontend-src
pnpm install
```

### 10.2 前端测试报 ESM 转换错误

如遇到 `Cannot use import statement outside a module`（antd 等 ESM 包），当前 `jest.config.js` 的 `isolatedModules: true` 与 `@secretflow/testing/config/react` 已能规避大部分场景。若新增组件测试仍遇到，可在 `jest.config.js` 中增加 `transformIgnorePatterns`。

### 10.3 后端测试首次运行慢

`ControllerTest.setup()` 会调用 `secretpad-web/config/setup.sh` 生成证书与数据库文件，首次约 20~30 秒，后续复用。

### 10.4 后端测试报证书/数据库错误

确认系统已安装 `openssl` 与 `keytool`，并检查 `secretpad-web/config/certs/`、`db/` 目录已生成。

---

## 11. 扩展建议

为进一步提升核心模块测试覆盖率，建议：

1. **前端组件测试**：使用 React Testing Library 对 `CreateProjectModal`、`DataManagerView`、`DagCanvas` 等关键组件做交互测试；
2. **前端 P2P 测试**：补充 `P2PCreateProjectService`、`p2p-project-list.service.ts` 的 UT；
3. **后端 Service 层 UT**：为 `ProjectServiceImpl`、`NodeServiceImpl`、`DatatableServiceImpl`、`GraphServiceImpl`、`ScheduledServiceImpl` 编写纯 Mockito 单元测试；
4. **端到端测试**：使用 Playwright/Cypress 覆盖“登录 -> 新建项目 -> 上传数据 -> 编排 DAG -> 运行任务”完整用户旅程。
