# SecretPad DataMesh / 数据源 / 数据表 测试指南

本指南覆盖 SecretPad 中与 **Kuscia DataMesh、DomainDataSource、DomainData、DomainDataGrant** 相关的单元测试与集成测试的运行方法、环境配置、测试清单及结果解读。

## 1. 涉及概念与模块

| 概念 | 说明 | 主要代码位置 |
|------|------|-------------|
| DataMesh | Kuscia 提供的数据访问与授权层，SecretPad 通过 KusciaAPI 与其交互 | `proto/kuscia/proto/api/v1alpha1/kusciaapi/`、各 `KusciaGrpcClientAdapter` 调用点 |
| DomainDataSource | 节点数据源（OSS/HTTP/MySQL/ODPS 等） | `secretpad-manager/.../datasource/`、`secretpad-service/.../handler/datasource/` |
| DomainData / Datatable | 节点注册的数据表 | `secretpad-manager/.../datatable/`、`secretpad-web/.../DatatableController.java` |
| DomainDataGrant | 跨节点数据授权 | `secretpad-manager/.../datatablegrant/` |

## 2. 环境准备

### 2.1 基础运行时

- OpenJDK 17
- Apache Maven 3.8.8
- OpenSSL（生成测试证书）

可参考 [前端运行指南](../frontend_run_guide.md) 中“一键准备本地运行时”脚本，将 JDK 与 Maven 安装到项目 `.tools` 目录。

设置环境变量：

```bash
export JAVA_HOME=/home/charles/code/secretpad/.tools/jdk-17
export PATH=$JAVA_HOME/bin:/home/charles/code/secretpad/.tools/maven/bin:$PATH
```

### 2.2 生成测试证书与目录

SecretPad 测试需要 KusciaAPI 客户端证书及 SecretPad 服务端 JKS。执行：

```bash
cd /home/charles/code/secretpad
bash scripts/test/setup.sh
```

> 注意：`scripts/test/setup.sh` 默认以 `scripts/` 作为 `SECRETPAD_ROOT`，因此生成物位于 `scripts/config/`。需要将其复制到项目根目录：

```bash
mkdir -p config db
cp -r scripts/config/certs config/
cp scripts/config/server.jks config/
```

最终项目根目录应包含：

```text
secretpad/
├── config/
│   ├── certs/           # ca.crt、client.crt、client.pem、token 等
│   │   ├── alice/
│   │   └── bob/
│   └── server.jks
├── db/                  # 测试用 SQLite 目录
```

### 2.3 Maven 依赖缓存

首次运行会下载大量依赖到 `~/.m2/repository`，耗时较长（视网络 5~20 分钟）。后续运行会复用缓存。

## 3. DataMesh 相关测试清单

| 模块 | 测试类 | 测试范围 |
|------|--------|---------|
| `secretpad-manager` | `DatasourceManagerTest` | 数据源查询逻辑 |
| `secretpad-manager` | `KusciaDomainDatasourceRpcImplTest` | Kuscia DomainDataSource RPC 适配 |
| `secretpad-manager` | `DatatableGrantManagerTest` | 跨节点数据授权创建/查询 |
| `secretpad-service` | `MysqlKusciaControlDatasourceHandlerTest` | MySQL 类型数据源处理器 |
| `secretpad-service` | `OdpsKusciaControlDatasourceHandlerTest` | ODPS 类型数据源处理器 |
| `secretpad-service` | `ReadPartitionRuleAnalysisServiceImplTest` | 分区规则解析 |
| `secretpad-web` | `DatasourceControllerTest` | 数据源接口（中心模式） |
| `secretpad-web` | `DatasourceControllerAutonomyTest` | 数据源接口（自治模式） |
| `secretpad-web` | `P2PDatasourceControllerTest` | P2P 网络数据源接口 |
| `secretpad-web` | `DatatableControllerTest` | 数据表接口（中心模式） |
| `secretpad-web` | `P2PDatatableControllerTest` | P2P 网络数据表接口 |
| `secretpad-web` | `FeatureDatasourceControllerTest` | 特征数据源接口 |
| `secretpad-web` | `DataControllerTest` | 数据相关接口 |

## 4. 运行命令

### 4.1 仅运行 DataMesh 相关测试

```bash
cd /home/charles/code/secretpad
export JAVA_HOME=/home/charles/code/secretpad/.tools/jdk-17
export PATH=$JAVA_HOME/bin:/home/charles/code/secretpad/.tools/maven/bin:$PATH

mvn clean test \
  -Dtest="*Datasource*Test,*Datatable*Test,*DataController*Test,*DatatableGrant*Test,*KusciaControlDatasource*Test,*ReadPartitionRule*Test" \
  -DfailIfNoTests=false
```

参数说明：

- `-Dtest="..."`：Surefire 通配符，匹配类名。多个模式用逗号分隔。
- `-DfailIfNoTests=false`：避免某些模块没有匹配测试时导致构建失败。

### 4.2 运行单个模块的测试

