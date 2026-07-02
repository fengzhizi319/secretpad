# Alice.csv Demo 数据文件使用详解

## 📋 目录

- [概述](#概述)
- [文件位置与结构](#文件位置与结构)
- [数据内容说明](#数据内容说明)
- [完整使用流程](#完整使用流程)
  - [1. 数据提取与初始化](#1-数据提取与初始化)
  - [2. 数据存储布局](#2-数据存储布局)
  - [3. DataMesh 注册](#3-datamesh-注册)
  - [4. 跨域数据授权](#4-跨域数据授权)
  - [5. SecretPad 中使用](#5-secretpad-中使用)
- [技术架构](#技术架构)
- [应用场景](#应用场景)
- [常见问题](#常见问题)

---

## 概述

`alice.csv` 是 SecretPad 项目中的核心示例数据文件，用于演示隐私计算和联邦学习功能。该文件代表了参与方 "Alice" 持有的本地数据集，在典型的纵向联邦学习场景中，与 "Bob" 的数据集配合使用，展示如何在保护数据隐私的前提下进行多方联合建模。

**核心价值：**
- 🎯 **快速上手**：提供开箱即用的测试数据，无需准备真实业务数据
- 🔬 **功能验证**：验证数据导入、注册、授权、计算等完整流程
- 📚 **教学示范**：帮助用户理解 SecretPad 的工作原理和使用方法
- 🧪 **开发测试**：为开发者提供标准的测试数据集

---

## 文件位置与结构

### 源码位置

```
secretpad/
└── demo/
    └── data/
        ├── alice/
        │   └── alice.csv          # Alice 方的示例数据
        └── bob/
            └── bob.csv            # Bob 方的示例数据
```

### 运行时位置

安装部署后，数据会被复制到以下位置：

```
{INSTALL_DIR}/{PAD_MASTER}/{PAD_DATA}/
├── alice/
│   └── alice.csv                  # Alice 节点的数据副本
└── bob/
    └── bob.csv                    # Bob 节点的数据副本
```

默认情况下：
- `INSTALL_DIR`: `/opt/secretpad` 或用户指定目录
- `PAD_MASTER`: `master`
- `PAD_DATA`: `data`

---

## 数据内容说明

### 数据集特征

`alice.csv` 是一个经过数值编码的银行营销数据集，包含约 **9,894 条记录**。

#### 字段列表

| 字段名 | 类型 | 说明 | 示例值 |
|--------|------|------|--------|
| `id` | Integer | 样本唯一标识 | 0, 1, 2, ... |
| `age` | Float | 年龄（标准化） | 1.53, 1.27, 0.005 |
| `education` | Float | 教育程度编码 | -0.305, 1.197, -1.808 |
| `default` | Float | 是否有违约记录 | -0.117 |
| `balance` | Float | 账户余额（标准化） | 0.215, -0.479, -0.109 |
| `housing` | Float | 是否有住房贷款 | 1.036, -0.965 |
| `loan` | Float | 是否有个人贷款 | -0.393, 2.547 |
| `day` | Float | 最后联系日期 | -1.262, -1.142 |
| `duration` | Float | 通话时长（秒） | 1.905, 3.118, 2.896 |
| `campaign` | Float | 当前活动接触次数 | -0.576, -0.185 |
| `pdays` | Float | 上次活动后天数 | -0.485 |
| `previous` | Float | 之前活动接触次数 | -0.362 |
| `job_*` | Float | 职业类别（独热编码） | job_blue-collar, job_management 等 |
| `marital_*` | Float | 婚姻状况（独热编码） | marital_divorced, marital_married, marital_single |

#### 数据特点

1. **数值化处理**：所有分类变量已转换为数值形式（独热编码或标签编码）
2. **标准化**：连续变量已进行标准化处理（均值为0，方差为1）
3. **无表头说明**：第一行为列名，可直接作为 CSV 解析
4. **缺失值处理**：数据中可能包含空值或特殊标记

### 数据来源

该数据集源自经典的 **Bank Marketing Dataset**（银行营销数据集），常用于：
- 客户订阅定期存款预测
- 电话营销效果分析
- 二分类问题建模

在 SecretPad 中，该数据集被改造为纵向联邦学习场景，其中：
- **Alice** 持有部分特征（如人口统计学信息）
- **Bob** 持有另一部分特征（如金融行为数据）
- 双方通过 ID 对齐样本，共同训练模型

---

## 完整使用流程

### 1. 数据提取与初始化

#### 1.1 从 Kuscia 镜像提取

在安装过程中，执行脚本会自动从 Kuscia Docker 镜像中提取示例数据：

**脚本位置：**
- `scripts/install-kuscia-only.sh` (第 592-593 行)
- `scripts/install.sh` (第 530-531 行)

**提取命令：**

```bash
# 提取 Alice 数据
docker run --rm "$KUSCIA_IMAGE" \
  cat /home/kuscia/var/storage/data/alice.csv \
  > "$INSTALL_DIR"/"$PAD_MASTER"/"$PAD_DATA"/alice/alice.csv

# 提取 Bob 数据
docker run --rm "$KUSCIA_IMAGE" \
  cat /home/kuscia/var/storage/data/bob.csv \
  > "$INSTALL_DIR"/"$PAD_MASTER"/"$PAD_DATA"/bob/bob.csv
```

**关键参数：**
- `$KUSCIA_IMAGE`: Kuscia 容器镜像（如 `secretflow/kuscia:v1.x.x`）
- `$INSTALL_DIR`: SecretPad 安装根目录
- `$PAD_MASTER`: 主节点标识（通常为 `master`）
- `$PAD_DATA`: 数据目录名称（通常为 `data`）

#### 1.2 验证数据完整性

```bash
# 检查文件是否存在
ls -lh $INSTALL_DIR/master/data/alice/alice.csv
ls -lh $INSTALL_DIR/master/data/bob/bob.csv

# 查看行数
wc -l $INSTALL_DIR/master/data/alice/alice.csv
# 预期输出: 9894 (包含表头)

# 查看前几行
head -n 5 $INSTALL_DIR/master/data/alice/alice.csv
```

---

### 2. 数据存储布局

#### 2.1 卷挂载结构

SecretPad 容器启动时，会挂载以下数据卷：

```yaml
# docker run 参数示意
--volume="${PAD_INSTALL_DIR}":/app/data
--volume="${volume_path}/config":/app/config
--volume="${volume_path}/db":/app/db
--volume="${volume_path}/log":/app/log
```

**实际目录映射：**

```
宿主机路径                                    容器内路径
─────────────────────────────────────────────────────────
/opt/secretpad/master/data/alice/    →      /app/data/alice/
/opt/secretpad/master/data/bob/      →      /app/data/bob/
/opt/secretpad/master/config/        →      /app/config/
/opt/secretpad/master/db/            →      /app/db/
/opt/secretpad/master/log/           →      /app/log/
```

#### 2.2 数据持久化

- **数据文件**：存储在宿主机的 `data/` 目录，容器重启后保留
- **数据库**：SQLite/MySQL 数据存储在 `db/` 目录
- **日志文件**：运行时日志存储在 `log/` 目录
- **配置文件**：应用配置存储在 `config/` 目录

---

### 3. DataMesh 注册

#### 3.1 创建 DomainData 资源

Kuscia 通过 Kubernetes CRD（自定义资源定义）管理数据资源。安装脚本会执行以下命令注册数据表：

**脚本位置：**
- `scripts/install-kuscia-only.sh` (第 590-591 行)
- `scripts/install.sh` (第 528-529 行)

**Kuscia项目中的注册命令：**

```bash
# 注册 Alice 的数据表
docker exec -i "${USER}-kuscia-master" \
  scripts/deploy/create_domaindata_alice_table.sh alice

# 注册 Bob 的数据表
docker exec -i "${USER}-kuscia-master" \
  scripts/deploy/create_domaindata_bob_table.sh bob
```

#### 3.2 DomainData YAML 示例

`create_domaindata_alice_table.sh` 脚本内部会创建类似以下的 Kubernetes 资源：

```yaml
apiVersion: kuscia.io/v1alpha1
kind: DomainData
metadata:
  name: alice-table
  namespace: alice          # Alice 的命名空间
spec:
  domainID: alice           # 所属域
  type: File                # 数据类型：File/Database/API
  dataSource:
    localFile:
      path: /var/storage/data/alice.csv  # 容器内路径
      format: CSV
      delimiter: ","
      headerLines: 1
  properties:
    schema: |
      {
        "fields": [
          {"name": "id", "type": "INT"},
          {"name": "age", "type": "FLOAT"},
          {"name": "education", "type": "FLOAT"},
          ...
        ]
      }
```

#### 3.3 验证注册状态

```bash
# 查看 DomainData 资源
docker exec -i "${USER}-kuscia-master" \
  kubectl get domaindata -n alice

# 预期输出：
# NAME          AGE
# alice-table   2m

# 查看详细描述
docker exec -i "${USER}-kuscia-master" \
  kubectl describe domaindata alice-table -n alice

# 通过 Kuscia API 查询
docker exec -i "${USER}-kuscia-lite-alice" \
  curl -k https://127.0.0.1:8070/api/v1/datamesh/domaindata/list \
  -H "Authorization: Bearer $KUSCIA_TOKEN"
```

---

### 4. 跨域数据授权

#### 4.1 授权机制说明

在联邦学习场景中，参与方需要相互授权才能访问对方的数据元信息（非原始数据）。Kuscia 通过 **DomainDataGrant** 资源实现细粒度的数据授权控制。

**授权原则：**
- ✅ 仅授权元数据访问权限（表结构、字段名）
- ❌ 不授权原始数据访问权限
- 🔐 基于 mTLS 证书的双向认证
- 📋 最小权限原则（只授予必要的操作权限）

#### 4.2 执行授权

**脚本位置：**
- `scripts/install-kuscia-only.sh` (第 594-595 行)
- `scripts/install.sh` (第 532-533 行)

**授权命令：**

```bash
# Alice 授权 Bob 访问 alice-table
docker exec -i "${USER}-kuscia-lite-alice" \
  curl https://127.0.0.1:8070/api/v1/datamesh/domaindatagrant/create \
  -X POST \
  -H 'content-type: application/json' \
  -d '{
    "author": "alice",
    "domaindata_id": "alice-table",
    "grant_domain": "bob"
  }' \
  --cacert var/certs/ca.crt \
  --cert var/certs/ca.crt \
  --key var/certs/ca.key

# Bob 授权 Alice 访问 bob-table
docker exec -i "${USER}-kuscia-lite-bob" \
  curl https://127.0.0.1:8070/api/v1/datamesh/domaindatagrant/create \
  -X POST \
  -H 'content-type: application/json' \
  -d '{
    "author": "bob",
    "domaindata_id": "bob-table",
    "grant_domain": "alice"
  }' \
  --cacert var/certs/ca.crt \
  --cert var/certs/ca.crt \
  --key var/certs/ca.key
```

**关键参数：**
- `author`: 数据所有者（授权方）
- `domaindata_id`: 被授权的数据表 ID
- `grant_domain`: 被授权方（接收授权的域）
- `--cacert/--cert/--key`: mTLS 客户端证书

#### 4.3 验证授权状态

```bash
# 查询 Alice 的授权记录
docker exec -i "${USER}-kuscia-lite-alice" \
  curl -k https://127.0.0.1:8070/api/v1/datamesh/domaindatagrant/list \
  -H "Authorization: Bearer $KUSCIA_TOKEN" \
  | jq '.[] | select(.author == "alice")'

# 预期输出：
# {
#   "author": "alice",
#   "domaindata_id": "alice-table",
#   "grant_domain": "bob",
#   "status": "ACTIVE",
#   "created_at": "2024-01-01T00:00:00Z"
# }
```

#### 4.4 清理 DataProxy 临时表

安装脚本还会删除 DataProxy 相关的临时表：

```bash
# 删除 DataProxy 临时表
docker exec -i "${USER}-kuscia-master" \
  kubectl delete domaindata alice-dp-table -n alice
docker exec -i "${USER}-kuscia-master" \
  kubectl delete domaindata bob-dp-table -n bob
```

这是为了清理旧版本的遗留资源，避免冲突。

---

### 5. SecretPad 中使用

#### 5.1 登录 SecretPad Web 界面

安装完成后，访问：

```
http://localhost:8088
```

**默认凭据：**
- 用户名：`admin`（或在安装脚本中设置的 `$SECRETPAD_USER_NAME`）
- 密码：`secretpad`（或在安装脚本中设置的 `$SECRETPAD_PASSWORD`）

#### 5.2 查看数据资产

1. **进入数据管理页面**
   - 左侧菜单栏点击「数据管理」或「Data Management」
   - 选择对应的域（Alice 或 Bob）

2. **查看已注册的数据表**
   - 应能看到 `alice-table`（在 Alice 域下）
   - 应能看到 `bob-table`（在 Bob 域下）

3. **查看数据详情**
   - 点击数据表名称
   - 查看字段列表、数据类型、样本数量
   - 预览前 N 行数据（仅元数据，不显示完整数据）

#### 5.3 创建联邦学习任务

##### 5.3.1 PSI（隐私集合求交）

**目的**：找出 Alice 和 Bob 数据集中共同的样本 ID，而不泄露各自独有的 ID。

**操作步骤：**

1. 点击「新建任务」→「PSI」
2. 配置任务参数：
   ```
   任务名称: psi-demo
   发起方: Alice
   参与方: Bob
   数据源（Alice）: alice-table
   数据源（Bob）: bob-table
   ID 列: id
   ```
3. 提交任务
4. 查看结果：获得交集大小和加密后的交集 ID

##### 5.3.2 纵向联邦学习（VFL）

**目的**：利用 Alice 和 Bob 的特征共同训练一个机器学习模型。

**操作步骤：**

1. 点击「新建任务」→「联邦学习」→「纵向联邦学习」
2. 配置任务参数：
   ```yaml
   任务名称: vfl-demo
   算法类型: Logistic Regression / XGBoost
   发起方: Alice
   
   参与方配置:
     - 参与方: Alice
       数据源: alice-table
       特征列: [age, education, job_*]
       标签列: y (如果有)
     
     - 参与方: Bob
       数据源: bob-table
       特征列: [balance, housing, loan]
   
   训练参数:
     epochs: 10
     batch_size: 256
     learning_rate: 0.01
   ```
3. 提交任务
4. 监控训练进度
5. 查看模型评估指标（AUC、Accuracy 等）

##### 5.3.3 SCQL（安全协同查询语言）

**目的**：在不泄露原始数据的前提下执行 SQL 联合查询。

**操作步骤：**

1. 点击「新建任务」→「SCQL」
2. 编写安全查询：
   ```sql
   SELECT 
     COUNT(*) as total_count,
     AVG(a.age) as avg_age,
     SUM(b.balance) as total_balance
   FROM alice_table a
   INNER JOIN bob_table b ON a.id = b.id
   WHERE a.education > 0
   ```
3. 配置隐私保护级别
4. 执行查询
5. 查看加密计算结果

#### 5.4 查看任务执行日志

```bash
# 查看 SecretPad 后端日志
tail -f logs/backend.log

# 查看容器日志
docker logs -f secretpad-master

# 查看 Kuscia Job 状态
docker exec -i "${USER}-kuscia-master" \
  kubectl get kusciajob -A

# 查看具体 Job 详情
docker exec -i "${USER}-kuscia-master" \
  kubectl describe kusciajob <job-name> -n alice
```

---

## 技术架构

### 整体架构图

```
┌─────────────────────────────────────────────────────────────┐
│                     SecretPad Web UI                         │
│                  (http://localhost:8088)                     │
└──────────────────────┬──────────────────────────────────────┘
                       │ REST API
                       ▼
┌─────────────────────────────────────────────────────────────┐
│                   SecretPad Backend                          │
│              (Spring Boot Application)                       │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐      │
│  │ Task Manager │  │ Data Manager │  │ Job Converter│      │
│  └──────────────┘  └──────────────┘  └──────────────┘      │
└──────────────────────┬──────────────────────────────────────┘
                       │ Kuscia API Protocol
                       ▼
┌─────────────────────────────────────────────────────────────┐
│                    Kuscia Master                             │
│           (Kubernetes Control Plane)                         │
│  ┌──────────────────────────────────────────────────────┐  │
│  │  DomainData CRD  │  DomainRoute  │  KusciaJob CRD   │  │
│  └──────────────────────────────────────────────────────┘  │
└──────────┬──────────────────────┬──────────────────────────┘
           │                      │
     ┌─────▼──────┐         ┌────▼──────┐
     │  Alice     │         │   Bob     │
     │  Lite Node │         │ Lite Node │
     └─────┬──────┘         └────┬──────┘
           │                      │
     ┌─────▼──────────────────────▼──────┐
     │   alice.csv    │    bob.csv       │
     │   (alice-table)│  (bob-table)     │
     └───────────────────────────────────┘
```

### 数据流图

```
1. 数据初始化阶段
┌──────────────┐
│ Kuscia Image │
│  (内置数据)   │
└──────┬───────┘
       │ docker run --rm cat
       ▼
┌──────────────────┐
│ Host Volume      │
│ /opt/secretpad/  │
│   data/alice/    │
└──────┬───────────┘
       │ Mount
       ▼
┌──────────────────┐
│ Kuscia Lite      │
│ Container        │
│ /var/storage/    │
└──────┬───────────┘
       │ Register
       ▼
┌──────────────────┐
│ DomainData CRD   │
│ alice-table      │
└──────────────────┘

2. 联邦计算阶段
┌──────────────┐         ┌──────────────┐
│  Alice Data  │         │   Bob Data   │
│ alice-table  │         │  bob-table   │
└──────┬───────┘         └──────┬───────┘
       │                        │
       │    ┌───────────┐      │
       └───►│  PSI/VFL  │◄─────┘
            │  SCQL     │
            └─────┬─────┘
                  │ Encrypted Result
                  ▼
            ┌───────────┐
            │  Result   │
            └───────────┘
```

### 关键技术组件

| 组件 | 版本 | 作用 |
|------|------|------|
| **Kuscia** | v1.x.x | 隐私计算调度框架，基于 Kubernetes |
| **SecretFlow** | latest | 联邦学习引擎 |
| **SCQL** | latest | 安全协同查询引擎 |
| **DataProxy** | - | 数据代理，提供统一数据访问接口 |
| **Spring Boot** | 2.7+ | SecretPad 后端框架 |
| **React** | 18+ | SecretPad 前端框架 |

---

## 应用场景

### 1. 金融行业联合风控

**场景描述**：
- 银行 A（Alice）持有客户的信贷记录
- 银行 B（Bob）持有客户的消费行为
- 双方希望联合训练风控模型，但不愿共享原始数据

**使用 alice.csv 模拟**：
- Alice 的特征：年龄、教育、职业
- Bob 的特征：余额、贷款、住房
- 联合预测：客户违约概率

### 2. 医疗数据协作研究

**场景类比**：
- 医院 A 持有患者的基因数据
- 医院 B 持有患者的临床病历
- 合作研究疾病预测模型

**技术要点**：
- 通过 PSI 找到共同患者群体
- 使用 VFL 训练预测模型
- 数据始终保留在本地

### 3. 广告归因分析

**场景描述**：
- 媒体平台（Alice）掌握用户曝光点击数据
- 电商平台（Bob）掌握用户购买转化数据
- 联合分析广告投放效果

**隐私保护**：
- 不泄露用户身份
- 不泄露商业机密
- 仅输出统计结果

### 4. 政务数据融合

**场景描述**：
- 税务局持有企业纳税信息
- 社保局持有企业社保缴纳信息
- 联合评估企业经营状况

**合规要求**：
- 符合《数据安全法》
- 符合《个人信息保护法》
- 满足 GDPR 要求

---

## 常见问题

### Q1: alice.csv 和 bob.csv 有什么区别？

**A**: 
- **字段不同**：两者包含不同的特征列，模拟不同机构持有的数据
- **样本重叠**：部分 ID 同时存在于两个文件中，用于 PSI 求交
- **用途互补**：在纵向联邦学习中，双方的特征合并形成完整样本

### Q2: 如何替换为自己的业务数据？

**A**: 有两种方式：

**方式一：手动上传（推荐）**
1. 登录 SecretPad Web 界面
2. 进入「数据管理」→「上传数据」
3. 选择 CSV 文件，填写元信息
4. 系统自动注册到 DataMesh

**方式二：命令行导入**
```bash
# 1. 将数据文件复制到数据目录
cp your_data.csv $INSTALL_DIR/master/data/alice/

# 2. 创建 DomainData YAML
cat > your-data.yaml <<EOF
apiVersion: kuscia.io/v1alpha1
kind: DomainData
metadata:
  name: your-data
  namespace: alice
spec:
  domainID: alice
  type: File
  dataSource:
    localFile:
      path: /var/storage/data/your_data.csv
      format: CSV
EOF

# 3. 应用到集群
docker exec -i "${USER}-kuscia-master" \
  kubectl apply -f your-data.yaml -n alice
```

### Q3: 数据量有限制吗？

**A**: 
- **示例数据**：alice.csv 约 10K 行，适合快速测试
- **生产环境**：支持百万级甚至千万级数据
- **性能优化**：大数据量建议启用分区、索引、缓存

### Q4: 数据是否会被发送到对方节点？

**A**: **绝对不会**。SecretPad 采用隐私计算技术：
- ✅ 原始数据始终保留在本地
- ✅ 仅传输加密的中间结果（梯度、激活值等）
- ✅ 通信通道使用 mTLS 加密
- ✅ 符合"数据不动价值动"原则

### Q5: 如何删除示例数据？

**A**: 

```bash
# 1. 删除 DomainData 资源
docker exec -i "${USER}-kuscia-master" \
  kubectl delete domaindata alice-table -n alice

# 2. 删除授权记录
docker exec -i "${USER}-kuscia-lite-alice" \
  curl -X DELETE \
  https://127.0.0.1:8070/api/v1/datamesh/domaindatagrant/delete \
  -H 'content-type: application/json' \
  -d '{"domaindata_id": "alice-table", "grant_domain": "bob"}'

# 3. （可选）删除物理文件
rm $INSTALL_DIR/master/data/alice/alice.csv
```

### Q6: 安装脚本失败，数据未正确初始化怎么办？

**A**: 可以手动重新执行初始化步骤：

```bash
# 1. 重新提取数据
bash scripts/install-kuscia-only.sh master

# 2. 或者单独执行数据初始化
source scripts/install-kuscia-only.sh
add_alice_bob_data

# 3. 验证数据
docker exec -i "${USER}-kuscia-master" \
  kubectl get domaindata -n alice
```

### Q7: 能否在 P2P 模式下使用 alice.csv？

**A**: 可以。P2P 模式（去中心化）与 Edge 模式（中心化）都支持示例数据：

```bash
# P2P 模式部署
export DEPLOY_MODE=p2p
bash scripts/install.sh

# 数据会自动初始化到各个节点
```

### Q8: 数据的 Schema 在哪里定义？

**A**: Schema 定义在多个位置：

1. **Kuscia DomainData CRD**：`spec.properties.schema`
2. **SecretPad 数据库**：`project_datatable` 表
3. **前端展示**：通过 API 动态获取

查看 Schema：
```bash
docker exec -i "${USER}-kuscia-master" \
  kubectl get domaindata alice-table -n alice -o yaml \
  | grep -A 50 "schema"
```

---

## 附录

### A. 相关脚本清单

| 脚本文件 | 功能 | 关键函数/行号 |
|---------|------|--------------|
| `scripts/install.sh` | 完整安装脚本 | `add_alice_bob_data()` L528-537 |
| `scripts/install-kuscia-only.sh` | 仅安装 Kuscia | `add_alice_bob_data()` L589-596 |
| `scripts/deploy/secretpad.sh` | 启动 SecretPad | `start()` L292-379 |
| `scripts/deploy/create_domaindata_alice_table.sh` | 注册 Alice 数据 | - |
| `scripts/deploy/create_domaindata_bob_table.sh` | 注册 Bob 数据 | - |

### B. API 端点参考

| 端点 | 方法 | 说明 |
|------|------|------|
| `/api/v1/datamesh/domaindata/list` | GET | 列出所有 DomainData |
| `/api/v1/datamesh/domaindata/create` | POST | 创建 DomainData |
| `/api/v1/datamesh/domaindatagrant/create` | POST | 创建数据授权 |
| `/api/v1/datamesh/domaindatagrant/list` | GET | 列出授权记录 |

### C. 常量定义

在代码中，alice-table 被定义为常量：

```java
// secretpad-common/src/main/java/org/secretflow/secretpad/common/constant/DomainConstants.java
public static final String ALICE_TABLE = "alice-table";
public static final String BOB_TABLE = "bob-table";
```

### D. 参考资料

- [SecretPad 官方文档](docs/index.rst)
- [Kuscia 集成指南](docs/KUSCIA_SECRETFLOW_INTEGRATION.md)
- [联邦学习流程说明](docs/FEDERATED_LEARNING_FLOW.md)
- [本地部署指南](docs/deployment/local_deploy_secretpad_kuscia_datamesh.md)

---

## 总结

`alice.csv` 作为 SecretPad 的核心示例数据，贯穿了整个隐私计算的生命周期：

1. **📥 数据初始化**：从 Kuscia 镜像提取到本地存储
2. **📝 元数据注册**：创建 DomainData CRD 资源
3. **🔐 跨域授权**：建立参与方之间的数据访问权限
4. **🚀 任务执行**：在 PSI、VFL、SCQL 等场景中使用
5. **📊 结果展示**：通过 Web UI 查看计算结果

通过这个完整的流程，用户可以快速理解和体验 SecretPad 的核心功能，为后续接入真实业务数据打下基础。

---

**文档版本**: v1.0  
**更新日期**: 2024-06-30  
**维护者**: SecretPad Team
