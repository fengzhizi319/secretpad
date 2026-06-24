# SecretPad 新建项目功能与单元测试运行指南

本文档说明 SecretPad 登录后如何在前端主页面新建项目，并给出**可复现的单元测试（UT）运行配置**。文档覆盖：

- 前端新建项目的入口、组件与接口调用链；
- 后端新建项目 API 与现有 UT；
- 前端新建项目 UT（本次新增）与后端现有 UT 的完整运行命令；
- 环境准备、常见问题和验证结果。

> 适用范围：SecretPad 前端 `frontend-src` + Java 后端 `secretpad-*` 模块。

---

## 1. 登录后如何新建项目

### 1.1 前端入口

登录后默认进入首页 `/home`，页面组件链为：

```
pages/new-home.tsx
  └── modules/layout/home-layout/index.tsx
        └── modules/project-content/project-content.view.tsx
              └── modules/project-list/index.tsx   <-- “新建项目”按钮在这里
```

点击按钮后打开弹窗：

| 模式 | 弹窗组件 | 文件路径 |
|------|----------|----------|
| CENTER / EDGE / ALL-IN-ONE | `CreateProjectModal` | `apps/platform/src/modules/create-project/create-project.view.tsx` |
| P2P / AUTONOMY | `P2PCreateProjectModal` | `apps/platform/src/modules/create-project/p2p-create-project/p2p-create-project.view.tsx` |

### 1.2 新建项目表单字段

弹窗表单（以 CENTER 模式为例）包含：

- **项目名称**（`projectName`）：必填，最多 32 字符，仅允许中文/英文/数字/下划线/中划线；
- **项目描述**（`description`）：可选，最多 128 字符；
- **计算模式**（`computeMode`）：`MPC`（管道模式）或 `TEE`（枢纽模式）；
- **训练流模板**（`templateId`）：如空白模板、PSI、Risk 等；
- **参与节点**（`nodes`）：至少两个节点。

### 1.3 前端 Service 调用链

点击“创建”后触发 `handleOk` -> `CreateProjectService.createProject`：

```
create-project.view.tsx
  └── create-project.service.ts: createProject
        ├── ProjectController.ts: createProject        POST /api/v1alpha1/project/create
        ├── ProjectController.ts: addProjectNode       POST /api/v1alpha1/project/node/add
        ├── ProjectController.ts: addProjectInst       POST /api/v1alpha1/project/inst/add
        ├── ProjectController.ts: addProjectDatatable  POST /api/v1alpha1/project/datatable/add
        ├── pipeline-service.ts: createPipeline        创建训练流
        └── umi history.push('/dag?projectId=xxx&mode=xxx')
```

P2P 模式调用 `P2PProjectController.createP2PProject`：

```
POST /api/v1alpha1/p2p/project/create
```

### 1.4 后端接口

| 接口 | Controller | 方法 | 路径 |
|------|------------|------|------|
| 中心化创建项目 | `ProjectController` | `createProject` | `POST /api/v1alpha1/project/create` |
| P2P 创建项目 | `P2PProjectController` | `createP2PProject` | `POST /api/v1alpha1/p2p/project/create` |
| 添加节点 | `ProjectController` | `addProjectNode` | `POST /api/v1alpha1/project/node/add` |
| 添加机构 | `ProjectController` | `addProjectInst` | `POST /api/v1alpha1/project/inst/add` |
| 添加数据表 | `ProjectController` | `addProjectDatatable` | `POST /api/v1alpha1/project/datatable/add` |

Service 实现位于：

- `secretpad-service/src/main/java/org/secretflow/secretpad/service/impl/ProjectServiceImpl.java`
- 接口：`secretpad-service/src/main/java/org/secretflow/secretpad/service/ProjectService.java`

---

## 2. 现有单元测试情况

### 2.1 后端：已有 UT

后端已存在针对新建项目的集成测试，位于 `secretpad-web/src/test/java/org/secretflow/secretpad/web/controller/`：

| 测试类 | 覆盖内容 |
|--------|----------|
| `ProjectControllerTest` | `createProject()` 正常/异常分支（项目不存在、机构不存在） |
| `P2PProjectControllerTest` | `createP2PProject()` 正常分支 |
| `RepositoryTest` | `ProjectRepository` 的 save / findByStatus / deleteAllAuthentic |

测试基类：`secretpad-web/src/test/java/org/secretflow/secretpad/web/controller/ControllerTest.java`

### 2.2 前端：原无新建项目 UT

`frontend-src` 中此前仅有 `packages/utils/src/future.test.ts`，没有针对“新建项目”流程的任何测试。

本次新增前端 UT：

- **文件**：`frontend-src/apps/platform/src/modules/create-project/create-project.service.test.ts`
- **目标**：`CreateProjectService.createProject`
- **覆盖场景**：
  1. CENTER 模式下使用空白模板成功创建 MPC 项目；
  2. `createProject` 接口返回非 0 状态码时抛出错误。

同时新增/修改了前端测试基础设施：

