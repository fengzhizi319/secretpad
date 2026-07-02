# 无Docker运行说明

本文档说明如何在不使用Docker容器的情况下，直接运行 SecretPad 前端、后端和 Kuscia 组件。这种部署方式适用于开发和测试环境，可以避免Docker相关的复杂性。

## 目录

- [1. 环境准备](#1-环境准备)
- [2. 运行脚本使用方法](#2-运行脚本使用方法)
- [3. 手动启动步骤](#3-手动启动步骤)
- [4. 停止服务](#4-停止服务)
- [5. 常见问题排查](#5-常见问题排查)

## 1. 环境准备

### 1.1 系统要求

| 组件 | 版本要求 | 说明 |
|------|---------|------|
| JDK | 17 | Java 开发环境 |
| Maven | 3.8.8+ | Java 构建工具 |
| Node.js | >= 16.14.0（推荐 v20+） | 前端运行环境 |
| pnpm | 8.8.0 | 前端包管理器 |
| Docker | >= 20.10 | 虽然我们不使用Docker运行时，但某些构建过程可能需要Docker来提取依赖 |
| Git | 任意 | 代码管理 |
| sudo | 可用 | 因为Kuscia需要监听53端口，需要root权限 |

验证命令：

```bash
java -version      # openjdk 17
mvn -version       # Apache Maven 3.x
node -v            # v20+ 或 v18+
pnpm -v            # 8.8.0
docker --version   # >= 20.10
```

### 1.2 工作目录结构

确保你的工作目录结构如下：

```
/home/charles/code/sfwork/
├── kuscia/
│   ├── scripts/run-local-master.sh
│   └── ...
├── secretpad/
│   ├── scripts/test/setup.sh
│   ├── config/application-dev.yaml
│   └── ...
└── run-all-no-docker.sh
```

## 2. 运行脚本使用方法

项目提供了一个自动化脚本 `run-all-no-docker.sh` 来简化非Docker模式的部署流程。

### 2.1 赋予脚本执行权限

```bash
chmod +x /home/charles/code/sfwork/run-all-no-docker.sh
```

### 2.2 启动所有服务

```bash
bash /home/charles/code/sfwork/run-all-no-docker.sh
```

该脚本会自动执行以下步骤：
1. 检查系统依赖
2. 编译 Kuscia 二进制文件
3. 启动 Kuscia Master（以非Docker模式）
4. 编译 SecretPad 后端
5. 生成必要的证书
6. 启动 SecretPad 后端
7. 启动 SecretPad 前端

### 2.3 停止所有服务

```bash
bash /home/charles/code/sfwork/run-all-no-docker.sh --stop
```

## 3. 手动启动步骤

如果你希望了解每个组件的具体启动过程，可以按照以下步骤手动操作。

### 3.1 编译 Kuscia

```bash
cd /home/charles/code/sfwork/kuscia
bash hack/build.sh -t kuscia
```

这将编译出 `kuscia` 二进制文件，用于后续的非Docker模式运行。

### 3.2 启动 Kuscia Master（非Docker模式）

Kuscia 提供了 `scripts/run-local-master.sh` 脚本来支持非Docker模式运行。

```bash
# 设置 KUSCIA_HOME 目录（存储运行时数据）
export KUSCIA_HOME="/home/charles/code/sfwork/.local-kuscia"

# 使用 sudo 运行（因为需要监听53端口）
echo "110734" | sudo -S bash /home/charles/code/sfwork/kuscia/scripts/run-local-master.sh "$KUSCIA_HOME"
```

> **注意**：这里需要输入sudo密码 `110734`，脚本已经配置好自动输入。

### 3.3 编译 SecretPad 后端

```bash
cd /home/charles/code/sfwork/secretpad
mvn clean install -Dmaven.test.skip=true
```

构建成功后会在 `target/` 目录下生成 `secretpad.jar` 文件。

### 3.4 生成证书

```bash
cd /home/charles/code/sfwork/secretpad

# 清理可能存在的旧证书
rm -f config/server.jks
rm -rf config/certs/

# 生成新证书
bash scripts/test/setup.sh
```

### 3.5 启动 SecretPad 后端

```bash
# 设置环境变量
echo "110734" | sudo -S export KUSCIA_API_ADDRESS=127.0.0.1
export KUSCIA_GW_ADDRESS=127.0.0.1:13081
export KUSCIA_PROTOCOL=notls

# 启动后端
java -Dspring.profiles.active=dev \
     -Dsun.net.http.allowRestrictedHeaders=true \
     -Dserver.port=8443 \
     -jar target/secretpad.jar
```

### 3.6 启动 SecretPad 前端

```bash
cd /home/charles/code/sfwork/secretpad/frontend-src

# 安装依赖（首次运行）
pnpm bootstrap

# 启动前端开发服务器
pnpm --filter secretpad dev
```

## 4. 停止服务

### 4.1 使用自动化脚本停止

```bash
bash /home/charles/code/sfwork/run-all-no-docker.sh --stop
```

### 4.2 手动停止服务

```bash
# 停止前端（在前端项目目录中）
# Ctrl+C 或 kill 进程

# 停止后端
kill $(lsof -t -i:8443) 2>/dev/null || true

# 停止 Kuscia Master
kill $(lsof -t -i:18083) 2>/dev/null || true

# 停止由 sudo 启动的进程
echo "110734" | sudo -S pkill -f "run-local-master.sh"
```

## 5. 常见问题排查

### 5.1 端口被占用

如果遇到端口被占用的问题，可以使用以下命令检查：

```bash
# 检查8443端口（后端HTTPS）
lsof -i :8443

# 检查8080端口（后端HTTP）
lsof -i :8080

# 检查18083端口（Kuscia API）
lsof -i :18083

# 结束占用8443端口的进程
lsof -t -i:8443 | xargs kill -9
```

### 5.2 sudo权限问题

如果遇到sudo权限问题，请确保你有sudo权限，并且知道正确的密码（`110734`）。

```bash
# 测试sudo权限
echo "110734" | sudo -S whoami
```

### 5.3 53端口被占用

Kuscia的CoreDNS需要监听53端口，如果被其他服务占用，可能会导致启动失败。

```bash
# 检查53端口占用情况
sudo ss -tlnp | grep ':53'

# 如果是systemd-resolved占用，可以临时停止
sudo systemctl stop systemd-resolved

# 或者修改/etc/systemd/resolved.conf，注释掉DNSStubListener=yes
```

### 5.4 证书相关错误

如果遇到证书相关错误，尝试重新生成证书：

```bash
cd /home/charles/code/sfwork/secretpad
rm -f config/server.jks
rm -rf config/certs/
bash scripts/test/setup.sh
```

### 5.5 Kuscia启动失败

查看Kuscia的日志文件：

```bash
# 查看Kuscia日志
cat /home/charles/code/sfwork/.local-kuscia/var/logs/master.log
```

### 5.6 后端连接Kuscia失败

确保Kuscia Master已经成功启动，并且环境变量设置正确：

```bash
# 检查Kuscia API是否可达
curl -v http://127.0.0.1:18083

# 检查环境变量
echo "KUSCIA_API_ADDRESS=$KUSCIA_API_ADDRESS"
echo "KUSCIA_GW_ADDRESS=$KUSCIA_GW_ADDRESS"
echo "KUSCIA_PROTOCOL=$KUSCIA_PROTOCOL"
```

## 6. 访问服务

服务启动成功后，可以通过以下地址访问：

- **前端开发服务器**：[http://localhost:8000](http://localhost:8000)
- **后端健康检查**：[http://localhost:8080/actuator/health](http://localhost:8080/actuator/health)
- **后端HTTPS地址**：[https://localhost:8443](https://localhost:8443)

登录账号：`admin` / `12345678`