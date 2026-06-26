# Kuscia-only Installation Script

## 说明

此脚本仅安装 Kuscia 容器（Master + Lite 节点），不安装 SecretPad Web 容器。

适用于本地开发调试场景，配合源码启动的 SecretPad 使用。

> 注意：`install-kuscia-only.sh` 基于 `scripts/install.sh` 改造，去掉了 `kuscia-master-secretpad` 容器部署。它仍会从 `SECRETPAD_IMAGE` 中抽取 `/app/scripts/deploy` 下的公共脚本（`common/log.sh`、`common/utils.sh`、`common/secretpad.env` 等）用于初始化 Kuscia。

## 快速开始

### 基本用法

```bash
# 使用 TLS 加密通信（默认）
bash scripts/install-kuscia-only.sh master

# 不使用 TLS（本地开发推荐，更简单）
bash scripts/install-kuscia-only.sh master -P notls
```

脚本采用 `MPC` 部署模式，默认拉起 `master` + `alice` + `bob` 三个 Kuscia 容器。

### 自定义端口

`master` 模式下的 Gateway / KusciaAPI 端口由 `deploy/common/utils.sh::prepare_environment()` 固定为 18080/18082/18083，通常无需修改。以下参数对 `master` 的 Envoy Internal 与 Metrics 端口有效：

```bash
bash scripts/install-kuscia-only.sh master -P notls \
  -q 13081   # Internal 端口 \
  -x 13084   # Metrics 端口
```

## 参数说明

| 参数 | 说明 | 默认值 | 对 master 是否生效 |
|------|------|--------|-------------------|
| `-P` | Kuscia 通信协议（tls/notls） | tls | 是 |
| `-p` | Kuscia Gateway 端口 | 18080 | 否（master 固定 18080） |
| `-k` | Kuscia API HTTP 端口 | 18082 | 否（master 固定 18082） |
| `-g` | Kuscia API gRPC 端口 | 18083 | 否（master 固定 18083） |
| `-q` | Domain 内部通信端口（Envoy Internal） | 13081 | 是 |
| `-x` | Metrics 端口 | 13084 | 是 |
| `-d` | 安装目录 | `$HOME/kuscia` | 是 |
| `-h` | 显示帮助信息 | - | - |

## 与 install.sh 的区别

| 特性 | install-kuscia-only.sh | install.sh |
|------|------------------------|------------|
| 安装内容 | 仅 Kuscia | Kuscia + SecretPad + 其他组件 |
| 镜像大小 | 小（MPC 模式） | 大（ALL-IN-ONE） |
| 启动速度 | 快 | 慢 |
| 适合场景 | 源码调试、开发 | 快速体验、演示 |
| 8080 端口冲突 | 无（不启动 SecretPad 容器） | 有（需手动停止容器版 SecretPad） |

## 部署后下一步

1. **生成证书**
   ```bash
   bash scripts/test/setup.sh
   ```

2. **编译后端**
   ```bash
   mvn clean install -Dmaven.test.skip=true
   ```

3. **启动后端**
   ```bash
   export KUSCIA_API_ADDRESS=127.0.0.1
   export KUSCIA_GW_ADDRESS=127.0.0.1:13081
   export KUSCIA_PROTOCOL=notls

   java -Dspring.profiles.active=dev \
        -Dsun.net.http.allowRestrictedHeaders=true \
        -Dserver.port=8443 \
        -jar target/secretpad.jar
   ```

4. **启动前端**
   ```bash
   cd frontend-src
   pnpm --filter secretpad dev
   ```

5. **登录**

   当前源码已固定为 `admin / 12345678`（详见 `docs/development/test-guides/cipher12345678.md`）。

## 常用命令

```bash
# 查看所有容器
docker ps | grep kuscia

# 查看日志（假设当前用户为 charles）
docker logs -f ${USER}-kuscia-master
docker logs -f ${USER}-kuscia-lite-alice
docker logs -f ${USER}-kuscia-lite-bob

# 停止所有容器
docker stop ${USER}-kuscia-master ${USER}-kuscia-lite-alice ${USER}-kuscia-lite-bob

# 删除所有容器
docker rm -f ${USER}-kuscia-master ${USER}-kuscia-lite-alice ${USER}-kuscia-lite-bob
```

## 注意事项

- ✅ 此脚本仅启动 Kuscia 容器，不会启动 SecretPad 容器
- ✅ SecretPad 需要单独从源码启动（用于开发调试）
- ✅ 启动完成后，KusciaAPI 客户端证书位于项目根目录 `config/certs/`
- ✅ 后端配置 gateway 应指向 `127.0.0.1:13081`
- ⚠️ 如果脚本询问是否保留已有的 k3s 数据，本地开发首次部署建议输入 `n`；后续复用可输入 `y`

## 参考文档

详见项目根目录的 `运行说明.md` 文档第 2 节与第 6 节。
