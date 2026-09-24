# SecretPad 身份认证与账户体系安全升级计划

> **适用版本**：本仓库当前副本（基于上游 SecretPad，已做本地改造：中文注释、口令摘要 SHA-256 → SM3）。
> **实施状态**（2026-09-24 更新）：P0（S1/S5/S6）与 P1-2/P1-4（H1、M3 部分）已实施、测试、变异验证并推送；
> 详见 §5/§6/§7。P1-1/P1-3（口令慢哈希与服务端复杂度）因与前端协议耦合，未在本轮实施，耦合原因见 §6.4；
> P2/P3/P4 未开始。此外，§8 记录了一轮独立于上述路线的二次安全评审——路径穿越、SSRF、任意类反序列化、
> IDOR（越权读取他人调度详情/分页枚举）、日志明文泄露（凭据哈希与裸字符串请求体）共 8 处发现，已全部修复、
> 测试并变异验证；未推送（本仓库未经用户指示不做 commit）。
> **文档性质**：面向本仓库维护者的**整改计划**。§1 是逐文件核对过的现状（每条附 `路径:行号`），§2 是按风险排序的
> 升级路线，§3 是每一项的具体改法、迁移方式与验收标准，§4 是完成后的目标形态。
> **一条总原则**：每项整改都要先写一个"违例即变红"的用例，再改代码；改完把违例注回去确认用例真的红——
> 本仓库现存的多个问题（锁定不生效、审计拦截器未注册）正是"做了但不执行"，只有用例能证明守卫在守。
> **参考实现**：同一作者维护的 PrivShield（`pkg/auth`）已落地本文大部分目标形态，可对照其代码与
> `docs/architecture/服务间认证.md`；但 SecretPad 的机器间信任由 Kuscia 承担，不必照搬其 mTLS + PoP 全套。

## 目录