```bash
# secretpad-manager
mvn -pl secretpad-manager test \
  -Dtest="DatasourceManagerTest,KusciaDomainDatasourceRpcImplTest,DatatableGrantManagerTest"

# secretpad-service
mvn -pl secretpad-service test \
  -Dtest="MysqlKusciaControlDatasourceHandlerTest,OdpsKusciaControlDatasourceHandlerTest,ReadPartitionRuleAnalysisServiceImplTest"

# secretpad-web（需要先编译依赖模块）
mvn -pl secretpad-web -am test \
  -Dtest="DatasourceControllerTest,DatasourceControllerAutonomyTest,P2PDatasourceControllerTest,DatatableControllerTest,P2PDatatableControllerTest,FeatureDatasourceControllerTest,DataControllerTest"
```

### 4.3 运行全部测试

```bash
make test
```

等价于：

```bash
mvn clean test
```

## 5. Surefire 配置

### 5.1 根 POM 配置

- `maven-surefire-plugin` 版本：`3.0.0-M4`
- `jacoco-maven-plugin` 版本：`0.8.11`

### 5.2 `secretpad-web/pom.xml` 配置

```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-surefire-plugin</artifactId>
    <configuration>
        <testFailureIgnore>true</testFailureIgnore>
        <parallel>all</parallel>
        <threadCount>20</threadCount>
    </configuration>
</plugin>
```

说明：

- `testFailureIgnore=true`：单个测试失败不中断整个构建。
- `parallel=all`：类与方法均并行执行。
- `threadCount=20`：最多 20 线程。

### 5.3 `test/pom.xml` 配置

```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-surefire-plugin</artifactId>
    <configuration>
        <parallel>methods</parallel>
        <threadCount>10</threadCount>
    </configuration>
</plugin>
```

`test` 模块为聚合覆盖率模块，运行 `report-aggregate` 并合并各模块 JaCoCo 报告。

## 6. 测试报告位置

- 各模块 Surefire 报告：`secretpad-*/target/surefire-reports/`
- 聚合 JaCoCo 报告：`test/target/site/jacoco/index.html`
- CI 中上报文件：`test/target/site/jacoco.xml`

## 7. 本次运行结果

### 7.1 运行时间与命令

- 执行命令：
  ```bash
  mvn clean test \
    -Dtest="*Datasource*Test,*Datatable*Test,*DataController*Test,*DatatableGrant*Test,*KusciaControlDatasource*Test,*ReadPartitionRule*Test" \
    -DfailIfNoTests=false
  ```
- 首次运行总耗时：约 9 分 26 秒（其中 Maven 依赖下载占用较大比例）
- 构建状态：**BUILD SUCCESS**
- 完成时间：2026-06-22 18:41:11

### 7.2 结果摘要

| 模块 | 测试类 | 用例数 | 失败 | 错误 | 跳过 | 耗时 |
|------|--------|--------|------|------|------|------|
| secretpad-manager | DatatableGrantManagerTest | 9 | 0 | 0 | 0 | 2.17 s |
| secretpad-manager | DatasourceManagerTest | 1 | 0 | 0 | 0 | 0.352 s |
| secretpad-manager | KusciaDomainDatasourceRpcImplTest | 6 | 0 | 0 | 0 | 0.084 s |
| secretpad-service | MysqlKusciaControlDatasourceHandlerTest | 7 | 0 | 0 | 0 | 0.066 s |
| secretpad-service | ReadPartitionRuleAnalysisServiceImplTest | 1 | 0 | 0 | 0 | 0.21 s |
| secretpad-service | OdpsKusciaControlDatasourceHandlerTest | 9 | 0 | 0 | 0 | 2.333 s |
| secretpad-web | FeatureDatasourceControllerTest | 4 | 0 | 0 | 0 | 5.007 s |
| secretpad-web | DataControllerTest | 8 | 0 | 0 | 0 | 5.042 s |
| secretpad-web | P2PDatasourceControllerTest | 3 | 0 | 0 | 0 | 4.547 s |
| secretpad-web | DatatableControllerTest | 16 | 0 | 0 | 0 | 5.852 s |
| secretpad-web | P2PDatatableControllerTest | 3 | 0 | 0 | 0 | 22.139 s |
| secretpad-web | DatasourceControllerTest | 7 | 0 | 0 | 0 | 4.792 s |
| secretpad-web | DatasourceControllerAutonomyTest | 2 | 0 | 0 | 0 | 5.021 s |
| **合计** | **13 个测试类** | **76** | **0** | **0** | **0** | - |

```text
BUILD SUCCESS
Total time:  09:26 min
```

## 8. 常见问题

### 8.1 提示 `java: command not found`

未设置 `JAVA_HOME` 或 PATH。确认 `.tools/jdk-17` 存在并导出环境变量。

### 8.2 测试报证书或 `server.jks` 找不到

确认已执行 `scripts/test/setup.sh` 并将产物复制到 `config/` 目录（见 2.2）。

### 8.3 部分测试报数据库锁定或 SQLite 错误

测试使用内存/文件 SQLite，并发较高时可能冲突。可尝试：

```bash
mvn test -Dtest=... -Dspring.datasource.url=jdbc:sqlite:file:./db/test.db
```

或降低并行度：

```bash
mvn test -Dtest=... -DforkCount=1 -DreuseForks=false
```

### 8.4 某些 web 测试因端口占用失败

Spring Boot 测试会随机分配端口，一般无需处理。若频繁冲突，可检查系统端口占用情况：

```bash
ss -tlnp | grep -E '8080|8000'
```
