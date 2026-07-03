# FlowAI MVP 分阶段计划：面向奥克兰实习投递的 Linear 风格任务管理项目

## Summary

FlowAI 默认定位为一个面向奥克兰软件工程、全栈或后端实习投递的作品集项目。目标不是完整复刻 Linear，而是在 4 周内做出一个可运行、可部署、可演示、可讲技术深度的 MVP。

项目重点展示企业级全栈开发能力：Spring Boot 后端、JWT 认证、多租户权限、PostgreSQL 数据建模、Flyway 数据库迁移、React 交互体验、看板拖拽、AI 辅助、自动化测试、Docker Compose 一键启动，以及清晰的 README 和面试讲解材料。

技术栈固定为：

- Java 21
- Spring Boot 3.5.x
- Spring Web
- Spring Data JPA / Hibernate
- PostgreSQL
- Flyway
- Spring Security + JWT
- Spring AI
- JUnit 5 + Testcontainers
- Docker Compose
- React + TypeScript
- Vite
- React Router
- TanStack Query
- React Hook Form + Zod
- Tailwind CSS
- shadcn/ui
- dnd-kit
- Recharts

## Implementation Phases

### Phase 0：项目定位与工程初始化，0.5 周

交付内容：

- 建立 monorepo：`backend/`、`frontend/`、`docker/`、`docs/`。
- 后端初始化 Spring Boot 3.5.x，启用 Web、Validation、JPA、PostgreSQL、Flyway、Security、Spring AI、Actuator、Testcontainers。
- 前端初始化 Vite React TypeScript，接入 Tailwind CSS、shadcn/ui、React Router、TanStack Query。
- 配置 Docker Compose：PostgreSQL、后端、前端开发环境。
- 写第一版 README：项目介绍、技术栈、架构图、启动命令、演示账号占位。

验收标准：

- `docker compose up` 能启动 PostgreSQL。
- 后端 health check 可访问。
- 前端首页可访问。
- README 能让面试官 3 分钟理解项目定位。

### Phase 1：认证、组织与权限，0.75 周

交付内容：

- 实现注册、登录、刷新 Token、当前用户信息。
- 使用 JWT 做 Spring Security 认证。
- 实现 Organization / Workspace / Member 基础模型。
- 实现角色：`OWNER`、`ADMIN`、`MEMBER`、`GUEST`。
- 所有业务表预留 `organization_id`，从第一天开始做租户隔离。
- 前端实现登录页、注册页、受保护路由、主应用 Layout、用户菜单。

核心接口：

- `POST /api/auth/register`
- `POST /api/auth/login`
- `POST /api/auth/refresh`
- `GET /api/me`
- `GET /api/organizations/current`
- `GET /api/organizations/current/members`

验收标准：

- 未登录用户不能访问 `/app`。
- 登录后可进入默认 workspace。
- 后端接口能根据 JWT 识别当前用户和组织。
- 不同角色访问受限接口时返回正确的 403。

### Phase 2：任务管理核心能力，1 周

交付内容：

- 实现 Project、Issue、WorkflowState、Label、Comment、ActivityEvent。
- 支持创建项目、创建任务、编辑任务、删除或归档任务。
- 支持任务状态、优先级、负责人、标签、截止日期。
- 每次任务状态、负责人、优先级、标题变更写入活动流。
- 前端实现 Issue List、Issue Detail Drawer、Issue Form、Filter Bar。
- 表单使用 React Hook Form + Zod；接口缓存使用 TanStack Query。

核心接口：

- `GET /api/projects`
- `POST /api/projects`
- `GET /api/issues`
- `POST /api/issues`
- `GET /api/issues/{id}`
- `PATCH /api/issues/{id}`
- `POST /api/issues/{id}/comments`
- `GET /api/issues/{id}/activities`

验收标准：

- 用户能完整完成：创建项目 -> 创建任务 -> 编辑状态/负责人/优先级 -> 评论 -> 查看活动记录。
- Issue 查询支持按项目、状态、负责人、优先级、关键词过滤。
- 所有查询都基于当前 organization 隔离数据。
- Flyway migration 可以从空库完整建表。

### Phase 3：Linear 风格体验，0.75 周

交付内容：

- 实现 Kanban Board，按 WorkflowState 分列展示任务。
- 使用 dnd-kit 支持拖拽任务改变状态和排序。
- 前端使用 TanStack Query 做乐观更新，失败时回滚。
- 实现侧边栏：Workspace、Projects、Views。
- 实现快速创建任务弹窗。
- 使用 shadcn/ui 做一致的按钮、表单、Dialog、Sheet、Dropdown、Tabs、Command。

