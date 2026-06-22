# SecretPad 前端本地运行指南

> 说明：SecretPad 主仓库（`secretpad`）本身不包含前端源码，前端代码位于独立仓库 [secretpad-frontend](https://github.com/secretflow/secretpad-frontend)。本指南描述如何在本地拉取、安装依赖并运行前端开发服务器。

## 1. 环境要求

| 名称 | 推荐版本 | 说明 |
|------|---------|------|
| Node.js | >= 16.14.0（本环境使用 v20.14.0） | 前端运行基础 |
| pnpm | 8.8.0（由 `packageManager` 字段锁定） | 使用 corepack 激活 |
| Git | 任意 | 克隆前端仓库 |

## 2. 一键准备本地运行时

以下脚本将 Node.js、pnpm、JDK 17、Maven 安装在项目内的 `.tools` 目录，避免污染系统环境。

```bash
# 进入项目根目录
cd /home/charles/code/secretpad

# 1. 下载 Node.js 20.14.0 到 .tools/node
mkdir -p .tools
curl -L -o .tools/node.tar.xz 'https://nodejs.org/dist/v20.14.0/node-v20.14.0-linux-x64.tar.xz'
tar -xf .tools/node.tar.xz -C .tools
mv .tools/node-v20.14.0-linux-x64 .tools/node
rm .tools/node.tar.xz

# 2. 激活 pnpm 8.8.0
export PATH=/home/charles/code/secretpad/.tools/node/bin:$PATH
corepack enable
corepack prepare pnpm@8.8.0 --activate

# 3. 下载 JDK 17 与 Maven 3.8.8（后端测试/运行需要）
curl -L -o .tools/jdk17.tar.gz 'https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.11%2B9/OpenJDK17U-jdk_x64_linux_hotspot_17.0.11_9.tar.gz'
tar -xzf .tools/jdk17.tar.gz -C .tools
mv .tools/jdk-17.0.11+9 .tools/jdk-17
rm .tools/jdk17.tar.gz

curl -L -o .tools/maven.tar.gz 'https://archive.apache.org/dist/maven/maven-3/3.8.8/binaries/apache-maven-3.8.8-bin.tar.gz'
tar -xzf .tools/maven.tar.gz -C .tools
mv .tools/apache-maven-3.8.8 .tools/maven
rm .tools/maven.tar.gz
```

设置环境变量（可加入 `~/.bashrc` 或每次执行前 source）：

```bash
export JAVA_HOME=/home/charles/code/secretpad/.tools/jdk-17
export PATH=$JAVA_HOME/bin:/home/charles/code/secretpad/.tools/maven/bin:/home/charles/code/secretpad/.tools/node/bin:$PATH
```

验证：

```bash
java -version      # openjdk 17.0.11
mvn -version       # Apache Maven 3.8.8
node -v            # v20.14.0
pnpm -v            # 8.8.0
```

## 3. 拉取前端源码

```bash
cd /home/charles/code/secretpad
git clone --depth=1 https://github.com/secretflow/secretpad-frontend.git frontend-src
```

## 4. 安装依赖并构建 workspace 包

前端使用 pnpm workspace + nx，platform 应用依赖 `@secretflow/dag` 和 `@secretflow/utils` 两个 workspace 包，需要先执行 `setup`。

```bash
cd /home/charles/code/secretpad/frontend-src

# 安装所有依赖（约 2271 个包）
pnpm install

# 构建 workspace 内部包（utils / dag）并执行 umi setup
pnpm run setup
```

## 5. 运行前端开发服务器

### 5.1 仅启动 platform 应用（推荐）

```bash
pnpm --filter secretpad dev
```

成功后会输出：

```text
App listening at:
  >   Local: http://localhost:8000
ready -  > Network: http://10.6.25.148:8000

Now you can open browser with the above addresses↑
event - [Webpack] Compiled in 10383 ms (8451 modules)
```

### 5.2 配置后端 API 代理（可选）

在 `frontend-src/apps/platform/` 下创建 `.env` 文件：

```text
PROXY_URL=http://127.0.0.1:8080
```

这样 `/api` 请求会被代理到本地 SecretPad 后端服务。若未配置代理，前端页面可正常加载，但调用接口时会报错。

### 5.3 启动全部前端应用

```bash
pnpm dev
```

该命令会并行启动 workspace 中所有非 `demo-*` 应用的 dev 服务器。

## 6. 构建并集成到 SecretPad 后端

若需要将前端产物打包进后端 JAR：

```bash
cd /home/charles/code/secretpad
make build
```

`scripts/build/build.sh true` 会自动下载最新 tag 的前端 dist 包并拷贝到 `secretpad-web/src/main/resources/static/`。

## 7. 本次运行验证

按上述步骤执行后，platform 应用成功启动：

```text
> secretpad@ dev /home/charles/code/secretpad/frontend-src/apps/platform
> umi dev

info  - Umi v4.3.18
info  - Preparing...
        ╔════════════════════════════════════════════════════╗
        ║ App listening at:                                  ║
        ║  >   Local: http://localhost:8000                  ║
ready - ║  > Network: http://10.6.25.148:8000                ║
        ║                                                    ║
        ║ Now you can open browser with the above addresses↑ ║
        ╚════════════════════════════════════════════════════╝
event - [Webpack] Compiled in 10383 ms (8451 modules)
```

- 访问地址：`http://localhost:8000`
- 编译成功：8451 个模块
- 未配置 `PROXY_URL` 时页面可正常加载，调用 `/api/*` 接口需自行启动后端并配置代理。

## 8. 常见问题

### 7.1 报错 `Module not found: Can't resolve '@secretflow/dag'`

原因：没有先执行 `pnpm run setup` 构建 workspace 包。  
解决：按第 4 步执行 `pnpm run setup` 后再运行 dev。

### 7.2 端口被占用

Umi 默认使用 `8000` 端口。若被占用，可在 `apps/platform/config/config.ts` 中增加 `port` 配置，或使用环境变量：

```bash
PORT=8001 pnpm --filter secretpad dev
```

### 7.3 浏览器提示 `caniuse-lite is outdated`

不影响运行。如需消除提示，可执行：

```bash
npx update-browserslist-db@latest
```