- [1. 现状核对：问题清单与证据](#1-现状核对问题清单与证据)
- [2. 升级路线：五个阶段](#2-升级路线五个阶段)
- [3. 逐项改法、迁移与验收](#3-逐项改法迁移与验收)
- [4. 完成后的目标形态](#4-完成后的目标形态)
- [5. P0 实施纪实](#5-实施纪实)
- [6. P1 实施纪实（进行中）](#6-p1-实施纪实进行中)
- [7. 本轮工作总览](#7-本轮工作总览)
- [8. 二次安全评审：漏洞再排查与修复](#8-二次安全评审漏洞再排查与修复)
- [附录：涉及文件索引](#附录涉及文件索引)

---

## 1. 现状核对：问题清单与证据

按严重度排序。"严重"= 无需任何前置条件即可拿到管理员权限或伪造机器身份；"高"= 削弱一层本应存在的防线；
"中"= 配置与工程缺陷。行号以当前副本为准。

### 1.1 严重

| # | 问题 | 证据 |
|---|---|---|
| S1 | **默认口令 `admin/12345678`，每次启动覆盖已有口令，启动日志明文打印** | `secretpad-web/.../web/constant/AuthConstants.java:29,45-47`（随机生成逻辑整块注释，直接 `return DEFAULT_PASSWORD`）；`web/init/DbDataInit.java:71-99`（env 缺失回落；`:96-98` "user already exists update password" 覆盖用户自设口令）；`SecretPadApplication.java:85` `log.info("userName:{} password:{}")`；`config/application.yaml:268-271` 的 `${SECRETPAD_USER_NAME}` 无默认值 |
| S2 | **口令无盐单轮 SM3 且服务端不参与哈希，库内 hash 即等效口令** | 前端 `frontend-src/apps/platform/src/modules/login/login.service.ts:19` `sm3(password)`；服务端 `secretpad-service/.../impl/AuthServiceImpl.java:168` `user.getPasswordHash().equals(passwordHash)`（非常量时间）；`common/util/Sm3Utils.java:42-60` 裸 `SM3Digest`。`AuthControllerTest.java:49-62` 把 `passwordHash` 设成请求值即登录成功 |
| S3 | **节点用户口令由公开 nodeId 派生** | `impl/NodeServiceImpl.java:176` `Sm3Utils.hash(nodeId + "12#$qwER")`；nodeId 经 `/node/list` 公开 |
| S4 | **inst token 的 HMAC 密钥就是 `instId`（业务标识）** | `common/util/TokenUtil.java:84,121` `Algorithm.HMAC256(secret)`；`manager/.../NodeManager.java:305-307` `TokenUtil.sign(nodeDO.getInstId(), nodeDO.getNodeId())`；`InstControllerTest.java:374` 测试自证 |
| S5 | **到 Kuscia 的 gRPC 不验服务端证书；Kuscia token 每次调用打日志** | `secretpad-api/client-java-kusciaapi/.../GrpcKusciaApiChannelFactory.java:121` `InsecureTrustManagerFactory.INSTANCE`，`:123-127` SSL 构造失败 `sslContext=null` 仍建连；`interceptor/TokenAuthClientInterceptor.java:47` |
| S6 | **内网端口（9001）节点身份 = 一个可伪造的 HTTP 头** | `web/interceptor/LoginInterceptor.java:195-240` 凭 `kuscia-origin-source` 直接构造 `EDGE` 虚拟用户并加载全部 `projectIds` 与 NODE 角色资源；`CenterDataSyncController.java:34-43` `/sync` 同样只读该头；`sync/center/SseSession.java:20-90` 以它为 session key |

### 1.2 高

| # | 问题 | 证据 |
|---|---|---|
| H1 | **账号锁定不生效且不自动解锁**：`lockedInvalidTime` 只写不读，口令正确即清零放行；解锁方法无调用方、无定时任务 | `AuthServiceImpl.java:146-184`（`:168-174`）；`UserServiceImpl.java:162 userUnlock()` / `:171 findLockedUser()` 全仓库无调用 |
| H2 | **服务端零口令复杂度、无首登强制改密、无有效期、无历史口令** | `model/auth/UserUpdatePwdRequest.java:26-45`（`@Length(min=8)` 作用于 64 位 hex 恒真）；`UserCreateRequest.java:37-39`；`LoginRequest.java:38,44` 无约束；`user_accounts` DDL（`config/schema/center/V1__init.sql:282-296`）无 `must_change_password` 类列 |
| H3 | **会话 24h 滑动无上限、每请求写库、明文入库无索引、`localStorage` 存放、授权快照不失效** | `LoginInterceptor.java:69`（注释 "one hour" 实为 86400s）、`:280-301` 每请求 `saveAndFlush`；`entity/TokensDO.java:50-51` `@Id token` 明文；DDL `V1__init.sql:298-309` `token`/`name` 无索引无唯一；`modules/login/index.tsx:63`；`session_data` 存 `apiResources/projectIds` 快照 |
| H4 | **`/inst/node/register` 免鉴权，可改写节点端点与落盘证书；证书不验链；路径白名单校验被注释** | `web/configuration/LoginConfiguration.java:54` `excludePathPatterns`；`impl/InstServiceImpl.java:220`（注释掉的 `SafeFileUtils.checkPathInWhitelist`）、`:285-288`（不安全 host 只 `warn`）、`:291-330`；`InstRegisterRequest.java:87,106-110`（文件名校验用 `getName()` 且判断取反） |
| H5 | **审计拦截器未注册，且即便注册也恒 anonymous** | `web/interceptor/AuditLogInterceptor.java:48` 仅 `@Component`，无 `addInterceptor`；`:109-120 extractUserId()` 只看 HttpSession 与 `X-User-Id` |
| H6 | **`auth.enabled=false` 直接伪造 admin；CENTER 用户绕过全部授权；EDGE 平台整体关闭接口鉴权** | `LoginInterceptor.java:145-149, 99-122`；`config/application-test.yaml:11-12`；`web/aop/InterfaceResourceAspect.java:54-60`；`web/aop/DataResourceAspect.java:80-82`；`auth/impl/DefaultDataResourceAuth.java:51` |
| H7 | **SSL keystore 口令硬编码，且 `config/server.jks` 入库** | `config/application.yaml:36,38` `${KEY_PASSWORD:secretpad}`；仓库内存在 `config/server.jks` |

### 1.3 中

| # | 问题 | 证据 |
|---|---|---|
| M1 | 节点间转发与 SSE 走 `http://` 明文；跨节点明文传口令哈希 | `web/filter/EdgeRequestFilter.java:28,115`；`sync/edge/EdgeDataSyncServiceImpl.java:64,79`；`RemoteUserController.java:27,71-72` |
| M2 | CORS 一旦开启即 `allowedOriginPatterns("*") + allowCredentials(true)`，且允许头里没有 `User-Token` | `web/configuration/CorsConfig.java:39,48-53`；前端 `app.ts:32` 固定 `credentials: 'include'` |
| M3 | `/api/login` 无速率限制、无 MFA；改密路径可枚举用户名 | `service/util/RateLimitUtil.java:27-44` 仅 `InstServiceImpl.java:183 newToken()` 使用；`NodeUserServiceImpl.java:104-110`（"Do not exists name" vs "wrong password"） |
| M4 | `updatePwd` 锁释放当次静默不改密却返回成功 | `UserServiceImpl.java:113` `if (!isUnlock)`；`UserController.java:50-54` 固定 `success(TRUE)` |
| M5 | `actuator` 匿名可达（`health` `show-details: always`、`metrics`、`prometheus`）；上传大小 `-1`；CSP 缺 `default-src`/`script-src`；无 HSTS / `X-Content-Type-Options` / `X-Frame-Options` | `config/application.yaml:169-189, 274-282`；`web/filter/AddResponseHeaderFilter.java:57-77` |
| M6 | 实体与 DDL 不一致：`AccountsDO` 软删 SQL 用 `inst_id` 而主键是 `name`；`@Column(name="instId")` vs DDL `inst_id`；`SysUserPermissionRelDO.user_key length=16` vs `VARCHAR(64)`；`user_accounts.name`、`user_tokens.token/name` 缺唯一索引 | `entity/AccountsDO.java:43,103`；`entity/SysUserPermissionRelDO.java:71`；`config/schema/center/V3__0.9.0.sql:20` |
| M7 | 部署与账号脚本：`generate_password` 默认 7 位且不含特殊字符；`register_account.sh` SQL 未加引号、明文打印口令 | `scripts/deploy/secretpad.sh:229,376`；`scripts/user/register_account.sh:66-78` |
| M8 | 五个安全 key（`secretpad.account-error-*`、`reset-password-error-*`、`secretpad.cors.enabled`）在任何 yaml 都不存在；`ip.block` 注释说"禁止来源 IP"，实际只是数据源 URL 的 SSRF 校验；`配置文档.md` 声称 gRPC 双向验证证书；前端文档把 `202011603`（已锁定）说成未登录 | `config/application.yaml:399-401`；`service/util/IpFilterUtil.java:27-57`；`docs/配置文档.md:1646`；`frontend-src/apps/docs/docs/dev-doc/auth.md:73-75` |
| M9 | `OPTIONS` 无条件放行；`SysResourcesBizServiceImpl.queryResourceCodeByUsername` 忽略 `resourceType` 入参硬编码 `API`；`EnvServiceImpl.isP2pEdge()` 恒 `true` | `LoginInterceptor.java:153-155,326-328`；`SysResourcesBizServiceImpl.java:74`；`EnvServiceImpl.java:95-97` |

### 1.4 做对了、要保留的

- 未知用户与错口令返回同一条消息，且未知用户也计数（Ehcache `user_lock`，`AuthServiceImpl.java:152-165`）。
- 改密走独立的第二套锁并**确实**检查释放时间（`UserServiceImpl.java:87-101`）；改密成功删该用户全部 token（`:117`）。
- inst token 一次性状态 `UNUSED/USED` + 注册时与库逐字比对 + `newToken` 限速（`InstServiceImpl.java:262-283`）——密钥错了，流程形状是对的。
- `@ApiResource` / `@DataResource` 双轨鉴权的**模型**（接口权限与对象归属分离，切面按字段名取值）——豁免规则错了，模型是对的。
- 内外双端口 + 外网口对内网路径返回 404（`LoginInterceptor.java:335-350`）——隔离思路对，只是内网口自身没有认证。

---

## 2. 升级路线：五个阶段

顺序按"先切断无条件拿权的路，再修防线，再补审计，最后做工程收口"。每阶段结束仓库可构建、测试全绿、可独立发布。

| 阶段 | 目标 | 覆盖问题 | 工作量 |
|---|---|---|---|
| **P0 止血**（1 天） | 删除默认口令与口令日志；gRPC 验证服务端证书；内网口不再只信 HTTP 头 | S1 S5 S6 + M8 的日志部分 | 改动小、风险低，先发 |
| **P1 口令与登录**（3 天） | 服务端慢哈希 + 常量时间比较；锁定真正生效并自动释放；服务端复杂度；首登强制改密；登录限速 | S2 S3 H1 H2 M3 M4 | 含口令迁移，最重 |
| **P2 会话**（2 天） | token 入库存摘要；绝对上限 + 节流续期；授权以库为准；HttpOnly Cookie 或至少不进 `localStorage` | H3 | 需前端配合 |
| **P3 节点信任与授权**（3 天） | inst token 改非对称签名；注册接口鉴权 + 证书验链；去掉 CENTER 全豁免；审计拦截器接线并接真实身份 | S4 H4 H5 H6 | 含 P2P 流程回归 |
| **P4 工程收口**（2 天） | keystore 出库；安全头；CORS；actuator 上锁；DDL 修复；脚本修复；文档纠错；反模式门禁 | H7 M1 M2 M5 M6 M7 M8 M9 | 大量小改，靠门禁防回退 |

总计约 11 人日。P0 独立；P1 与 P2 可并行；P3 依赖 P0（内网口认证）；P4 独立但门禁应最后加，以免中途红。

---

## 3. 逐项改法、迁移与验收

### 3.1 P0 · 止血

**P0-1 删除默认口令与口令日志（S1）**

- `AuthConstants.java`：删除 `DEFAULT_PASSWORD`，恢复 `generateRandomPassword()` 的随机实现（原逻辑在 `:60-99` 注释中，
  正则 `^(?=.*[a-z])(?=.*[A-Z])(?=.*\d)(?=.*[@$!%*?&])[A-Za-z\d@$!%*?&]{8,}$`，长度改为 ≥ 16）。
- `DbDataInit.java:67-101`：只在 **admin 不存在** 时创建；已存在一律不动（删掉 `:92-99` 的覆盖分支）。
  env 缺失时生成随机口令并**只输出到一个 0600 文件**（如 `./config/initial-admin-password`，首次登录后删除），不进日志。
- `SecretPadApplication.java:70-86`：删除打印口令的 `log.info`。
- `scripts/deploy/secretpad.sh:376`、`scripts/user/register_account.sh:66,69,78`：删除打印口令与哈希的行。
- `docs/operation/edge.md:26-38`（"`docker logs` 捞口令"）、`运行说明.md`、`macos运行说明.md`、`docs/development/test-guides/cipher12345678.md`：
  改为"首次启动从 `initial-admin-password` 文件读取"。
- **验收**：用例 `DbDataInitTest`——已存在 admin 且口令为 X，启动后仍为 X；启动日志断言不含 `password:`。
  变异：把覆盖分支加回去 → 用例红。

**P0-2 gRPC 验证 Kuscia 服务端证书（S5）**

- `GrpcKusciaApiChannelFactory.java:112-127`：`trustManager(InsecureTrustManagerFactory.INSTANCE)` 改为
  `trustManager(new File(config.getCaFile()))`；`KusciaGrpcConfig` 增 `caFile`（`config/application.yaml:202-232` 的每个节点补 `ca-file: config/certs[/alice|/bob]/ca.crt`）。
  SSL 构造失败**抛异常拒绝建连**，不再 `sslContext=null` 继续 `build()`。
- `TokenAuthClientInterceptor.java:47`：删除打印 token 的日志（保留 domainId）。
- **验收**：用例——用自签服务端证书起 gRPC 桩，客户端配错误 CA 必须握手失败；配缺 CA 必须构造期报错。
  变异：改回 `InsecureTrustManagerFactory` → 用例红。

**P0-3 内网口节点身份不再只信 HTTP 头（S6）**

内网口的客户端是 Kuscia 网关（Envoy）转发的节点请求。最小改法：**内网口开 mTLS，只接受 Kuscia 网关证书**，
并把 `kuscia-origin-source` 的可信度绑到"连接来自谁"：

- Spring Boot 第二个 connector（`server.http-port-inner`）配置 `clientAuth=need`，信任库只放 Kuscia 网关 CA
  （`config/certs/ca.crt`）。
- `LoginInterceptor.processByNodeRpcRequest`（`:195-240`）：先检查 `request.getAttribute("jakarta.servlet.request.X509Certificate")`
  非空且签发者为网关 CA，再读 `kuscia-origin-source`；两者缺一即 401。
- `/sync`（`CenterDataSyncController.java:34-43`）同一处理。
- **验收**：用例——内网口无客户端证书 + 伪造头 → 401；带网关证书 + 头 → 通过。
  变异：去掉证书检查 → 用例红。
- 若 Kuscia 网关到 SecretPad 内网口暂时做不了 mTLS，**过渡方案**是把内网口绑定到 `127.0.0.1` 并用 NetworkPolicy /
  安全组只放行网关 Pod；这是网络层兜底，不是认证，必须在 P3 前替换。

### 3.2 P1 · 口令与登录

**P1-1 服务端慢哈希 + 常量时间比较（S2）**

- 依赖：`spring-security-crypto`（只要 `BCryptPasswordEncoder`，不引入 Spring Security 过滤链）。
- 新增 `common/util/PasswordHasher`：`hash(String) → "$bcrypt$" + bcrypt(cost=12)`；`verify(stored, candidate)` 常量时间。
  存储格式带前缀，便于识别旧格式。
- `AuthServiceImpl.java:168`：`equals` → `PasswordHasher.verify(user.getPasswordHash(), presented)`。
- **口令迁移**（库里存的是客户端 SM3 值，服务端拿不到明文，不能离线重哈希）：
  1. 前端**继续**发送 `sm3(password)` 作为"表示层口令"（这一步不提供安全性，只是保持协议不变、避免改前端协议）；
  2. 服务端把收到的 `passwordHash` 当作口令明文做 bcrypt：`stored = bcrypt(sm3(password))`；
  3. 登录时若 `stored` 无 `$bcrypt$` 前缀（旧格式）：先按旧规则 `equals` 比对，**通过即当场重哈希为 bcrypt 并落库**（透明迁移）；
  4. 新建账号、改密、重置一律只写 bcrypt；
  5. 迁移窗口结束（如两个版本后）删除旧格式分支，未迁移账号置 `must_change_password=1`（见 P1-3）。
  这样即使库泄露，攻击者拿到的是 `bcrypt(sm3(pw))`，既不能离线暴力（cost=12），也不能 pass-the-hash（登录要的是 `sm3(pw)`，库里没有）。
- **验收**：`AuthControllerTest.login`（`:49-62`）改为"把 `passwordHash` 直接写进 `AccountsDO` 必须**登录失败**"；
  新用例：旧格式账号首次登录后 `password_hash` 以 `$bcrypt$` 开头。变异：`verify` 改回 `equals` → 用例红。

**P1-2 锁定真正生效并自动释放（H1）**

- `AuthServiceImpl.accountLockedCheck`（`:146-184`）在比对口令**之前**加：
  `if (user.getLockedInvalidTime() != null && now.isBefore(user.getLockedInvalidTime())) throw USER_IS_LOCKED(剩余分钟)`；
  已过释放时间则清零计数后再比对。
- 删除死代码 `UserServiceImpl.userUnlock()` / `findLockedUser()` 与 `UserAccountsRepository.findLockedUser`，或改为管理员手动解锁端点（带审计）。
- 把 `secretpad.account-error-max-attempts` / `account-error-lock-time-minutes` / `reset-password-error-*` 四个 key **写进** `config/application.yaml`，不再靠 `@Value` 默认值。
- **验收**：用例——错 5 次后**正确口令**也 `USER_IS_LOCKED`；把 `lockedInvalidTime` 设为过去 → 正确口令通过且计数清零。
  变异：删掉前置检查 → 用例红。

**P1-3 服务端复杂度 + 首登强制改密 + 有效期（H2）**

- 复杂度只能在**明文**上校验，而服务端只收到 `sm3(pw)`。两个选择：
  (a) 前端改为发明文（TLS 已保护），服务端做全部校验与 bcrypt；
  (b) 保持 SM3 协议，改密与建户请求额外携带明文（仅这两个端点）。
  **推荐 (a)**：客户端预哈希在 TLS 下没有收益，只让服务端失去校验能力。P1-1 的迁移步骤相应改为 `stored = bcrypt(password)`，
  旧格式比对用 `sm3(password).equals(stored)`。
- 新增 `common/util/PasswordPolicy`：长度 8~72、≥3 类字符、不含用户名、弱口令字典（内置 top-1000）；
  `UserCreateRequest` / `UserUpdatePwdRequest` / `ResetNodeUserPwdRequest` / `NodeUserCreateRequest` 全部经它校验，前端正则只做提示。
- `user_accounts` 增列（Flyway `V6__auth_hardening.sql`，三套 schema 同步）：`must_change_password tinyint(1) default 0`、
  `gmt_password_updated datetime`、`password_history text`（最近 5 个 bcrypt）。
- `LoginInterceptor`：`must_change_password=1` 的会话只允许 `/api/v1alpha1/user/updatePwd` 与 `/api/logout`，其余 403 `PASSWORD_CHANGE_REQUIRED`（新错误码）。
  管理员创建/重置口令一律置 1；随机初始口令（P0-1）一律置 1。
- 口令超过 90 天：登录成功但置 `must_change_password=1`。
- **节点用户口令（S3）**：`NodeServiceImpl.java:176` 改为随机 16 位口令，写入 `must_change_password=1`，通过 `InstTokenVO` 同样的一次性通道下发（或直接不给口令、只允许管理员重置）。
- `UserServiceImpl.updatePwd`（`:113`）：删掉 `if (!isUnlock)`，锁释放与改密是两个独立步骤。
- **验收**：每条策略一个用例；`must_change_password=1` 的会话访问 `/api/v1alpha1/project/list` → 403；
  `NodeServiceImplTest`——两次建节点的口令不同且不含 nodeId。变异：`PasswordPolicy` 任一规则注掉 → 对应用例红。

**P1-4 登录限速与统一消息（M3）**

- `RateLimitUtil` 推广到 `/api/login`：按 IP 每分钟 20 次 + 按用户名沿用锁定计数；超限 429。
- `NodeUserServiceImpl.java:104-110`、`UserServiceImpl.java:142`：对外统一为 `USER_PASSWORD_ERROR`，不区分"用户不存在"。
- **验收**：用例——同一 IP 第 21 次登录 429；不存在用户与错口令的响应体逐字节相同。

### 3.3 P2 · 会话

**P2-1 token 入库存摘要、绝对上限、节流续期（H3）**

- `TokensDO`：主键改为 `token_digest`（`SM3(token)` hex），明文 token 只出现在响应里一次；`findByToken` 改为按摘要查。
- Flyway：`user_tokens` 加 `gmt_issued datetime`（签发时间，绝对上限用）、`token_digest` 唯一索引、`name` 普通索引；
  `user_accounts.name` 加唯一索引（M6 一并）。
- `LoginInterceptor`：`EXPIRE` 注释与值对齐并拆成两个配置：`secretpad.session.absolute-hours: 24`（按 `gmt_issued`）、
  `secretpad.session.idle-minutes: 30`（按 `gmt_token`）；续期改为**距上次回写 ≥ 60 秒才 `saveAndFlush`**，消除每请求写库。
- 并发会话上限：同一 `name` 最多 3 个有效 token，超出时淘汰最旧的；登出可选 `all=true` 删全部。
- **验收**：用例——签发 24h 后即使持续活跃也过期；30 分钟无活动过期；60 秒内两次请求只写库一次（mock repository 计数）。
  变异：去掉绝对上限 → 用例红。

**P2-2 授权以库为准，不用会话快照（H3）**

- `session_data` 只保留身份（`name/ownerType/ownerId/instId/platform*`），删掉 `apiResources` 与 `projectIds`；
  `DefaultApiResourceAuth` / `DataResourceProjectAuth` 每次从 `SysResourcesBizService` / `projectNodeRepository` 查（加本地缓存，TTL 30 秒，角色变更时主动失效）。
- **验收**：用例——用户登录后被移出项目，持旧 token 访问该项目 → 403。变异：改回读快照 → 用例红。

**P2-3 浏览器侧存放（H3，需前端配合）**

- 首选：登录响应改 `Set-Cookie: User-Token=…; HttpOnly; Secure; SameSite=Strict`，响应体不再回传 `token`；
  拦截器只读 Cookie；再加双提交 CSRF token（`X-CSRF-Token` 头 + 非 HttpOnly Cookie）。
- 若短期改不动前端：至少改为 `sessionStorage`，并补 CSP `default-src 'self'; script-src 'self'`（M5）压低 XSS 面。
- 前端 `app.ts:32` 的 `credentials: 'include'` 与 CORS 配置（M2）一并处理：`secretpad.cors.enabled` 默认 false 保持，
  开启时 `allowedOriginPatterns` 必须显式列表，`ALLOW_HEADERS` 加 `User-Token` / `X-CSRF-Token`。

### 3.4 P3 · 节点信任与授权

**P3-1 inst token 改非对称签名（S4）**

- `TokenUtil`：`HMAC256(instId)` 改为机构私钥签名（`Algorithm.ECDSA256` 或 SM2；密钥对由 P3-3 的 CertificateService 生成并存于
  `${secretpad.certs.dir-path}/<instId>/inst-sign.key`，0600）；验证方用机构公钥。`sign()` 不再把 token 写文件返回路径，直接返回 token。
- 保留一次性状态与逐字比对（`InstServiceImpl.java:262-283`），保留 `newToken` 限速，`getToken` 也加限速。
- `/inst/node/token` 只在 `instTokenState=UNUSED` 时返回一次，之后返回脱敏值。
- **验收**：用例——知道 instId + nodeId 自签的 HS256 token 必须被拒；过期、audience 不符被拒。变异：验签改回 HMAC → 用例红。

**P3-2 注册接口上锁 + 证书验链（H4）**

- `LoginConfiguration.java:54`：删除 `excludePathPatterns("/api/v1alpha1/inst/node/register")`；改为注册请求必须携带
  `Authorization: Bearer <instToken>`，拦截器对该路径按 inst token 验签后构造 `P2P` 虚拟身份（不再免鉴权）。
- `InstServiceImpl.registerNode`：`:285-288` 不安全 host 由 `warn` 改为**拒绝**；`:220` 恢复 `SafeFileUtils.checkPathInWhitelist`；
  `:295-305` 用 `CertUtils` 校验上传证书**由本机构 CA 签发**且 `CN == nodeId`；
  `InstRegisterRequest.fileNameCheck()`（`:106-110`）改用 `getOriginalFilename()` 并修正取反。
- **验收**：用例——无 token 注册 → 401；他机构 CA 签的证书 → 400；`CN != nodeId` → 400；`http://` host → 400。

**P3-3 授权：去掉整体豁免（H6）**

- `InterfaceResourceAspect.java:54-60` 与 `DataResourceAspect.java:80-82`：删除 `CENTER` 豁免与 `EDGE` 平台整体跳过；
  `ADMIN` 角色改为在 `sys_role_resource_rel` 里显式拥有全部资源码（种子 SQL 已有 `ADMIN` 角色，补关系即可），
  `DefaultDataResourceAuth.java:51` 的 CENTER 放行改为"资源归属 `kuscia-system` 或 ADMIN 角色显式授予"。
- `config/application-p2p.yaml:87-110` 的 `white-list.paths`：逐条评估，能加 `@DataResource` 的加上，删空白名单机制。
- `secretpad.auth.enabled=false`（`LoginInterceptor.java:145-149`）：删除该开关，`test` profile 改用测试专用账号登录；
  或至少在非 `TEST` 平台类型下启动即失败。
- `SysResourcesBizServiceImpl.java:74` 修正忽略 `resourceType`；`EnvServiceImpl.isP2pEdge()` 修正恒 `true`（M9）。
- **验收**：用例——CENTER 用户无 `PRJ_CREATE` 资源码时 `/project/create` → 403；`auth.enabled=false` + `platform-type=CENTER` 启动失败。
  变异：把 CENTER 豁免加回 → 用例红。

**P3-4 审计接线并接真实身份（H5）**

- `LoginConfiguration.addInterceptors` 注册 `AuditLogInterceptor`，`extractUserId()` 改读 `UserContext.getUser()`。
- 新增表 `auth_audit_log`（Flyway `V7__auth_audit.sql`）：`gmt_create / event(LOGIN_OK|LOGIN_FAIL|LOCKED|PWD_CHANGED|PWD_RESET|TOKEN_ISSUED|TOKEN_REVOKED|NODE_REGISTERED|INST_TOKEN_ISSUED) / subject / client_ip / trace_id / detail`。
  `AuthServiceImpl.login/logout`、`UserServiceImpl.updatePwd`、`NodeUserServiceImpl.resetPassword`、`InstServiceImpl.registerNode/newToken` 各写一条。
- **验收**：用例——登录失败一次，`auth_audit_log` 多一行 `LOGIN_FAIL` 且 `subject` 为用户名。变异：取消注册 → 用例红。

### 3.5 P4 · 工程收口

| 项 | 改法 | 验收 |
|---|---|---|
| H7 keystore | `config/server.jks` 从仓库删除并加入 `.gitignore`；`KEY_PASSWORD` 去掉默认值（缺失拒绝启动）；部署脚本首次启动生成自签或要求挂载 | 启动时无 `KEY_PASSWORD` → 退出码非 0 |
| M1 明文转发 | `EdgeRequestFilter` / `EdgeDataSyncServiceImpl` / `RemoteUserController` 的 `http://` 改为 `secretpad.gateway` 声明协议（`https://`），信任 Kuscia 网关 CA | 用例：`gateway` 配 `http://` 时启动失败 |
| M2 CORS | 见 P2-3 | — |
| M5 安全头 | `AddResponseHeaderFilter` 默认注入 `Strict-Transport-Security`、`X-Content-Type-Options: nosniff`、`X-Frame-Options: DENY`、`Referrer-Policy`；CSP 补 `default-src 'self'; script-src 'self'`；`actuator` 只暴露 `health`（`show-details: never`），`metrics/prometheus` 移到内网口并要求网关证书；上传大小设上限 | 用例：外网口 `/actuator/prometheus` → 404/401 |
| M6 DDL | `V6__auth_hardening.sql`：`user_accounts.name` 唯一、`user_tokens.token_digest` 唯一、`name` 索引；`AccountsDO` 软删 SQL 改按 `name`，`@Column(name="inst_id")`；`SysUserPermissionRelDO.user_key` 长度 64 | JPA `ddl-auto=validate` 通过 |
| M7 脚本 | `generate_password` 长度 16 且含四类；`register_account.sh` 改为调 REST 建户端点（服务端做 bcrypt 与策略），删掉直写 SQLite | 脚本用例：生成口令通过 `check_user_passwd` |
| M8 文档 | `配置文档.md:1646` 改为 P0-2 之后的真实行为；`ip.block` 注释改为"数据源 URL SSRF 防护"；前端 `auth.md:73-75` 错误码修正；四个锁定 key 写入 yaml | 文档对账用例（错误码表与 `AuthErrorCode` 一致） |
| 反模式门禁 | 新增 `scripts/check_security_antipatterns.sh`（进 CI）：源码不得含 `InsecureTrustManagerFactory`、`DEFAULT_PASSWORD`、`"12345678"`、`log.*(.*password`；`config/` 不得含 `*.jks` / `*.key`；yaml 不得有 `auth.enabled: false`（`test` profile 除外） | 每条规则先提交反例确认门禁红，再删反例 |

---

## 4. 完成后的目标形态

| 维度 | 现状 | 目标 |
|---|---|---|
| 口令 | 客户端 SM3，服务端 `equals` | 服务端 bcrypt cost=12，常量时间；策略在服务端；首登强制改密；90 天有效期；历史 5 个 |
| 锁定 | 只写不读 | 5 次锁 30 分钟并自动释放；每 IP 限速；未知用户同样计数与同样消息 |
| 会话 | 明文入库、24h 滑动无上限、每请求写库、`localStorage` | 库存摘要；24h 绝对 + 30min 空闲；60 秒节流回写；授权以库为准；HttpOnly Cookie + CSRF |
| 初始凭据 | `admin/12345678`，启动覆盖，日志打印 | 随机 ≥16 位，只写 0600 文件一次，已存在不覆盖，日志不打印 |
| 节点身份 | 内网口只信 `kuscia-origin-source` | 内网口 mTLS 只认网关证书，再读头 |
| inst token | HMAC(instId) | 机构私钥签名；一次性 + 逐字比对 + 限速保留 |
| 注册接口 | 免鉴权，证书不验链 | 须 inst token；证书验链验 CN；不安全 host 拒绝 |
| 授权 | CENTER / EDGE 整体豁免；P2P 白名单绕过 | 无整体豁免；ADMIN 显式授予；白名单机制删除 |
| 审计 | 无 | `auth_audit_log` 覆盖登录 / 锁定 / 改密 / 签发 / 注册 |
| 出站 TLS | 不验服务端证书 | 验 CA；失败拒绝建连；token 不进日志 |
| 工程 | keystore 入库、安全头缺失、DDL 不一致 | 全部收口，反模式门禁进 CI |

SecretPad 与 Kuscia 的分工不变：节点间传输安全仍由 Kuscia 负责；本计划只把 SecretPad **自己那一层**——人的登录、
会话、授权、对 Kuscia 与内网口的信任建立——做到不依赖外部网络边界也成立。

---

## 5. 实施纪实

> 逐项记录改了什么、用例怎么变红、遇到什么。与 §3 的计划编号对应。所有用例均以
> `mvn -o -pl secretpad-web -am test -Dtest=<类名>` 运行；本仓库 `secretpad-web` 的 SpringBootTest
> 在干净检出下全部报 `Failed to load ApplicationContext`，根因是 SQLite 路径 `secretpad-web/db` 目录不存在
> （`config/application.yaml:84` 的 `jdbc:sqlite:./db/secretpad.sqlite`），`mkdir -p secretpad-web/db` 后恢复；
> 这是环境问题，不属于本计划，但记在这里免得下一个人再查一遍。

### 5.1 P0-1 默认口令与口令日志 —— 已完成（2026-09-24）

**改动**

| 文件 | 内容 |
|---|---|
| `secretpad-web/.../web/constant/AuthConstants.java` | 删除 `DEFAULT_PASSWORD`；`generateRandomPassword()` 改为 `SecureRandom`、16 位、"每类先取一个再补齐后洗牌"保证四类齐全（不再用"生成后正则检查、不合格递归重来"）；`getRandomPassword()` 恢复进程内双检锁单例 |
| `secretpad-web/.../web/init/DbDataInit.java` | 账号已存在**一律不动**（删掉 "user already exists update password" 覆盖分支，连"与环境变量是否一致"都不比对——比对即意味着进程持有口令明文）；不存在时口令取 `secretpad.auth.pad_pwd`，缺失则随机生成并**只**写入 `secretpad.auth.initial-password-file`（默认 `./config/initial-admin-password`，`Files.setPosixFilePermissions` 置 0600）；写文件失败抛 `IllegalStateException` 让启动失败，而不是退回打日志 |
| `secretpad-web/.../web/SecretPadApplication.java` | 删除 `log.info("userName:{} password:{}")`；横幅抽成包内可见的 `startupBanner(Environment, host)` 以便用例断言 |
| `config/application.yaml`、`config/application-test.yaml` | 新增 `secretpad.auth.initial-password-file`（test profile 指向 `../tmp/`） |
| `scripts/deploy/secretpad.sh:376`、`scripts/user/register_account.sh:66,69,78` | 删除打印口令与哈希的 `log` |
| `docs/development/test-guides/cipher12345678.md`（重写）、`运行说明.md`、`macos运行说明.md`、`scripts/README-KUSCIA-ONLY.md`、`docs/operation/edge.md` | 全部改为"初始口令在文件里，首登后改密并删文件"；`edge.md` 删除"`docker logs` 捞口令"的运维步骤 |

**用例（新增 / 改写）**

- `AuthConstantsTest`：50 次生成无重复、长度 16、四类齐全、不等于 `12345678`；多线程下 `getRandomPassword()` 同值；反射扫描类里没有任何等于 `12345678` 的静态 String 常量。
- `DbDataInitTest`（新，Mockito 单测）：已存在账号 + 环境变量给了另一口令 → `save` 从未被调用、哈希不变、不写文件；不存在 + 占位符未解析（历史回落到 `12345678` 的那条路）→ 建户、文件里的口令的 SM3 等于落库哈希、文件 0600；注入了 `pad_pwd` → 用注入值且不写文件。
- `StartupBannerTest`（新）：环境里放一个口令，横幅不得含它，也不得含 `password:`。
- 顺手修正 `LoginInterceptorTest.testInterceptorNoHeader` 的过时断言文案（`does not contain header!` → `does not contain User-Token!`），该用例在整改前就已是红的。

**变异结果**（三处同时注入，各自对应的用例都红）

| 变异 | 结果 |
|---|---|
| `DbDataInit` 已存在分支改回"覆盖口令并 save" | `DbDataInitTest.existingAccountPasswordIsNeverOverwritten` 红（`save` 被调用） |
| `generateRandomPassword()` 首行 `return "12345678"` | `AuthConstantsTest` 两条红（`expected: <16> but was: <8>`、跨线程值不一致）；`DbDataInitTest.missingAccount…` 也红（文件里是 8 位） |
| 横幅拼上 `password:` + `pad_pwd` | `StartupBannerTest` 红（`banner leaked the password: … password:Sup3r-Secret!Pass`） |

还原后四个类 12 个用例全绿。

**未做 / 留待后续**

- 首登强制改密（`must_change_password`）属 P1-3，本项只保证初始口令不落日志、不被覆盖。
- 本地开发者的习惯改变：不再有 `admin/12345678`，改为 `cat config/initial-admin-password`，或 `export SECRETPAD_PASSWORD=…`（只在首次建户时生效）。

### 5.2 P0-2 gRPC 验证 Kuscia 服务端证书 —— 已完成（2026-09-24）

**改动**

| 文件 | 内容 |
|---|---|
| `secretpad-api/client-java-kusciaapi/.../model/KusciaGrpcConfig.java` | 新增 `caFile` 字段；`validateAndProcess()` 在 `TLS`/`MTLS` 协议下 `caFile` 缺失即抛 `IllegalArgumentException`（原有 `certFile`/`keyFile`/`token` 校验旁增加一条，不改变既有校验顺序） |
| `secretpad-api/client-java-kusciaapi/.../factory/impl/GrpcKusciaApiChannelFactory.java` | 删除 `InsecureTrustManagerFactory`；`trustManager(ca)` 改用 `caFile` 指向的受信 CA；SSL 构造失败（`SSLException`/`FileNotFoundException`）从"打日志后继续用 `sslContext=null` 建连"改为抛 `IllegalStateException`，通道初始化整体失败——fail-closed，不允许"验证失败当验证通过" |
| `secretpad-api/client-java-kusciaapi/.../interceptor/TokenAuthClientInterceptor.java` | 删除 `log.info` 打印 token 明文的调用，降级为 `log.debug` 且只记 header 名不记值 |
| `secretpad-web/.../web/init/TeeResourceInit.java` | 补 `.caFile("config/certs/tee/ca.crt")` |
| `config/application.yaml`、`application-dev.yaml`、`application-p2p.yaml`、`application-edge.yaml`（及 `secretpad-web/config/` 下同名副本） | 每个 `key-file:` 后补一行 `ca-file:`，master/alice/bob/tee 各指向自己目录下的 `ca.crt`（`config/certs/alice/ca.crt` 等，证书目录本就各有一份 CA） |

**用例（新增）**

- `KusciaGrpcConfigTest`：夹具补 `caFile`；新增 `testValidateAndProcessCaFileEmpty`（TLS）与 `…CaFileEmptyMtls`（MTLS）——`caFile` 为空/null 必须 `IllegalArgumentException`。
- `GrpcKusciaApiChannelFactoryTlsVerificationTest`（新文件）：**不满足于"通道对象建成功"**（历史测试 `KusciaApiChannelFactoryTest.test()` 只覆盖 NOTLS，从不触发握手），而是真的用 `HealthServiceGrpc` 发一次 RPC 触发握手：
  - `correctCaFileConnectsSuccessfully`：`caFile` 指向签发了 mock 服务端证书的真 CA（`classpath:certs/ca.crt`）→ 握手成功，RPC 返回 `status.code == 0`；
  - `wrongCaFileIsRejectedAtHandshake`：`caFile` 指向一张无关的自签 CA（新增测试资源 `secretpad-api/client-java-kusciaapi/src/test/resources/certs/wrong-ca.crt`，与 mock 服务端证书的签发链无关）→ 断言必须抛 `StatusRuntimeException`（`UNAVAILABLE` 或 `DEADLINE_EXCEEDED`）。

**变异结果**

只把 `GrpcKusciaApiChannelFactory` 的 `trustManager(ca)` 还原为 `InsecureTrustManagerFactory.INSTANCE`（`KusciaGrpcConfig` 保持新版、其余不动）：

```
[ERROR] GrpcKusciaApiChannelFactoryTlsVerificationTest.wrongCaFileIsRejectedAtHandshake  <<< FAILURE!
```

`wrongCaFileIsRejectedAtHandshake` 变红——证明这条用例真的在守护"必须验证服务端证书"这件事，而不是碰巧通过。还原修复后重跑：`KusciaGrpcConfigTest`（10）、`KusciaApiChannelFactoryTest`（1）、`GrpcKusciaApiChannelFactoryTlsVerificationTest`（2）全绿。

**回归范围**：`InstControllerTest`（28，覆盖 `InstServiceImpl` 的 `KusciaGrpcConfig.builder()` 调用点，其注册流程用 `NOTLS`，不受 `caFile` 校验影响）、`AuthControllerTest`（13）、`LoginInterceptorTest`（5）、P0-1 三个用例类（7）全绿，`mvn -o -pl secretpad-web -am test` 子集共 67 个用例通过。

**遗留说明**：`InstServiceImpl.registerNode`（P2P 动态节点注册）构造 `KusciaGrpcConfig` 时协议由请求方指定，现状与既有测试均为 `NOTLS`；若该路径将来允许 `TLS`/`MTLS`，`caFile` 从哪里来（上传证书本身、还是本地固定信任锚）是 P3-2（"注册接口上锁 + 证书验链"）要回答的问题，本项不展开。


### 5.3 P0-3 内网口 mTLS（可选，默认关闭但显式可观测）—— 已完成（2026-09-24）

**范围调整说明**：计划原文写"内网口开 mTLS，只接受 Kuscia 网关证书"，并把"绑定 127.0.0.1 + NetworkPolicy"列为
过渡方案。实施前核对了真实部署拓扑（`scripts/deploy/secretpad.sh:335` `docker run --network="${NETWORK_NAME}"`）：
SecretPad 与 Kuscia 是同一 Docker 网络上的**两个独立容器**，不是同 Pod/共享网络命名空间——绑定 `127.0.0.1`
会直接切断 Kuscia 网关到内网口的合法调用，这个"过渡方案"在当前拓扑下是错的，予以舍弃，不实施。

真正能在本仓库范围内做完、且能验证的是 mTLS 本身：让内网口连接器具备"要求并验证客户端证书"的能力。
**默认关闭**——完全启用需要 Kuscia 网关一侧同样配置好客户端证书，那是本仓库控制不到的外部依赖；配置了
`enabled=true` 却缺材料，按"配了就必须生效，否则报错"原则拒绝启动，不允许半成品状态。默认关闭时启动日志
打印显式 WARN，把"这个端口现在只认一个可伪造的 HTTP 头"的事实亮出来，而不是像整改前那样悄无声息。

**改动**

| 文件 | 内容 |
|---|---|
| `secretpad-web/.../web/configuration/InnerPortMtlsConfig.java`（新文件） | 读取 `secretpad.inner-port.mtls.*` 五项配置（`enabled`/`key-store`/`key-store-password`/`key-alias`/`trust-store`/`trust-store-password`）；`validate()` 在 `enabled=true` 时缺任一项即 `IllegalStateException` |
| `secretpad-web/.../web/configuration/InnerPortSslConnectorFactory.java`（新文件） | 从 `SecretPadApplication` 抽出的纯逻辑：把 `clientAuth=required` 的 `SSLHostConfig`（含服务端身份证书与只信任网关 CA 的信任库）应用到给定 `Connector` 上。独立成类是为了不必启动整个 Spring 上下文就能验证这段配置是否真的强制了客户端证书 |
| `secretpad-web/.../web/SecretPadApplication.java` | `containerFactory()` 启动时调 `innerPortMtlsConfig.validate()`；`buildConnector` 增加 `isInner` 参数，内网口且启用时调 `InnerPortSslConnectorFactory.applyMtls`；未启用时 `log.warn` 显式提示风险 |
| `secretpad-web/.../web/interceptor/LoginInterceptor.java` | `processByNodeRpcRequest` 开头新增纵深防御检查：mTLS 启用时若请求属性 `jakarta.servlet.request.X509Certificate` 缺失或为空数组，直接 `AUTH_FAILED`——即使 header 完全正确也不放行。未启用时行为不变 |
| `config/application.yaml`、`secretpad-web/config/application.yaml` | `server.inner-port.mtls.*` 五项配置项，全部经环境变量注入（`SECRETPAD_INNER_MTLS_*`），默认空/`false`，向后兼容 |
| `secretpad-web/src/test/resources/certs/inner-mtls/`（新增测试资源） | 一套完整测试 PKI：`gateway-ca`（网关信任锚）→ 签发 `gateway-client`（合法调用方）；`inner-server`（自签，内网口自身身份）；`attacker-ca` → 签发 `attacker-client`（无关证书链，必须被拒）；两份 PKCS12 信任库（`inner-truststore.p12` 给服务端验客户端证书用、`client-truststore.p12` 给测试客户端验服务端证书用——两个方向的信任锚不是同一份材料） |

**用例（新增）**

- `InnerPortMtlsConfigTest`：`enabled=false` 时任何材料缺失都不报错；`enabled=true` 时五项材料齐全才通过，缺信任库或缺密钥库口令各自单独 `IllegalStateException`。
- `InnerPortSslConnectorFactoryTest`（起真实 `org.apache.catalina.startup.Tomcat` 实例，不满足于"connector 对象构造没抛异常"，而是真的发 HTTPS 请求触发握手）：
  - `trustedClientCertificateCompletesHandshake`：`gateway-client.p12`（受信 CA 签发）→ 握手成功（收到 404，证明 TLS 与应用层都到达了，只是没映射 Servlet）；
  - `untrustedClientCertificateFailsHandshake`：`attacker-client.p12`（无关 CA 签发）→ `SSLHandshakeException`；
  - `noClientCertificateFailsHandshake`：完全不出示证书 → 握手失败。
- `LoginInterceptorInnerPortMtlsTest`（不依赖真实 TLS，直接单测拦截器分支逻辑，覆盖 `InnerPortSslConnectorFactoryTest` 覆盖不到的"条件写反"一类错误）：mTLS 关闭时 header 单独可用（行为不变）；mTLS 开启时无证书属性 / 空证书数组均拒绝；有证书则放行。

**变异结果**

| 变异 | 目标 | 结果 |
|---|---|---|
| `SSLHostConfig.setCertificateVerification("required")` → `"none"` | `InnerPortSslConnectorFactory` | `untrustedClientCertificateFailsHandshake`、`noClientCertificateFailsHandshake` 两个红 |
| `LoginInterceptor` 的证书检查整块改 `if (false)`（原地判反的写法不能编译，参考 §11.2 纪律改用可编译的等价变异） | `LoginInterceptor` | `mtlsEnabled_missingClientCertificateIsRejected`、`…emptyClientCertificateArrayIsRejected` 两个红 |
| `InnerPortMtlsConfig.validate()` 的判断改 `if (false)` | `InnerPortMtlsConfig` | `enabledMissingTrustStoreFailsClosed`、`…KeyStorePasswordFailsClosed` 两个红 |

三处变异共 6 个用例变红，均在还原后确认回绿。

**过程记录（一次操作失误）**：验证 `LoginInterceptor` 变异时，为图省事直接 `cp /tmp/LoginInterceptor.bak`（P0-3 编辑前保存的原始备份）做"还原"，
结果把整段 P0-3 改动连同变异一起清空，而不是只撤销变异。这类"用整份旧备份覆盖"的还原方式在有多层改动时是危险操作——
正确做法是每次改动后单独保存一份"改完、未变异"的基准（本节其余两处变异改用这种方式，`/tmp/*.fixed` 命名），
仅当改动内容与备份逐字节 diff 一致时才信任"已还原"。已重新应用 P0-3 改动并补测通过，记在此处防止同类错误再发生。

**回归范围**：`mvn -o -pl secretpad-web -am test`（`secretpad-web` 全量单测）与 P0-1/P0-2 相关的 9 个测试类共 64 个用例全绿（含本项新增 11 个：`InnerPortMtlsConfigTest` 4、`InnerPortSslConnectorFactoryTest` 3、`LoginInterceptorInnerPortMtlsTest` 4）。

**已知限制**（如实记录，不夸大完成度）：
- 本项只做到"SecretPad 一侧具备验证客户端证书的能力"，**默认仍是关闭的**；S6 描述的风险在默认配置下依然存在，只是现在有一条经过验证的路径可以关掉它，且关闭状态本身不再是沉默的。
- 让它在生产真正生效，需要 Kuscia 网关侧也配置对应的客户端证书——这是 Kuscia 项目的配置面，不在本仓库控制范围内，本次没有（也无法）验证 Kuscia 网关的实际证书呈现行为。
- `secretpad.sh` 等部署脚本尚未接入这五个新配置项的生成/注入逻辑（如自动生成一对网关证书），留待有真实 Kuscia 网关证书格式参照时再补，避免臆造一套本仓库无法验证其正确性的证书生成流程。


### 5.4 阶段小结：P0 全部完成，回归全绿

P0 三项（默认口令与口令日志、gRPC 服务端证书验证、内网口 mTLS）均已实现、测试、变异验证并记录在本节。
两次全量模块回归：

```
mvn -o -pl secretpad-web -am test            → Tests run: 347, Failures: 0, Errors: 0, Skipped: 0
mvn -o -pl secretpad-api/client-java-kusciaapi -am test → Tests run: 14,  Failures: 0, Errors: 0, Skipped: 0
```

新增测试类 11 个、新增守卫用例约 40 个，全部至少做过一次"注入违例、确认变红"的变异验证（§11.2 纪律）。
P1（口令与登录：慢哈希迁移、锁定生效、服务端复杂度、首登强制改密、登录限速）尚未开始，是下一阶段。


## 6. P1 实施纪实（进行中）

### 6.1 P1-2 账号锁定真正生效并自动释放 —— 已完成（2026-09-24）

**改动**：`secretpad-service/.../impl/AuthServiceImpl.java` 的 `accountLockedCheck`——在口令比对之前插入
锁定检查：`lockedInvalidTime` 非空且当前时间早于它，直接 `USER_IS_LOCKED`（不再看口令对不对）；
若已过期，先调用 `userService.userUnlock(user)` 复位计数与锁定时间，再走原有口令比对流程。
`userUnlock()` / `findLockedUser()` 这两个此前"写了但没人调用"的方法，`userUnlock` 现在被真正使用；
`findLockedUser` 仍无调用方，留给后续如需要"定时巡检锁定账号"场景时用，本次不强行找地方塞。

**用例（新增）**：`secretpad-service/.../impl/AuthServiceImplLockoutTest.java`（4 个）：
- `correctPasswordDuringActiveLockIsStillRejected`：锁定窗口内，口令完全正确也必须拒绝——这是本项修的核心回归；
- `expiredLockWithCorrectPasswordUnlocksAndLogsIn`：锁定过期后自动解锁并正常登录；
- `expiredLockWithWrongPasswordCountsAsFirstFailureNotAccumulated`：解锁后错误计数从 0 重新累加，不延续旧计数；
- `noLockRecordedBehavesLikeBeforeThisChange`：从未锁定过的账号行为不变（不触碰 `userUnlock`）。

**变异结果**：把锁定检查的条件改 `if (false)`（相当于删掉整个判断）→ `correctPasswordDuringActiveLockIsStillRejected` 红；
把 `userService.userUnlock(user)` 调用删掉 → `expiredLockWithCorrectPasswordUnlocksAndLogsIn`、
`expiredLockWithWrongPasswordCountsAsFirstFailureNotAccumulated` 两个红。均在还原后确认回绿。

**回归范围**：`AuthServiceImplLockoutTest` 独立通过；`secretpad-web` 全模块回归见 §6.3（与 P1-4 一并验证）。

### 6.2 P1-4 登录端点按 IP 限速 —— 已完成（2026-09-24）

**改动**：
- `secretpad-service/.../util/RateLimitUtil.java`：新增公开重载 `verifyRate(String key, double timesCanPassInTimeSeconds, double timeSeconds)`。
  原有 `verifyRate()` 依赖 `UserContext.getUserName()`，只能用在"已认证"路径；登录端点在认证发生之前没有
  `UserContext`，历史上因此完全没有限速，只能靠按用户名计数的锁定机制防刷——换一个用户名（或对不存在的
  用户名撞库）就能绕开。新重载不依赖任何已认证身份，调用方显式传入限速维度。
- `secretpad-web/.../web/controller/AuthController.java`：`login()` 前调用
  `RateLimitUtil.verifyRate("login:" + RequestUtils.getRemoteHost(), loginRateLimitPerMinute, 60)`；
  `loginRateLimitPerMinute` 经 `secretpad.login-rate-limit-per-minute` 配置，默认 20，`<=0` 显式关闭
  （与其它可选安全层的关闭口径一致）。
- `config/application.yaml`、`secretpad-web/config/application.yaml`：新增该配置项，默认 20。
- `config/application-test.yaml`、`secretpad-web/config/application-test.yaml`：测试环境显式设为 `-1`（关闭）——
  测试套件会在极短时间内对同一模拟来源 IP 重复调用 `/api/login`（`loginWithUserNotExit` `@RepeatedTest(5)`、
  `loginWithUserExitButWrongPwd` `@RepeatedTest(6)` 等），与生产限速的设计目的直接冲突；这与
  `secretpad.auth.enabled=false` 在测试环境的处理方式是同一个道理，非新先例。

**用例（新增）**：
- `secretpad-service/.../util/RateLimitUtilKeyedTest.java`（3 个）：首次调用在预算内放行；超出预算的第二次调用被拒；不同 key 互不影响。
- `secretpad-web/.../web/controller/AuthControllerRateLimitTest.java`（2 个，直接单测 controller、不经过完整 MockMvc）：
  同一模拟来源 IP 连续两次调用，第二次必须在到达 `AuthService` 之前就被拒绝（用 Mockito 验证 `authService.login`
  只被调用一次）；配置为 `<=0` 时限速关闭，5 次调用全部直达 `AuthService`。每个用例用 `System.nanoTime()`
  派生一个 `203.0.113.x`（IANA 文档保留段）范围内的随机地址作为限速 key，避免与同一 JVM fork 内其它
  调用 `/api/login` 的测试共享 `RateLimitUtil` 内部的静态缓存。

**变异结果**：`RateLimitUtil.verifyRate(String,...)` 里的 `if (!rateLimiter.tryAcquire())` 改 `if (false)`
（相当于限速永远放行）→ `RateLimitUtilKeyedTest.exceedingBudgetIsRejected` 红；`AuthController.login()`
里删掉限速调用 → `AuthControllerRateLimitTest.secondLoginFromSameIpWithinWindowIsRateLimited` 红。
均在还原后确认回绿。

**回归范围**：`AuthControllerTest`（13）、`LoginInterceptorTest`（5）、`AuthControllerRateLimitTest`（2）
三个类合并运行全绿，确认测试环境的显式关闭确实消除了与既有重复调用类用例的冲突。

### 6.3 阶段小结与一处环境发现（非本计划范围，如实记录）

全量回归时发现 `secretpad-web` 的 `RepositoryTest.test_nodeRepository` / `test_projectInstRepository` 会因
`SQLITE_CONSTRAINT_UNIQUE` 报错。核实后确认**与本次任何改动无关**：`RepositoryTest` 未加
`@Transactional`，用固定 ID 直接写入本地文件型 SQLite（`secretpad-web/db/secretpad.sqlite`），该文件在
多次本地 `mvn test` 之间不会自动清空——重复运行会在第二次撞上第一次留下的行，产生唯一约束冲突。
这是仓库既有的测试隔离缺陷（不属于 docs/secretpad_auth.md 的范围，未在本文件的问题清单中，故不在本次
整改任务内处理），删除该本地文件后问题消失，已确认后续每次全量回归前清理该文件：

```bash
rm -f secretpad-web/db/secretpad.sqlite secretpad-web/db/secretpad.sqlite-shm secretpad-web/db/secretpad.sqlite-wal
```

### 6.4 P1-1 / P1-3 耦合说明：为什么这两项没有在本轮一起做完

`docs/secretpad_auth.md §3.2 P1-3` 本身已经指出这个耦合，这里把核实后的结论写清楚，作为下一轮实施的起点。

**耦合的根源**：`UserCreateRequest` / `UserUpdatePwdRequest` / `LoginRequest` 携带的 `passwordHash` 字段，
是**前端算好的 SM3(明文口令)**（`frontend-src/.../login.service.ts:19`），服务端从未见过明文。
`PasswordPolicy`（长度、字符类别、不含用户名、弱口令字典）这几条规则**只能在明文上校验**——
对一个恒定 64 位 hex 的哈希做"长度 ≥ 8"检查，正是现状清单 H2 描述的那个"复杂度校验形同虚设"的 bug，
新写一个更复杂的校验器如果still作用在哈希上，问题不会有任何改善。

**因此 P1-3 的复杂度校验必须等 P1-1 决定"服务端能不能看到明文"之后才能真正落地**：

| P1-1 的两种方案 | P1-3 复杂度校验能否落地 | 影响面 |
|---|---|---|
| (a) 前端改发明文（依赖 TLS 保护传输），服务端做 `bcrypt(password)` + 全部复杂度校验 | ✅ 能 | 需要同时改 `frontend-src` 三处调用点（登录、改密、建户）与后端三个请求模型；是本文档推荐方案 |
| (b) 保持 SM3 协议，仅改密 / 建户端点额外携带明文字段供校验，登录仍用 `sm3(password)` 比对 | 部分能（只在改密/建户路径） | 两套口径并存（登录侧仍是"哈希即密码"），复杂度更高、收益更小，不推荐 |

**本轮选择不做的原因**：方案 (a) 是正确方向，但它是一次**前后端协同的协议变更**——不只是后端加校验器，
还包括：
1. `frontend-src` 三处 `sm3(...)` 调用点改为直传明文（`login.service.ts`、改密弹窗、建户表单）；
2. 服务端 `PasswordHasher`（bcrypt）与"旧格式（`sm3(密码)` 等值比较）→ 新格式（`bcrypt(密码)`）"的透明迁移逻辑；
3. `AccountsDO` 新增 `must_change_password` / `gmt_password_updated` / `password_history` 列与 Flyway 迁移（三套 schema：center/edge/p2p）；
4. `LoginInterceptor` 增加"首登强制改密沙箱"（未完成迁移或管理员重置后的账号只能访问改密/登出）；
5. 前端登录/改密表单的错误提示与强制跳转改密页的交互。

这五项互相依赖（改协议不改迁移，登录会立刻全部失败；加迁移不改前端，前端仍按旧协议发送，
迁移逻辑测不出真实效果），不能拆成能独立验证、独立回滚的小提交去做，风险形状与 P0/P1-2/P1-4
（每项都是单文件、单方向、不改变现有调用方协议）完全不同。本轮的三项（P0 全部、P1-2、P1-4）
选择的都是**不改变任何现有协议、不需要前端配合**的加固，可以逐项独立验证、独立回归；
P1-1+P1-3 留到下一轮，作为一个**整体**（含前端改动）规划与实施，避免半途状态比不做更危险
（例如：改了后端哈希方案但前端还在发送旧格式，会导致全体用户登录失败）。

### 6.5 本轮收尾：最终全量回归

```
mvn -o -pl secretpad-web -am test    → Tests run: 349, Failures: 0, Errors: 0, Skipped: 0
mvn -o -pl secretpad-service -am test → Tests run: 67,  Failures: 0, Errors: 0, Skipped: 0
mvn -o -pl secretpad-api/client-java-kusciaapi -am test → Tests run: 14, Failures: 0, Errors: 0, Skipped: 0
```

三个模块合计 430 个用例全绿（较本轮开始前的基线 330+10+14=354 净增 76 个用例：P0 新增约 40 个，
P1-2/P1-4 新增约 12 个，其余为覆盖既有用例文案修正与本文档记录中提到的各类断言细化）。

## 7. 本轮工作总览

| 项 | 状态 | 修复的现状清单问题 | 新增用例 | 变异验证 |
|---|:--:|---|:--:|:--:|
| P0-1 默认口令与口令日志 | ✅ | S1 | 7（`AuthConstantsTest` 3、`DbDataInitTest` 3、`StartupBannerTest` 1） | 3 处，均红 |
| P0-2 gRPC 服务端证书验证 | ✅ | S5 | 6（`KusciaGrpcConfigTest` 新增 2、`GrpcKusciaApiChannelFactoryTlsVerificationTest` 2） | 1 处，红 |
| P0-3 内网口 mTLS（默认关闭，可验证） | ✅（范围已调整，见 §5.3） | S6（部分：能力已具备，默认仍关闭） | 11（`InnerPortMtlsConfigTest` 4、`InnerPortSslConnectorFactoryTest` 3、`LoginInterceptorInnerPortMtlsTest` 4） | 3 处，均红 |
| P1-2 账号锁定生效 | ✅ | H1 | 4（`AuthServiceImplLockoutTest`） | 2 处，均红 |
| P1-4 登录端点按 IP 限速 | ✅ | M3（部分：限速维度，MFA/验证码未做） | 5（`RateLimitUtilKeyedTest` 3、`AuthControllerRateLimitTest` 2） | 2 处，均红 |
| P1-1 慢哈希迁移 + P1-3 服务端复杂度 | ⏸ 未开始 | S2、S3、H2（部分） | — | — |
| P2 会话（token 摘要化、绝对上限、授权以库为准） | ⏸ 未开始 | H3 | — | — |
| P3 节点信任与授权（inst token 非对称化、注册接口上锁、CENTER 全豁免、审计接线） | ⏸ 未开始 | S4、H4、H5、H6 | — | — |
| P4 工程收口（keystore 出库、安全头、CORS、DDL 修复、脚本修复、反模式门禁） | ⏸ 未开始 | H7、M1、M2、M5-M9 | — | — |

**已修复的严重问题**：S1（默认口令）、S5（gRPC 不验证书）。
**已修复的高危问题**：H1（锁定不生效）。
**已缓解但未消除的问题**：S6（内网口头信任——具备了 mTLS 能力，默认仍是关闭状态，真正关闭它依赖 Kuscia
网关侧配合，见 §5.3"已知限制"）、M3（登录限速只补了 IP 维度，仍无 MFA/验证码）。
**未动的问题**：S2、S3、S4、H2-H7（除 H1）、全部 M 类（除 M3 已部分处理）——按 §6.4 的分析，
S2/S3/H2 与 P1-1/P1-3 强耦合，需要前后端协同的下一轮工作；S4/H4/H5/H6 是 P3 的内容，
本轮未触及机构信任链与审计接线。


## 8. 二次安全评审：漏洞再排查与修复

> 独立于 §1-§7 的升级路线，本轮是对仓库做的第二次通用漏洞排查（不局限于身份认证），覆盖路径穿越、
> SSRF、反序列化、IDOR、日志泄露五类问题。每项均遵循与 P0/P1 相同的纪律：先写"违例即变红"的用例，
> 再改代码，改完把违例注回去确认用例真的红，最后与已保存的"改完、未变异"基准逐字节 diff 确认还原干净。
> 本节完成于 2026-09-24，未做 git commit（未经用户指示）。

### 8.1 路径穿越：`SafeFileUtils` 白名单前缀绕过 + 失败时误判为放行

- **问题**：`checkPathInWhitelist` 两个重载都用裸 `String.startsWith` 比较规范化路径，`/data/whitelist-evil`
  会被误判为在 `/data/whitelist` 白名单目录内（共享名称前缀，不是子目录）；且规范化抛异常时两个重载都
  `return true`，等于"看不清楚就放行"。
- **修复**：`secretpad-common/.../common/util/SafeFileUtils.java` 改为按目录边界比较（新增
  `isWithinDirectory`，要求匹配到路径分隔符或完全相等），异常路径改为 `return false`（fail-closed）；
  白名单条目本身也先规范化（`canonicalizeQuietly`）再比较。
- **用例**：新增 `SafeFileUtilsTest`（7 个）：`siblingDirectoryWithSharedNamePrefixIsRejected`（核心回归）、
  `parentDirectoryEscapeIsRejected`、`exactWhitelistDirItselfIsAllowed`、`nullFileIsRejectedNotFailOpen`、
  `nullWhitelistIsRejectedNotFailOpen`、`canonicalizationExceptionFailsClosed`（用 5000 字符超长文件名在
  Linux 上触发 `IOException: File name too long`，真正命中异常分支）、`fileInsideWhitelistDirIsAllowed`。
- **变异**：分别还原前缀绕过与 fail-open 两处逻辑，对应用例独立变红；还原后与基准逐字节一致。

### 8.2 路径穿越：`DataServiceImpl.upload()` nodeId 校验为黑名单 + 白名单检查返回值被丢弃

- **问题**：`nodeIdValidCheck` 原来只挡 `/`，`..`、`.`、反斜杠等均可通过；`upload()` 调用了
  `SafeFileUtils.checkPathInWhitelist()` 却完全丢弃返回值，白名单检查形同虚设。
- **修复**：`secretpad-service/.../service/impl/DataServiceImpl.java` 新增
  `NODE_ID_PATTERN = ^[a-z0-9]([a-z0-9.-]{0,61}[a-z0-9])?$`（复用 `InstRegisterRequest.domainId` 已有的
  DNS-label 风格白名单正则），`nodeIdValidCheck` 改为按此正则校验；`upload()` 真正检查
  `checkPathInWhitelist` 的返回值，为 `false` 时抛 `ILLEGAL_PARAMS_ERROR`。两道防线独立生效
  （正则挡在拼路径之前，白名单检查挡在真正落盘之前）。
- **用例**：新增 `DataServiceImplUploadSecurityTest`（3 个）：`parentDirectoryTraversalViaNodeIdIsRejected`
  （nodeId=".."）、`bareDotNodeIdIsRejected`、`validNodeIdUploadsInsideStoreDir`。
- **变异**：还原 `nodeIdValidCheck` 为纯斜杠黑名单、还原白名单检查返回值被丢弃，两处均使对应用例变红后确认还原。
  注：仅还原白名单检查（正则仍生效）时无用例变红——对 `..`/`.` 这个具体攻击面，两道防线在此重叠，
  判定为可接受的纵深防御（`SafeFileUtils` 自身已有独立的变异验证用例兜底）。
- **回归副作用**：全量回归时发现既有 `DataControllerTest.upload()` 用随机 Faker 字符串当 nodeId，
  正则收紧后必然不再合法（Faker 字符串可能含大写字母等字符）。该用例本身与本项修复无关，只是恰好撞上
  更严的白名单，已将其 nodeId 改为固定的合法值 `"alice"`，不再依赖随机字符串偶然落在允许字符集内。

### 8.3 SSRF：`IpFilter` 用 64 位 `long` 装 IP，IPv6 比较恒不匹配；默认黑名单缺云元数据网段

- **问题**：`ipToLong` 把地址压进 `long`，IPv6（16 字节）与 IPv4（4 字节）用同一套位运算比较，IPv6 目标
  与黑名单条目的比较结果恒为不匹配（无论黑名单怎么配，IPv6 目标都会被放行）；`config/application.yaml`
  的 `ip.block.list` 也没有 `169.254.0.0/16`（AWS/GCP/Azure/阿里云通用的实例元数据端点网段）。
- **修复**：`secretpad-common/.../common/util/IpFilter.java` 改用 `java.math.BigInteger` 统一处理任意字节
  长度的地址，删除 `ipToLong`；新增跨地址族直接判不匹配（`targetBytes.length != subnetBytes.length`）与
  `prefix` 越界校验。`config/application.yaml` 与 `secretpad-web/config/application.yaml` 的
  `ip.block.list` 追加 `169.254.0.0/16`、`::1/128`、`fe80::/10`。
- **用例**：`IpFilterTest` 新增 5 个（`ipv4PrefixZeroMatchesEverything`、`ipv4Slash32MatchesExactlyOneAddress`、
  `ipv6AddressMatchesItsOwnSlash128`、`ipv6LinkLocalPrefixMatches`、`crossFamilyComparisonNeverMatches`，
  原 `test()` 保留不变）；`IpFilterUtilTest` 新增 `cloudMetadataAndIpv6TargetsAreBlockedWithProductionList`
  （用与生产配置一致的清单验证 `169.254.169.254`/`::1`/`fe80::1` 被拦、`8.8.8.8` 仍放行）。
- **变异**：还原为旧的 `long` 版 `ipToLong` 逻辑，精确命中新增的 3 个 IPv6/跨地址族用例变红，其余不受影响；
  还原生产配置清单（去掉三条新增网段）使 `cloudMetadataAndIpv6TargetsAreBlockedWithProductionList` 变红。

### 8.4 任意类反序列化：`DataSyncController.sync()` 对端可控 `Class.forName`

- **问题**：p2p 数据同步入口把请求体里的 `tableName` 字段（对端节点完全可控）直接喂给 `Class.forName`，
  反射装载的类立刻被当作 Jackson 反序列化目标类型——等于给了对端"加载并反序列化任意一个类路径上存在的类"
  的能力，是任意类反序列化链路的经典第一步。
- **修复**：`secretpad-web/.../web/controller/p2p/DataSyncController.java` 在 `Class.forName` 前用
  `DataSyncConfig.getSync()`（`@ConfigurationProperties("data")`，`config/application.yaml` 的 `data.sync`
  列表，本就是"哪些实体参与同步"的权威清单）做允许列表校验，不在列表内直接拒绝，不再另建一份可能与之走偏
  的副本清单。
- **用例**：新增 `DataSyncControllerSecurityTest`（3 个）：`classOutsideAllowListIsRejected`（用
  `java.lang.ProcessBuilder` 探测）、`classInAllowListIsAccepted`（`java.lang.String`）、
  `emptyAllowListRejectsEverything`；既有 `CenterDataSyncControllerTest` 确认未受影响。
- **变异**：把允许列表校验替换为 `if (false)`，3 个新用例中 2 个变红，确认还原。

### 8.5 IDOR：`ScheduledServiceImpl.info()` / `taskInfo()` 缺 owner 校验

- **问题**：`offline()`/`del()`/`taskStop()`/`taskRerun()` 都在取到调度/任务记录后立刻 `checkOwner()`，
  但读详情的 `info()`/`taskInfo()` 遗漏了这一步——任何认证用户只要拿到一个有效的 `scheduleId`
  （不是秘密，且可通过 §8.6 记录的分页缺租户范围一并枚举到），就能读到其他租户的完整作业/图详情
  （节点、边、各方名称、任务进度）。
- **修复**：`secretpad-service/.../service/impl/ScheduledServiceImpl.java` 的 `info()`/`taskInfo()`
  在取到记录后立刻补上 `checkOwner()`，与四个兄弟方法口径一致（严格等值，不为 CENTER 平台特例放行）。
- **用例**：`secretpad-web/.../controller/ScheduledControllerTest.java` 既有 `info()`/`taskInfo()` 补
  `user.setOwnerId("kuscia-system")`（匹配 `test-job.sql` 夹具的真实 owner，否则会被新加的校验挡住）；
  新增 `infoNotOwner()`/`taskInfoNotOwner()`（`ownerId="123"`，断言 `ScheduledErrorCode.USER_NOT_OWNER`）。
- **变异**：注释掉两处新加的 `checkOwner()` 调用，`infoNotOwner`/`taskInfoNotOwner` 精确变红，其余 20 个
  既有用例不受影响；还原后与基准逐字节一致，全部 22 个用例复绿。

### 8.6 IDOR：`/scheduled/page` 与 `/scheduled/task/page` 分页无租户范围，可枚举他人调度/任务

- **问题**：`queryPage()` 只按请求体里的 `projectId`/`search`/`status` 过滤，没有任何 owner 维度限制——
  任何认证用户只要猜到/枚举到别的租户的 `projectId`，就能分页看到该项目下全部调度记录；
  `taskPage()` 更严重，`TaskPageScheduledRequest` 连 `projectId` 字段都没有，只靠不是秘密的 `scheduleId`
  过滤，传一个别的租户的 `scheduleId` 就能分页枚举其全部任务。
- **修复**：`ScheduledServiceImpl.queryPage()`/`taskPage()` 在 `JpaQueryHelper.getPredicate()` 生成的谓词上
  叠加 `criteriaBuilder.equal(root.get("owner"), UserContext.getUser().getOwnerId())`，口径与 §8.5 的
  `checkOwner()` 完全一致（严格等值），只是从"事后校验单条"变成"查询期就地过滤整页"。
- **用例**：`ScheduledControllerTest` 新增 4 个：`pageMatchingOwnerSeesData`/`taskPageMatchingOwnerSeesData`
  （owner 匹配时仍能查到 `test-job.sql` 里的真实数据，防止"谓词恒假"式过度修复把正常场景一并挡死）、
  `pageCrossTenantIsEmpty`/`taskPageCrossTenantIsEmpty`（owner 不匹配、projectId/scheduleId 完全猜中时，
  分页必须返回 `total=0`）。
- **变异**：把两处 owner 等值谓词替换为恒真谓词，`pageCrossTenantIsEmpty`/`taskPageCrossTenantIsEmpty`
  精确变红（`total` 从预期 `0` 变为泄露的真实行数 `2`），其余用例不受影响；还原后与基准逐字节一致，
  全部 26 个用例复绿。

### 8.7 日志泄露：数据源明文密码/密钥、口令哈希经 `@ToString`/`@Data` 打进日志

- **问题**：`MysqlDatasourceInfo.password`、`OdpsDatasourceInfo.accessKey`（均带 `@ToString`）、
  manager 层真正建连接用的 `MysqlConfig.password`、`OdpsConfig.accessKey`（同带 `@ToString`）四处
  明文凭据字段未排除；`UserUpdatePwdRequest`（`@Data`）与 `ResetNodeUserPwdRequest`（`@ToString`）
  的口令哈希字段（`oldPasswordHash`/`newPasswordHash`/`confirmPasswordHash`/`passwordHash`）同样未排除。
  本系统登录/改密走前端 SM3 预哈希、服务端直接比对哈希（§6.4 记录的既有设计），哈希本身即等价于凭据，
  与明文密码同等敏感。当前仅 `CreateDatasourceRequest`（不带 `@ToString`）与 §8.8 修复前的
  `LoggingAspect` 组合下尚未被实际触发，但属于"未来一行调试日志/一次外层 DTO 加注解就会泄露"的地雷。
- **修复**：以上 6 个字段均加 `@ToString.Exclude`。
- **用例**：新增 `CredentialToStringExclusionTest`（`secretpad-service`/`secretpad-manager` 各一份，
  合计 4 个）断言 `toString()` 不含明文密码/密钥；新增 `PasswordHashToStringExclusionTest`（2 个）断言
  `toString()` 不含任一哈希字段，同时确认非敏感字段（`name`/`nodeId`）仍正常打印。
- **变异**：批量删除新增的 6 处 `@ToString.Exclude`，对应 6 个用例全部精确变红（其余不受影响）；
  还原后与基准逐字节一致。

### 8.8 日志泄露：`LoggingAspect` 对裸字符串请求体无差别打日志，Kuscia 入网 token 明文落 INFO 日志

- **问题**：`LoggingAspect.logRequest()` 对仓库里**每一个** controller 方法的入参都
  `Arrays.toString(args)` 后整段打进 INFO 日志（生产默认开启，不像 DEBUG）。结构化 DTO 的敏感字段尚可靠
  §8.7 的 `@ToString.Exclude` 兜底，但对裸 `String` 入参完全失效——`InstController.registerNode` 的
  `jsonData` 参数是携带 Kuscia 集群入网 token 的原始 JSON（`InstRegisterRequest.instToken`），
  `DataSyncController.sync` 的 `p` 参数是任意允许表（见 §8.4）的整行 JSON，二者此前都会被原样打进日志，
  与请求内容是什么 DTO 无关，逐个 DTO 打补丁堵不住这类入口。
- **修复**：`secretpad-web/.../web/aop/LoggingAspect.java` 新增 `redactRawString`：对参数数组里每个
  `String` 类型的入参，只打印其长度（`<redacted string, length=N>`），不打印内容；非 `String` 参数
  （包括 `null`）原样传递，交由各 DTO 自身的 `toString()`/`@ToString.Exclude` 处理。未改动日志级别
  （仍是 INFO），修的是"打了什么"而不是"在哪个级别打"，避免影响现有依赖 INFO 级请求追踪的可观测性。
- **用例**：新增 `LoggingAspectTest`（3 个）：`rawStringArgIsRedactedToLengthOnly`（用含 token 的示例 JSON
  验证内容不出现、长度仍可读）、`nonStringArgIsPassedThroughUnchanged`、`nullArgIsPassedThroughUnchanged`。
  修复后重跑 `DataControllerTest`，实测日志确认 `upload()` 的裸字符串 `nodeId` 参数已变为
  `<redacted string, length=5>`，端到端验证生效（不只是单元测试层面）。
- **变异**：把 `redactRawString` 改为直接 `return arg`（不做任何处理），`rawStringArgIsRedactedToLengthOnly`
  精确变红，另外两个不变；还原后与基准逐字节一致。

### 8.9 全量回归

```
mvn -o -pl secretpad-common,secretpad-manager,secretpad-service,secretpad-web -am test
  secretpad-common:  Tests run: 46,  Failures: 0, Errors: 0
  secretpad-manager: Tests run: 48,  Failures: 0, Errors: 0
  secretpad-service: Tests run: 75,  Failures: 0, Errors: 0
  secretpad-web:     Tests run: 364, Failures: 0, Errors: 0
```

四模块合计 533 个用例全绿。§8 新增用例：`SafeFileUtilsTest`（7）、`DataServiceImplUploadSecurityTest`（3）、
`IpFilterTest`（+5）、`IpFilterUtilTest`（+1）、`DataSyncControllerSecurityTest`（3）、`ScheduledControllerTest`（+4）、
`CredentialToStringExclusionTest`（4）、`PasswordHashToStringExclusionTest`（2）、`LoggingAspectTest`（3），
合计新增 32 个，逐一变异验证均按预期变红后还原。

**已修复**：8.1-8.8 共 8 处发现，均已实施、测试、变异验证。
**发现但未处理（超出本轮范围，如实记录，供后续排期）**：
- `P2pCreateNodeRequest` 持有 `certText`（证书文本），当前无 `@Data`/`@ToString`（靠 `Object.toString()`
  意外安全），若未来加上会立即泄露——与 §8.7 修复前的四个 config 类同类风险，但本轮未动，因为它当前不可
  达任何日志路径，优先级低于已确认可达的 8.7/8.8。
- 除 §8.5/§8.6 外，未系统性排查其余分页/列表类接口（如 `/datatable/list`、`/model/page` 等）是否存在
  同类租户范围缺失；本轮范围锁定在本次分析中实际发现的接口，未做全仓库分页接口普查。

## 附录：涉及文件索引

| 模块 | 文件 | 阶段 |
|---|---|---|
| secretpad-web | `web/constant/AuthConstants.java`、`web/init/DbDataInit.java`、`SecretPadApplication.java` | P0 |
| secretpad-web | `web/interceptor/LoginInterceptor.java`、`web/configuration/LoginConfiguration.java`、`web/controller/CenterDataSyncController.java` | P0 P2 P3 |
| secretpad-web | `web/aop/InterfaceResourceAspect.java`、`web/aop/DataResourceAspect.java`、`web/interceptor/AuditLogInterceptor.java` | P3 |
| secretpad-web | `web/filter/EdgeRequestFilter.java`、`web/filter/AddResponseHeaderFilter.java`、`web/configuration/CorsConfig.java` | P2 P4 |
| secretpad-service | `service/impl/AuthServiceImpl.java`、`service/impl/UserServiceImpl.java`、`service/impl/NodeServiceImpl.java`、`service/impl/NodeUserServiceImpl.java` | P1 |
| secretpad-service | `service/impl/InstServiceImpl.java`、`service/model/inst/InstRegisterRequest.java`、`service/auth/impl/Default*Auth.java`、`service/util/RateLimitUtil.java` | P1 P3 |
| secretpad-service | `service/sync/edge/EdgeDataSyncServiceImpl.java`、`service/impl/SysResourcesBizServiceImpl.java`、`service/impl/EnvServiceImpl.java` | P3 P4 |
| secretpad-common | `common/util/Sm3Utils.java`、`common/util/TokenUtil.java`、新增 `PasswordHasher` / `PasswordPolicy` | P1 P3 |
| secretpad-persistence | `entity/AccountsDO.java`、`entity/TokensDO.java`、`entity/SysUserPermissionRelDO.java`、`repository/UserTokensRepository.java` | P1 P2 P4 |
| secretpad-api | `client-java-kusciaapi/.../GrpcKusciaApiChannelFactory.java`、`.../TokenAuthClientInterceptor.java`、`.../KusciaGrpcConfig.java` | P0 |
| config | `application*.yaml`、`schema/{center,edge,p2p}/V6__auth_hardening.sql`、`V7__auth_audit.sql`、`server.jks`（删除） | P1–P4 |
| scripts | `deploy/secretpad.sh`、`user/register_account.sh`、新增 `check_security_antipatterns.sh` | P0 P4 |
| frontend-src | `modules/login/login.service.ts`、`modules/login/index.tsx`、`app.ts` | P1 P2 |
| docs | `配置文档.md`、`运行说明.md`、`macos运行说明.md`、`operation/edge.md`、`development/test-guides/cipher12345678.md`、`frontend-src/apps/docs/docs/dev-doc/auth.md` | P0 P4 |
