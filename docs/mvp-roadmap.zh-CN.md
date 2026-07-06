# FlowAI MVP Roadmap

## 概要

FlowAI 是一个面向奥克兰 software engineering、full-stack、backend 实习投递的作品集 MVP。它参考 Linear 风格任务管理，但当前产品定位是 workspace-first 的项目 issue tracker，而不是完整复刻 Linear。

项目重点展示企业级全栈开发能力：

- Spring Boot 后端开发
- JWT 认证和 refresh token rotation
- PostgreSQL 数据建模
- Flyway 数据库迁移
- Workspace membership 和角色权限
- React 应用架构
- Docker 本地开发环境
- 自动化测试和清晰技术文档

## 固定技术方向

- Java 21
- Spring Boot 3.5.x
- Spring Web
- Spring Data JPA / Hibernate
- PostgreSQL
- Flyway
- Spring Security + JWT
- Spring AI 用于后续 AI 功能
- JUnit 5 + Testcontainers
- Docker Compose
- React + TypeScript
- Vite
- React Router
- TanStack Query
- Tailwind CSS
- shadcn/ui
- 后续计划：React Hook Form、Zod、dnd-kit、Recharts

## 实现阶段

### Phase 0：项目定位与工程初始化

状态：已完成。

交付内容：

- 创建 `backend/`、`frontend/`、`docs/` 的 monorepo 结构。
- 初始化 Spring Boot 后端，接入 Web、Validation、JPA、PostgreSQL、Flyway、Security、Actuator、Testcontainers。
- 初始化 Vite React TypeScript 前端。
- 接入 Tailwind CSS、shadcn/ui、React Router、TanStack Query。
- 使用 Docker Compose 配置本地 PostgreSQL。
- 编写第一版 README，说明项目定位、技术栈、架构、启动命令和路线图。

验收标准：

- `docker compose up -d postgres` 可以启动 PostgreSQL。
- 后端 health check 可以访问。
- 前端开发服务器可以访问。
- README 能让面试官在几分钟内理解项目。

### Phase 1：认证与工作区访问

状态：本地完成。

交付内容：

- 实现注册、登录、刷新 token、当前 session 加载。
- 使用 JWT 做 Spring Security 认证。
- 实现 workspace-first 数据模型：
  - `users`
  - `workspaces`
  - `workspace_memberships`
  - `refresh_tokens`
- 实现成员角色：
  - `OWNER`
  - `ADMIN`
  - `MEMBER`
  - `GUEST`
- 注册时自动创建默认 workspace 和 `OWNER` membership。
- 前端实现登录页、注册页、受保护 `/app` 路由、token 存储、access token 自动刷新、当前 session 展示。

核心 API：

- `POST /api/auth/register`
- `POST /api/auth/login`
- `POST /api/auth/refresh`
- `GET /api/me`
- `GET /api/workspaces/current`
- `GET /api/workspaces/current/members`

验收标准：

- 未登录用户不能访问 `/app`。
- 注册用户会得到默认 workspace。
- 登录返回 access token 和 refresh token。
- 受保护 API 请求使用 `Authorization: Bearer <token>`。
- access token 过期后，前端可以自动 refresh 并重试原请求。
- `/api/me` 返回当前 session，也就是 `user + workspace`。

### Phase 2：项目和 Issues

状态：计划中。

交付内容：

- 实现 project 创建和 project 成员或邀请流程。
- 实现 Issue、WorkflowState、Label、Comment、ActivityEvent。
- 支持 issue 创建、编辑、归档、分配、优先级、标签、状态、截止日期。
- issue 状态、负责人、优先级、标题变化时写入 activity event。
- 前端实现 issue list、issue detail drawer、issue form、filter bar。
- 表单使用 React Hook Form + Zod。

核心 API：

- `GET /api/projects`
- `POST /api/projects`
- `GET /api/projects/{id}`
- `GET /api/issues`
- `POST /api/issues`
- `GET /api/issues/{id}`
- `PATCH /api/issues/{id}`
- `POST /api/issues/{id}/comments`
- `GET /api/issues/{id}/activities`

验收标准：

- 用户可以创建 project、邀请或添加成员、创建 issue、更新 issue、评论、查看 activity history。
- issue 查询支持按 project、status、assignee、priority、keyword 过滤。
- 业务查询基于当前 workspace 和 project membership 做隔离。
- Flyway 可以从空 PostgreSQL 数据库构建完整 schema。

### Phase 3：Linear 风格产品体验

状态：计划中。

交付内容：

