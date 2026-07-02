# SecretPad 对接 Kuscia：Docker 模式 vs 本地二进制模式

> 适用版本：SecretPad 源码仓库（`/home/charles/code/sfwork/secretpad`）与 Kuscia 源码仓库（`/home/charles/code/sfwork/kuscia`）
> 阅读对象：需要在本地开发/调试时选择或切换 Kuscia 运行方式的开发者

---

## 目录

1. [两种模式的整体架构](#1-两种模式的整体架构)
2. [核心差异速查](#2-核心差异速查)
3. [Docker 模式详解](#3-docker-模式详解)
4. [本地二进制模式详解](#4-本地二进制模式详解)
5. [SecretPad 配置差异](#5-secretpad-配置差异)
6. [启动流程对比](#6-启动流程对比)
7. [原理说明](#7-原理说明)
8. [如何选择](#8-如何选择)
9. [常见问题](#9-常见问题)

---

## 1. 两种模式的整体架构

### 1.1 Docker 模式（官方默认）

```text
┌─────────────────────────────────────────────────────────────┐
│                         宿主机（Host）                        │
│  ┌─────────────────┐         ┌───────────────────────────┐  │
│  │ SecretPad 后端   │────────▶│  Docker 网络              │  │
│  │ （源码启动）      │ gRPC    │  ┌─────────────────────┐  │  │
│  └─────────────────┘         │  │ charles-kuscia-master│  │  │
│                              │  │  - KusciaAPI gRPC    │  │  │
│  ┌─────────────────┐         │  │  - Envoy Internal    │  │  │
│  │ SecretPad 前端   │────────▶│  └─────────────────────┘  │  │
│  │ （源码启动）      │ HTTP    │  ┌─────────────────────┐  │  │
│  └─────────────────┘         │  │ charles-kuscia-lite- │  │  │
│                              │  │ alice / bob           │  │  │
│                              │  └─────────────────────┘  │  │
│                              └───────────────────────────┘  │
└─────────────────────────────────────────────────────────────┘
```

### 1.2 本地二进制模式（源码编译）

```text
┌─────────────────────────────────────────────────────────────┐
│                         宿主机（Host）                        │
│  ┌─────────────────┐                                        │
│  │ SecretPad 后端   │──────────────────────────────────────┐ │
│  │ （源码启动）      │ gRPC / 127.0.0.1:18083|28083|38083   │ │
│  └─────────────────┘                                      │ │
│                                                           ▼ │
│  ┌─────────────────┐         ┌───────────────────────────┐│ │
│  │ SecretPad 前端   │────────▶│  本地 Kuscia 二进制进程    ││ │
│  │ （源码启动）      │ HTTP    │  - kuscia start --config  ││ │
│  └─────────────────┘         │  - K3s + Envoy + CoreDNS  ││ │
│                              │  - 监听 53/80/1080/8083   ││ │
│                              └───────────────────────────┘│ │
│                                                           │ │
│                              无需 Docker 容器，进程直接运行在宿主机 │
└─────────────────────────────────────────────────────────────┘
```

---

## 2. 核心差异速查

| 对比项 | Docker 模式 | 本地二进制模式 |
|---|---|---|
| **Kuscia 运行形态** | Docker 容器（`charles-kuscia-master` / `alice` / `bob`） | 宿主机进程 |
| **SecretPad 连接地址** | 容器名（`root-kuscia-master`）或宿主机端口（`127.0.0.1:18083`） | 本机端口（`127.0.0.1:18083`） |
| **网络依赖** | 依赖 Docker 网络 / 端口映射 | 不依赖 Docker，直接监听宿主机端口 |
| **Root 权限** | 不需要（Docker 守护进程处理特权） | **需要 root**（CoreDNS 监听 53，Envoy 监听 80） |
| **根目录** | 容器内固定 `/home/kuscia` | 可通过 `KUSCIA_HOME` 自定义 |
| **TLS 模式** | 默认 `tls`（需证书）或 `-P notls` | 推荐 `notls`（开发调试） |
| **启动脚本** | `scripts/install-kuscia-only.sh` | `kuscia/scripts/run-local-master.sh` |
| **SecretPad 启动脚本** | `scripts/dev-start.sh` / `scripts/dev-start-mac.sh` | `scripts/dev-start-local-kuscia.sh` |
| **适用场景** | 快速体验、完整功能验证 | 源码二次开发、调试 Kuscia 本身 |

---

## 3. Docker 模式详解

### 3.1 原理

Docker 模式下，Kuscia 被打包成一个 Docker 镜像（如 `secretflow-registry.cn-hangzhou.cr.aliyuncs.com/secretflow/kuscia:1.2.0b0`）。镜像内部包含：

- `bin/kuscia`：Kuscia 主二进制
- `bin/k3s`：轻量级 Kubernetes 控制平面
- `bin/envoy`：网关代理
- `bin/coredns`：域名解析
- `etc/conf/`：配置文件模板
- `crds/`：Kubernetes CRD 定义
- `var/certs/`：自动生成的证书

Docker 容器启动后，Kuscia 在容器内以 root 运行，监听容器内的 53/80/1080/8082/8083 等端口，并通过 `-p` 参数映射到宿主机端口。

### 3.2 端口映射（默认）

| 服务 | 容器内端口 | 宿主机端口（master） | 宿主机端口（alice） | 宿主机端口（bob） |
|---|---|---|---|---|
| Gateway | 1080 | 18080 | 28080 | 38080 |
| Envoy Internal | 80 | 13081 | 23081 | 33081 |
| KusciaAPI HTTP | 8082 | 18082 | 28082 | 38082 |
| KusciaAPI gRPC | 8083 | 18083 | 28083 | 38083 |
| Metrics | 9091 | 13084 | 23084 | 33084 |

### 3.3 启动步骤

```bash
cd /home/charles/code/sfwork/secretpad

# 仅部署 Kuscia 容器（master + alice + bob），不启动容器版 SecretPad
bash scripts/install-kuscia-only.sh master -P notls

# 生成证书与数据库
bash scripts/test/setup.sh

# 编译后端
mvn clean install -Dmaven.test.skip=true

# 启动后端（通过环境变量覆盖容器名，使用宿主机端口）
export KUSCIA_API_ADDRESS=127.0.0.1
export KUSCIA_GW_ADDRESS=127.0.0.1:13081
export KUSCIA_PROTOCOL=notls

java -Dspring.profiles.active=dev \
     -Dsun.net.http.allowRestrictedHeaders=true \
     -Dserver.port=8443 \
     -jar target/secretpad.jar
```

### 3.4 为什么默认使用容器名

`config/application.yaml` 中默认配置：

```yaml
kuscia:
  nodes:
    - host: ${KUSCIA_API_ADDRESS:root-kuscia-master}
```

这是因为官方推荐的部署方式是：SecretPad 后端也作为一个 Docker 容器运行，与 Kuscia 容器在同一个 Docker 网络中，可以通过容器名互相解析。开发模式下通过环境变量 `KUSCIA_API_ADDRESS=127.0.0.1` 覆盖，使源码启动的后端连接到宿主机的端口映射。

---

## 4. 本地二进制模式详解

### 4.1 原理

本地二进制模式下，直接从 Kuscia 源码编译出 `kuscia` 二进制，并在宿主机上直接运行。Kuscia 会启动 K3s、Envoy、CoreDNS 等子进程，所有进程都运行在宿主机命名空间中。

关键修改：Kuscia 默认把根目录硬编码为 `/home/kuscia`，普通用户无法写入。在 `t1` 分支中，`pkg/common/constants.go` 增加了对 `KUSCIA_HOME` 环境变量的支持：

```go
func defaultKusciaHomePathFn() string {
    if home := os.Getenv("KUSCIA_HOME"); home != "" {
        return home
    }
    return defaultKusciaHomePath
}
```

这样 Kuscia 可以在任意目录（如 `./.local-kuscia`）下运行，无需 root 创建 `/home/kuscia`。

### 4.2 依赖准备

Kuscia 二进制本身只包含 Go 代码，运行时需要从官方镜像中复制以下依赖：

- `bin/k3s`：Kubernetes 控制平面
- `bin/envoy`：网关
- `bin/coredns`：DNS
- `bin/node_exporter`：监控
- `etc/conf/`：配置文件模板
- `crds/`：CRD 资源
- `scripts/`：部署脚本

这些依赖通过 `docker run --rm kuscia-image cp -a /home/kuscia/...` 提取到本地目录。

### 4.3 配置模板路径替换

官方镜像的 `etc/conf/corefile`、`etc/conf/crictl.yaml`、`etc/conf/logrotate.conf.tmpl` 等文件中硬编码了 `/home/kuscia`。在本地运行前，需要将这些路径替换为实际的 `KUSCIA_HOME`：

```bash
find ${KUSCIA_HOME}/etc -type f -exec sed -i "s|/home/kuscia|${KUSCIA_HOME}|g" {} +
```

否则会出现类似 `plugin/forward: not an IP address or file: /home/kuscia/var/tmp/resolv.conf` 的错误。

### 4.4 启动步骤

```bash
cd /home/charles/code/sfwork/kuscia

# 使用一键脚本（推荐）
sudo bash scripts/run-local-master.sh

# 或手动分步执行
bash hack/build.sh -t kuscia

export KUSCIA_HOME=$(pwd)/.local-kuscia
export KUSCIA_IMAGE=secretflow-registry.cn-hangzhou.cr.aliyuncs.com/secretflow/kuscia:1.2.0b0

mkdir -p ${KUSCIA_HOME}
docker run --rm -v ${KUSCIA_HOME}:/out --user $(id -u):$(id -g) ${KUSCIA_IMAGE} bash -c '
  cp -a /home/kuscia/bin /home/kuscia/crds /home/kuscia/etc /home/kuscia/scripts /home/kuscia/pause /out/
  mkdir -p /out/var/storage/data /out/var/logs /out/var/certs /out/var/tmp /out/var/stdout
'

cp -f build/apps/kuscia/kuscia ${KUSCIA_HOME}/bin/kuscia
find ${KUSCIA_HOME}/etc -type f -exec sed -i "s|/home/kuscia|${KUSCIA_HOME}|g" {} +

KUSCIA_HOME=${KUSCIA_HOME} ${KUSCIA_HOME}/bin/kuscia init \
  --mode master \
  --domain kuscia-master \
  --protocol NOTLS > ${KUSCIA_HOME}/etc/conf/kuscia.yaml

sudo KUSCIA_HOME=${KUSCIA_HOME} ${KUSCIA_HOME}/bin/kuscia start --config ${KUSCIA_HOME}/etc/conf/kuscia.yaml
```

---

## 5. SecretPad 配置差异

### 5.1 `config/application-dev.yaml`

#### Docker 模式（原始默认）

```yaml
secretpad:
  gateway: ${KUSCIA_GW_ADDRESS:127.0.0.1:18301}

kuscia:
  nodes:
    - domainId: ${NODE_ID:kuscia-system}
      mode: master
      host: ${KUSCIA_API_ADDRESS:root-kuscia-master}
      port: ${KUSCIA_API_PORT:18083}
      protocol: ${KUSCIA_PROTOCOL:tls}
      cert-file: config/certs/client.crt
      key-file: config/certs/client.pem
      token: config/certs/token

    - domainId: alice
      mode: lite
      host: ${KUSCIA_API_ADDRESS:root-kuscia-lite-alice}
      port: ${KUSCIA_API_PORT:28083}
      protocol: ${KUSCIA_PROTOCOL:tls}
      cert-file: config/certs/alice/client.crt
      key-file: config/certs/alice/client.pem
      token: config/certs/alice/token

    - domainId: bob
      mode: lite
      host: ${KUSCIA_API_ADDRESS:root-kuscia-lite-bob}
      port: ${KUSCIA_API_PORT:38083}
      protocol: ${KUSCIA_PROTOCOL:tls}
      cert-file: config/certs/bob/client.crt
      key-file: config/certs/bob/client.pem
      token: config/certs/bob/token
```

#### 本地二进制模式（t1 分支修改后）

```yaml
secretpad:
  gateway: ${KUSCIA_GW_ADDRESS:127.0.0.1:13081}

kuscia:
  nodes:
    - domainId: ${NODE_ID:kuscia-system}
      mode: master
      host: ${KUSCIA_API_ADDRESS:127.0.0.1}
      port: ${KUSCIA_API_PORT:18083}
      protocol: ${KUSCIA_PROTOCOL:notls}
      cert-file: config/certs/client.crt
      key-file: config/certs/client.pem
      token: config/certs/token

    - domainId: alice
      mode: lite
      host: ${KUSCIA_API_ADDRESS:127.0.0.1}
      port: ${KUSCIA_API_PORT:28083}
      protocol: ${KUSCIA_PROTOCOL:notls}
      cert-file: config/certs/alice/client.crt
      key-file: config/certs/alice/client.pem
      token: config/certs/alice/token

    - domainId: bob
      mode: lite
      host: ${KUSCIA_API_ADDRESS:127.0.0.1}
      port: ${KUSCIA_API_PORT:38083}
      protocol: ${KUSCIA_PROTOCOL:notls}
      cert-file: config/certs/bob/client.crt
      key-file: config/certs/bob/client.pem
      token: config/certs/bob/token
```

### 5.2 差异解读

| 配置项 | Docker 模式 | 本地二进制模式 | 原因 |
|---|---|---|---|
| `host` | `root-kuscia-master` | `127.0.0.1` | Docker 模式官方期望 SecretPad 也在容器内，通过容器名解析；本地模式无容器名，直接连本机端口 |
| `gateway` | `127.0.0.1:18301` | `127.0.0.1:13081` | Docker 模式下 `18301` 是 autonomy/edge 常用端口；master 模式实际映射为 `13081` |
| `protocol` | `tls` | `notls` | Docker 模式可通过 `-P notls` 改为 notls；本地模式推荐 notls 简化证书配置 |

### 5.3 环境变量

两种模式都可以不修改文件，直接通过环境变量覆盖：

```bash
# Docker 模式（如果容器名无法解析，同样要改成 127.0.0.1）
export KUSCIA_API_ADDRESS=127.0.0.1
export KUSCIA_GW_ADDRESS=127.0.0.1:13081
export KUSCIA_PROTOCOL=notls

# 本地二进制模式（与上面相同，因为都是直连本机端口）
export KUSCIA_API_ADDRESS=127.0.0.1
export KUSCIA_GW_ADDRESS=127.0.0.1:13081
export KUSCIA_PROTOCOL=notls
```

在 `t1` 分支中，`application-dev.yaml` 的默认值已经等同于这些环境变量的效果，因此本地模式下无需再手动 export。

---

## 6. 启动流程对比

### 6.1 Docker 模式启动流程

```bash
# 1. 部署 Kuscia Docker 容器
bash scripts/install-kuscia-only.sh master -P notls

# 2. 生成证书、数据库
bash scripts/test/setup.sh

# 3. 编译后端
mvn clean install -Dmaven.test.skip=true

# 4. 启动后端
export KUSCIA_API_ADDRESS=127.0.0.1
export KUSCIA_GW_ADDRESS=127.0.0.1:13081
export KUSCIA_PROTOCOL=notls
java -Dspring.profiles.active=dev \
     -Dsun.net.http.allowRestrictedHeaders=true \
     -Dserver.port=8443 \
     -jar target/secretpad.jar

# 5. 启动前端（可选）
cd frontend-src && pnpm --filter secretpad dev
```

### 6.2 本地二进制模式启动流程

```bash
# ====== Kuscia 仓库 ======
cd /home/charles/code/sfwork/kuscia

# 1. 启动本地 Kuscia master（需要 root）
sudo bash scripts/run-local-master.sh

# 2. （可选）继续注册并启动 alice / bob lite 节点
#    参考 Kuscia 多机部署文档，使用本地二进制启动

# ====== SecretPad 仓库 ======
cd /home/charles/code/sfwork/secretpad

# 3. 生成证书、数据库、编译并启动后端
bash scripts/dev-start-local-kuscia.sh

# 4. 启动前端（可选）
cd frontend-src && pnpm --filter secretpad dev
```

---

## 7. 原理说明

### 7.1 SecretPad 如何发现 Kuscia

SecretPad 后端通过 `kuscia.nodes` 配置创建 gRPC Channel。核心代码在：

- `secretpad-api/client-java-kusciaapi/src/main/java/org/secretflow/secretpad/kuscia/v1alpha1/model/DynamicKusciaGrpcConfig.java`
- `secretpad-api/client-java-kusciaapi/src/main/java/org/secretflow/secretpad/kuscia/v1alpha1/DynamicKusciaChannelProvider.java`

启动时，`DynamicKusciaChannelProvider.init()` 读取 `kuscia.nodes` 列表，为每个节点调用 `GrpcKusciaApiChannelFactory` 创建 `ManagedChannel`：

```java
NettyChannelBuilder.forAddress(host, port)
```

因此无论 Kuscia 是 Docker 容器还是本地进程，只要 `host:port` 可达，SecretPad 就能连接。

### 7.2 为什么 Docker 模式默认用容器名

在官方 ALL-IN-ONE 部署中，SecretPad 本身也是一个 Docker 容器，与 Kuscia 容器通过 Docker 网络互联。容器名 `root-kuscia-master` 是 Docker 默认容器名（当部署用户为 root 时）。开发环境中使用 `${USER}-kuscia-master`（如 `charles-kuscia-master`），所以源码启动时需要改成 `127.0.0.1`。

### 7.3 为什么本地模式需要 root

Kuscia 内部多个组件监听特权端口：

- CoreDNS：`:53`
- Envoy：`:80`
- Gateway：`:1080`

在 Docker 容器中，这些端口在容器命名空间内，容器以 root 运行，因此无问题。在宿主机上直接运行时，普通用户无法绑定 53/80 端口，必须 root。

### 7.4 KUSCIA_HOME 的作用

Kuscia 运行时需要读写大量文件：

- `var/logs/`：日志
- `var/certs/`：证书
- `var/storage/data/`：数据存储
- `containerd/run/containerd.sock`：容器运行时 socket
- `var/tmp/resolv.conf`：CoreDNS 转发配置

默认路径 `/home/kuscia` 在大多数 Linux 系统上需要 root 才能创建。通过 `KUSCIA_HOME` 可以指定到用户有权限的目录（如 `~/kuscia-local` 或项目下的 `.local-kuscia`）。

### 7.5 配置文件路径替换的原理

Kuscia 镜像中的配置文件是为容器内路径编写的，例如 `etc/conf/corefile`：

```corefile
.:53 {
    kuscia {
        fallthrough
    }
    forward . /home/kuscia/var/tmp/resolv.conf
}
```

当 `KUSCIA_HOME` 改为 `/home/charles/code/sfwork/kuscia/.local-kuscia` 时，CoreDNS 会尝试读取 `/home/kuscia/var/tmp/resolv.conf`，但该文件实际位于 `/home/charles/code/sfwork/kuscia/.local-kuscia/var/tmp/resolv.conf`。因此启动前必须替换配置文件中的路径。

---

## 8. 如何选择

| 场景 | 推荐模式 | 理由 |
|---|---|---|
| 快速体验完整功能 | Docker 模式 | 一键部署 master + alice + bob，无需关心依赖 |
| 前端页面开发 | macOS 无 Kuscia 模式 | 不依赖 Docker，只验证页面 |
| 调试 Kuscia 源码 | 本地二进制模式 | 可修改 Kuscia 代码后直接编译运行 |
| 修改 SecretPad 后端 | Docker 模式或本地二进制模式 | 都需要真实 Kuscia 才能执行任务 |
| 无 root 权限 | Docker 模式 | 本地二进制模式必须 root |
| 在 WSL2 / Linux 做深度开发 | 本地二进制模式 | 避免 Docker 容器内外路径、网络差异 |

---

## 9. 常见问题

### Q1：本地模式启动报错 `Listen: listen tcp :53: bind: permission denied`

**原因**：CoreDNS 需要监听 53 端口，普通用户无权限。

**解决**：以 root 用户执行，或使用 `sudo`：

```bash
sudo bash scripts/run-local-master.sh
```

### Q2：本地模式启动报错 `plugin/forward: not an IP address or file: /home/kuscia/var/tmp/resolv.conf`

**原因**：`etc/conf/corefile` 中路径仍是 `/home/kuscia`，未替换为实际 `KUSCIA_HOME`。

**解决**：执行路径替换：

```bash
find ${KUSCIA_HOME}/etc -type f -exec sed -i "s|/home/kuscia|${KUSCIA_HOME}|g" {} +
```

### Q3：SecretPad 后端启动报 `UnknownHostException: root-kuscia-master`

**原因**：使用了 Docker 模式的默认容器名，但本地 Kuscia 没有该容器名。

**解决**：设置环境变量或确保 `application-dev.yaml` 中 `host` 为 `127.0.0.1`。

### Q4：Docker 模式和本地模式能否同时运行？

**不推荐**。两者会占用相同端口（18083、13081 等），导致冲突。如果需要切换，先停止另一种模式：

```bash
# 停止 Docker Kuscia
docker stop ${USER}-kuscia-master ${USER}-kuscia-lite-alice ${USER}-kuscia-lite-bob

# 停止本地 Kuscia
# 在运行 kuscia 的终端按 Ctrl+C，或 kill 对应进程
```

### Q5：本地模式只启动了 master，alice / bob 怎么启动？

本地 `run-local-master.sh` 只启动 master。需要参照 Kuscia 官方文档的“多机部署中心化集群”步骤，使用本地二进制生成 lite 配置并启动：

```bash
# 1. 在 master 上注册 alice 获取 token
${KUSCIA_HOME}/bin/kuscia ... # 或使用 kubectl 创建 Domain

# 2. 生成 alice lite 配置
KUSCIA_HOME=${KUSCIA_HOME_ALICE} ${KUSCIA_HOME_ALICE}/bin/kuscia init \
  --mode lite \
  --domain alice \
  --master-endpoint "https://127.0.0.1:18080" \
  --lite-deploy-token "${ALICE_TOKEN}" \
  --protocol NOTLS > ${KUSCIA_HOME_ALICE}/etc/conf/kuscia.yaml

# 3. 启动 alice lite
sudo KUSCIA_HOME=${KUSCIA_HOME_ALICE} ${KUSCIA_HOME_ALICE}/bin/kuscia start --config ${KUSCIA_HOME_ALICE}/etc/conf/kuscia.yaml
```

---

## 参考文档

- `secretpad/运行说明.md`：SecretPad 开发模式完整运行说明
- `kuscia/docs/development/build_kuscia_cn.md`：Kuscia 源码构建
- `kuscia/docs/deployment/local_deploy_kuscia_datamesh.md`：Kuscia 本地部署
- `kuscia/docs/deployment/deploy_with_runp_cn.md`：RunP 模式部署
