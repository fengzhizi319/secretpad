# SecretPad 前端重构设计文档（综合最优版）

> 本目录为综合 Kimi 方案与 Qoder 方案后的最优版本，取两者之长：
> - **Kimi 方案优势**：量化诊断、FSD widgets/entities 层、灰度双轨迁移、DAG 独立包、平台类型处理、脚手架脚本
> - **Qoder 方案优势**：TanStack Router 类型安全、openapi-fetch 零运行时、Biome 格式化、领域端口概念、i18n、可扩展性设计

---

## 文件索引

| 文件 | 说明 | 阅读顺序 |
|------|------|----------|
| [01-工业化前端设计要求.md](./01-工业化前端设计要求.md) | 工业级前端应满足的 13 个维度设计要求 + 评估矩阵 | 1 |
| [02-新架构设计方案.md](./02-新架构设计方案.md) | 现有架构诊断 + 新架构设计 + 技术选型 + 迁移路线 | 2 |
| [03-新工程目录结构.md](./03-新工程目录结构.md) | 完整目录结构（apps/packages/tooling）+ 依赖规则 | 3 |
| [04-UI设计图.html](./04-UI设计图.html) | 交互式 HTML 设计图：Dashboard + DAG 画布 + 架构图 | 4 |

---

## 如何查看 UI 设计图

```bash
# 方式 1：直接用浏览器打开
open /Users/charles/Documents/code/sfwork/secretpad/web/design/04-UI设计图.html

# 方式 2：启动本地静态服务器（推荐）
cd /Users/charles/Documents/code/sfwork/secretpad/web/design
python3 -m http.server 8080
# 访问 http://localhost:8080/04-UI设计图.html
```

设计图支持：
- 顶部 Tab 切换页面（Dashboard / DAG 画布 / 架构图）
- 右上角主题切换（亮色 / 暗色）
- 响应式布局

---

## 核心技术决策

| 决策点 | 选择 | 来源 |
|--------|------|------|
| 构建工具 | Vite 5 | 两者一致 |
| 路由 | TanStack Router | Qoder（类型安全、loader） |
| 服务端状态 | TanStack Query v5 | 两者一致 |
| 客户端状态 | Zustand + Immer | 两者一致 |
| API 客户端 | openapi-fetch + Zod 校验 | Qoder(零运行时) + Kimi(Zod) |
| 格式化 | Biome | Qoder（更快） |
| 架构分层 | FSD 6 层（含 widgets + entities） | Kimi（更完整） |
| DAG | 独立 packages/dag-next | Kimi（更好隔离） |
| 迁移策略 | 灰度双轨 + Nginx 路径切换 | Kimi（更实用） |
| 权限 | 2 个守卫替代 10 个 wrapper | Kimi（更具体） |
| 可扩展性 | 微前端 + 插件 + SSR 预留 | Qoder（更前瞻） |
| 国际化 | ICU MessageFormat | Qoder（更完备） |

---

## 后续工作建议

1. 创建 `frontend-next` 工程脚手架（Vite 5 + pnpm + Turborepo）
2. 先落地 `packages/api-client` 和 `packages/design-system`，作为旧新共用基础
3. 按迁移路线图逐步迁移：P0 基础 → P1 核心 → P2 复杂 → P3 加固
4. 补齐 E2E（Playwright）与性能监控（Web Vitals + Sentry）
5. 推动后端补齐 OpenAPI/Swagger 描述