- `frontend-src/apps/platform/jest.config.js`：启用 React/jsdom 环境、TypeScript 转换、`@/` 路径别名、静态资源 mock；
- `frontend-src/apps/platform/package.json`：新增 devDependency `@secretflow/testing`。

---

## 3. 环境准备

### 3.1 前端

- **Node**：`>= 16.14.0`（推荐与 `packageManager` 一致，使用 pnpm 8.8.0）
- **包管理器**：pnpm 8.8.0

安装依赖：

```bash
cd cd /home/charles/code/secretpad/frontend-src
pnpm install
```

如果 pnpm 未安装：

```bash
npm install -g pnpm@8.8.0
```

### 3.2 后端

- **JDK**：17（项目已内置 `.tools/jdk-17`，也可使用系统 JDK 17）
- **Maven**：3.8.x（项目已内置 `.tools/maven`）
- **系统工具**：`openssl`、`keytool`（用于测试启动时生成证书，已在 `secretpad-web/config/setup.sh` 中调用）

> 后端测试首次运行会自动执行 `./config/setup.sh`，生成 `config/certs/` 与 `db/` 目录。

---

## 4. 运行单元测试

### 4.1 后端现有 UT

#### 运行中心化创建项目测试

```bash
cd /home/charles/code/secretpad
.tools/maven/bin/mvn test -pl secretpad-web \
  -Dtest=ProjectControllerTest#createProject \
  -DfailIfNoTests=false
```
命令解释：
1. cd /home/charles/code/secretpad ；
   切换到项目根目录
2. .tools/maven/bin/mvn ；
   使用项目本地安装的 Maven（位于 .tools/maven/bin/ 目录下）
   而不是系统全局的 Maven
   这样可以确保使用特定版本的 Maven，避免版本兼容性问题
3. test ；
   Maven 的生命周期阶段，表示运行测试
   会执行 src/test/java 目录下的单元测试
4. -pl secretpad-web (或 --projects)  ；
   只针对指定模块运行测试
   在这个多模块项目中，只测试 secretpad-web 模块
   不测试其他模块（如 secretpad-service、secretpad-persistence 等）
   好处：节省时间，聚焦于特定模块
5. -Dtest=ProjectControllerTest#createProject ；
   精确指定要运行的测试方法
   ProjectControllerTest: 测试类名
   #createProject: 具体的测试方法名
   只运行这一个测试方法，而不是整个测试类
   格式：测试类名#测试方法名，路径为：secretpad\secretpad-web\src\test\java\org\secretflow\secretpad\web\controller\P2PProjectControllerTest.java


6. -DfailIfNoTests=false ；
   如果没有找到匹配的测试，不要构建失败
   默认情况下，如果找不到测试，Maven 会报错
   设置为 false 后，即使没找到测试也会继续执行
   使用场景
   这条命令通常用于：
   调试单个测试：快速验证某个具体功能
   开发过程中：只测试刚修改的代码相关部分
   CI/CD 流程：针对性地运行特定测试
   节省时间：避免运行全部测试套件
#### 运行 P2P 创建项目测试

```bash
cd /home/charles/code/secretpad
.tools/maven/bin/mvn test -pl secretpad-web \
  -Dtest=P2PProjectControllerTest#createProject \
  -DfailIfNoTests=false
```

#### 运行 `secretpad-web` 全部测试

```bash
cd /home/charles/code/secretpad
.tools/maven/bin/mvn test -pl secretpad-web
```

#### 运行全量后端测试

```bash
cd /home/charles/code/secretpad
make test
# 等价于：mvn clean test
```

### 4.2 前端新增 UT

#### 单独运行新建项目测试

```bash
cd frontend-src/apps/platform
pnpm test -- create-project.service.test.ts --no-coverage
```

#### 运行 platform 应用全部测试

```bash
cd frontend-src/apps/platform
pnpm test
```

#### 运行前端全部测试

```bash
cd frontend-src
pnpm test
```

---

## 5. 配置说明

### 5.1 前端 Jest 配置

`frontend-src/apps/platform/jest.config.js`：

```js
/** @type {import('ts-jest').JestConfigWithTsJest} */
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

关键点：

- 继承 `@secretflow/testing/config/react`（Jest 29 + ts-jest + jsdom + React Testing Library）；
- `isolatedModules: true`：避免 ts-jest 对全量源码做类型检查，防止无关类型错误阻塞测试；
- `moduleNameMapper`：映射 `umi` 生成的 `@/`、`@@/` 路径别名，并将样式/图片资源替换为 identity proxy。

### 5.2 前端测试依赖

`frontend-src/apps/platform/package.json` 中新增：

```json
{
  "devDependencies": {
    "@secretflow/testing": "workspace:^"
  }
}
```

添加后需重新执行 `pnpm install`，pnpm 会自动建立 workspace 软链。

### 5.3 后端测试配置

后端测试激活 `test` profile，使用 `secretpad-web/config/application-test.yaml`：

- `secretpad.auth.enabled: false`：测试关闭认证；
- 数据库沿用 `config/application.yaml` 中的 SQLite 文件库 `./db/secretpad.sqlite`；
- Quartz 使用 H2 文件库 `./db/secretpadQuartz.mv.db`；
- `ControllerTest` 在每个测试前通过 `UserContext.setBaseUser` 注入测试用户。

---

## 6. 验证结果

### 6.1 前端新建项目 UT

```bash
cd frontend-src/apps/platform
pnpm test -- create-project.service.test.ts --no-coverage
```

预期输出：

```text
PASS src/modules/create-project/create-project.service.test.ts
  CreateProjectService
    ✓ should create a project with blank template in MPC mode (6 ms)
    ✓ should throw an error when createProject API returns a non-zero code (11 ms)