- 实现按 WorkflowState 分组的 kanban board。
- 使用 dnd-kit 支持拖拽 issue 改变状态。
- 使用 TanStack Query 做 optimistic update，并在失败时 rollback。
- 实现 sidebar，包含 workspace、projects、views。
- 实现快速创建 issue。
- 使用 shadcn/ui 统一 Button、Form、Dialog、Sheet、Dropdown、Tabs、Command 等组件。

核心 API：

- `GET /api/issues/board`
- `PATCH /api/issues/{id}/state`
- `PATCH /api/issues/reorder`

验收标准：

- 拖拽 issue 后状态立即更新并持久化。
- 刷新后看板顺序保持稳定。
- API 失败时前端恢复之前状态。
- 第一屏像真实工作台，而不是 landing page。

### Phase 4：AI 与统计分析

状态：计划中。

交付内容：

- 使用可配置 provider 接入 Spring AI。
- 实现 AI issue breakdown。
- 实现 AI issue 或 project summary。
- 实现 analytics overview：总 issues、完成率、状态分布、负责人分布、完成趋势。
- 使用 Recharts 展示统计图表。
- AI 建议默认作为草稿，用户确认后才写入正式业务数据。

核心 API：

- `POST /api/ai/issues/breakdown`
- `POST /api/ai/issues/summarize`
- `GET /api/analytics/overview`

验收标准：

- AI 生成内容不会自动污染正式 issue 数据。
- 没有 provider key 时，AI 功能可以清晰禁用。
- Analytics 展示真实 issue 数据。

### Phase 5：测试、部署与求职材料

状态：计划中。

交付内容：

- 增加 backend service、controller、repository integration tests。
- 使用 Testcontainers 测试 PostgreSQL + Flyway + JPA。
- 完成 frontend build、lint、表单校验检查。
- Docker Compose 支持一条命令启动完整应用。
- 完善 README 截图、架构图、数据库设计、API 示例、测试命令、部署说明。
- 准备简历 bullet、面试讲解材料、demo checklist。

验收标准：

- Backend tests 通过。
- Frontend build 和 lint 通过。
- 新机器可以按 README 启动项目。
- 简历 bullet 能体现 Spring Boot、JWT、workspace authorization、PostgreSQL、Flyway、React、拖拽、AI、测试、Docker。

## 公共数据类型

当前已实现：

- `User`：用户账号身份。
- `Workspace`：协作边界。
- `WorkspaceMembership`：用户在 workspace 内的角色。
- `RefreshToken`：用于维持登录状态的轮换 token。

后续计划：

- `Project`：workspace 内的项目。
- `ProjectMember` 或 `ProjectInvitation`：项目级协作。
- `WorkflowState`：issue 状态列，例如 Backlog、Todo、In Progress、Done。
- `Issue`：核心工作项。
- `Comment`：issue 讨论。
- `ActivityEvent`：issue 变更历史。
- `AnalyticsOverview`：统计页数据。
- `AiSuggestion`：AI 返回的草稿建议。

## API 约定

- 受保护请求使用 `Authorization: Bearer <token>`。
- MVP 阶段前端把 access token 和 refresh token 存在本地。
- Access token 生命周期较短。
- Refresh token 在 `/api/auth/refresh` 中轮换。
- 业务 API 从 JWT 上下文解析当前 user 和 workspace。
- 前端不应该直接传 privileged `workspaceId` 来绕过权限判断。

## 测试计划

后端测试：

- Auth：注册、登录、刷新 token、无效 token、过期 token。
- Workspace access：当前 workspace、当前成员、禁用 membership。
- Security：覆盖 `OWNER`、`ADMIN`、`MEMBER`、`GUEST` 权限。
- 数据隔离：用户不能读写没有权限的 workspace 或 project 数据。
- Flyway：新的 PostgreSQL container 可以运行所有 migration 并启动 JPA。
- AI：provider 已配置、provider 缺失、模型调用失败时优雅降级。

前端检查：

- 注册后进入 `/app`。
- 登录后进入 `/app`。
- 未登录访问 `/app` 会跳转 `/login`。
- 受保护请求带 `Authorization`。
- Access token 过期后触发 refresh，并重试原请求。
- Refresh 失败时退出登录。
- Production build 没有 TypeScript 错误。
- 主要页面在桌面和移动宽度下没有明显溢出或重叠。

## 假设

- 目标受众是实习招聘团队，所以工程完整度优先于炫技 UI。
- 后端保持 modular monolith。
- 实时协作、邮件通知、文件附件、webhook、移动端、微服务拆分、复杂 roadmap/cycle 功能都不在 MVP 范围内。