核心接口：

- `GET /api/issues/board`
- `PATCH /api/issues/{id}/state`
- `PATCH /api/issues/reorder`

验收标准：

- 拖动任务到不同列后，状态立即更新并持久化。
- 刷新页面后看板顺序保持一致。
- 接口失败时前端能恢复原状态并提示错误。
- UI 第一屏就像一个真实工作台，而不是 landing page。

### Phase 4：AI、统计与作品亮点，0.5 周

交付内容：

- Spring AI 接入一个可配置模型 provider。
- 实现 AI 任务拆解：输入一个大任务，返回若干子任务建议。
- 实现 AI 摘要：对任务描述和评论生成摘要。
- 实现基础统计页：任务总数、完成率、按状态分布、按负责人分布、近 14 天完成趋势。
- 使用 Recharts 展示统计图表。
- AI 建议只作为草稿返回，用户确认后才写入业务数据。

核心接口：

- `POST /api/ai/issues/breakdown`
- `POST /api/ai/issues/summarize`
- `GET /api/analytics/overview`

验收标准：

- AI 生成内容不会自动污染正式任务数据。
- 没有配置 AI Key 时，系统能禁用 AI 功能并给出清晰提示。
- 统计页能展示真实任务数据，不使用硬编码 mock 数据。

### Phase 5：测试、部署与投递材料，1 周

交付内容：

- 后端补齐 Service、Controller、Repository 集成测试。
- 使用 Testcontainers 跑 PostgreSQL + Flyway + JPA 集成测试。
- 前端完成 build、lint、核心表单校验。
- Docker Compose 支持一键启动完整应用。
- README 补齐：功能截图、架构图、数据库设计、API 示例、测试命令、部署说明。
- 准备简历描述、面试讲述稿和 demo checklist。

验收标准：

- 后端 `test` 通过。
- 前端 `build` 通过。
- 新机器按 README 可以启动项目。
- 简历 bullet 能清楚体现：Spring Boot、JWT、多租户、PostgreSQL、Flyway、React、拖拽、AI、测试、Docker。

## Public APIs / Interfaces

主要数据类型固定如下：

- `User`：用户账号信息。
- `Organization`：租户边界。
- `Member`：用户在组织中的角色。
- `Project`：任务所属项目。
- `WorkflowState`：任务状态列，例如 Backlog、Todo、In Progress、Done。
- `Issue`：核心任务。
- `Comment`：任务评论。
- `ActivityEvent`：任务变更历史。
- `AnalyticsOverview`：统计页数据。
- `AiSuggestion`：AI 返回的草稿建议。

统一 API 约定：

- 请求认证使用 `Authorization: Bearer <token>`。
- 分页接口使用 `page`、`size`、`sort`。
- 错误响应包含 `code`、`message`、`details`。
- 所有业务接口从 JWT 上下文解析当前用户和组织，不允许前端直接指定 `organizationId` 来越权查询。

## Test Plan

后端测试：

- Auth：注册、登录、刷新 token、无效 token、过期 token。
- Security：`OWNER`、`ADMIN`、`MEMBER`、`GUEST` 权限覆盖。
- Tenant isolation：用户不能读写其他 organization 的项目和任务。
- Issue：创建、编辑、筛选、状态变更、排序、评论、活动流。
- Flyway：空 PostgreSQL 容器中能完整迁移并启动 JPA。
- AI：正常返回、provider 未配置、模型调用失败时的降级行为。

前端测试与验收：

- 登录后跳转到 app，未登录访问 app 被重定向。
- Issue Form 的 Zod 校验正确提示。
- Issue List 筛选结果与后端一致。
- Board 拖拽后 UI 乐观更新，失败时回滚。
- 生产构建无 TypeScript 错误。
- 主要页面在桌面和移动宽度下无明显溢出或遮挡。

## Assumptions

- 默认目标岗位是奥克兰的软件工程、全栈或后端实习，因此优先展示工程完整度，而不是只做炫酷 UI。
- 默认 MVP 周期为 4 周；如果只有 2 周，砍掉 AI 摘要、统计页和部分测试，但保留认证、任务、看板、Docker、README。
- 默认最终交付为线上 Demo + GitHub README + 本地 Docker Compose。
- 默认不做实时协作、邮件通知、文件附件、Webhook、移动端 App、微服务拆分和复杂 Roadmap/Cycle 功能。
- 默认先做单体后端，避免为了“企业级”过早微服务化；企业感通过权限、测试、迁移、审计、部署和文档体现。