Test Suites: 1 passed, 1 total
Tests:       2 passed, 2 total
```

### 6.2 后端中心化创建项目 UT

```bash
.tools/maven/bin/mvn test -pl secretpad-web -Dtest=ProjectControllerTest#createProject -DfailIfNoTests=false
```

预期输出（节选）：

```text
[INFO] Running org.secretflow.secretpad.web.controller.ProjectControllerTest
...
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

### 6.3 后端 P2P 创建项目 UT

```bash
.tools/maven/bin/mvn test -pl secretpad-web -Dtest=P2PProjectControllerTest#createProject -DfailIfNoTests=false
```

预期输出（节选）：

```text
[INFO] Running org.secretflow.secretpad.web.controller.P2PProjectControllerTest
...
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

---

## 7. 常见问题

### 7.1 前端测试找不到 `@secretflow/testing`

错误示例：

```text
Error: Cannot find module '@secretflow/testing/config/react'
```

解决：确认 `apps/platform/package.json` 已添加 `@secretflow/testing: "workspace:^"`，并重新执行：

```bash
cd frontend-src
pnpm install
```

### 7.2 前端测试报 `Cannot use import statement outside a module`（antd ESM）

原因是 `antd` 以 ESM 发布，默认不会被 Jest 转换。本配置通过 `isolatedModules` 与 `@secretflow/testing/config/react` 已规避大部分问题；若后续新增组件测试仍遇到，可在 `jest.config.js` 增加 `transformIgnorePatterns` 让 Jest 转换指定 ESM 包。

### 7.3 后端测试首次运行较慢

`ControllerTest.setup()` 会调用 `secretpad-web/config/setup.sh` 生成证书和 `db/` 目录，首次约 20~30 秒，后续会复用。

### 7.4 后端测试报证书或数据库错误

检查是否具备 `openssl` 和 `keytool`，并确认 `secretpad-web/config/certs/`、`db/` 目录已生成。

---

## 8. 相关文件速查

| 用途 | 路径 |
|------|------|
| 前端项目列表入口 | `frontend-src/apps/platform/src/modules/project-list/index.tsx` |
| 前端创建项目弹窗 | `frontend-src/apps/platform/src/modules/create-project/create-project.view.tsx` |
| 前端创建项目 Service | `frontend-src/apps/platform/src/modules/create-project/create-project.service.ts` |
| 前端新建项目 UT | `frontend-src/apps/platform/src/modules/create-project/create-project.service.test.ts` |
| 前端 Jest 配置 | `frontend-src/apps/platform/jest.config.js` |
| 后端中心化 Controller | `secretpad-web/src/main/java/org/secretflow/secretpad/web/controller/ProjectController.java` |
| 后端 P2P Controller | `secretpad-web/src/main/java/org/secretflow/secretpad/web/controller/p2p/P2PProjectController.java` |
| 后端 Service 实现 | `secretpad-service/src/main/java/org/secretflow/secretpad/service/impl/ProjectServiceImpl.java` |
| 后端 Controller 测试 | `secretpad-web/src/test/java/org/secretflow/secretpad/web/controller/ProjectControllerTest.java` |
| 后端 P2P 测试 | `secretpad-web/src/test/java/org/secretflow/secretpad/web/controller/P2PProjectControllerTest.java` |
| 后端测试基类 | `secretpad-web/src/test/java/org/secretflow/secretpad/web/controller/ControllerTest.java` |
| 后端测试配置 | `secretpad-web/config/application-test.yaml` |

---

## 9. 扩展建议

如需继续提升新建项目相关测试覆盖率，可考虑：

1. **前端组件测试**：对 `CreateProjectModal` 做 React Testing Library 测试，验证表单校验、按钮禁用、提交调用 `service.createProject`；
2. **前端 P2P 测试**：为 `P2PCreateProjectService.createProject` 补充 P2P 模式 UT；
3. **后端 Service 层 UT**：当前仅通过 Controller 集成测试覆盖，可新增 `ProjectServiceImplTest` 对 `createProject`/`createP2PProject` 做纯 Mockito 单元测试；
4. **端到端测试**：使用 Playwright / Cypress 覆盖“登录 -> 新建项目 -> 进入 DAG”完整用户路径。
