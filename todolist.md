# FlowAI 前端技术栈学习清单

> 目标：在完成 [React Tutorial Full Course - Beginner to Pro](https://www.youtube.com/watch?v=TtPXvEcE11E) 后，补齐独立开发、测试和交付 FlowAI 前端所需的能力。

## 使用方式

- [ ] 每完成一个知识点，至少编写一个小示例或在 FlowAI 中完成一次实践
- [ ] 每个阶段完成后运行 `npm run lint`、`npm test` 和 `npm run build`
- [ ] 不只记录“看过”，必须能解释原理、独立实现并完成测试

## 阶段 0：完成 React 课程

- [ ] JSX、组件和 Props
- [ ] State、事件处理和受控输入
- [ ] Hooks 与自定义 Hook
- [ ] Vite 项目结构
- [ ] React Router
- [ ] Fetch、数据读取和数据修改
- [ ] Vitest 基础
- [ ] React 19 更新
- [ ] React + TypeScript 基础
- [ ] 独立完成课程中的 Chatbot 项目
- [ ] 独立完成课程中的 Ecommerce 项目

验收标准：

- [ ] 不参考课程代码，独立实现一个具有路由、表单和 API 请求的小型 React 应用

## 阶段 1：JavaScript 与 TypeScript

### JavaScript

- [ ] 掌握作用域、闭包和模块
- [ ] 掌握 Promise、`async/await` 和错误传播
- [ ] 理解事件循环、微任务和宏任务
- [ ] 熟练使用 `map`、`filter`、`reduce` 和不可变更新
- [ ] 掌握解构、展开运算符、可选链和空值合并
- [ ] 理解引用相等与 React 重渲染之间的关系

### TypeScript

- [ ] 掌握接口、类型别名、联合类型和交叉类型
- [ ] 掌握泛型及其约束
- [ ] 掌握类型收窄、判别联合和类型守卫
- [ ] 正确使用 `unknown`，避免滥用 `any`
- [ ] 掌握 `Partial`、`Pick`、`Omit`、`Record` 等 Utility Types
- [ ] 为组件 Props、表单数据和 API DTO 建模
- [ ] 理解可选字段、`null` 和 `undefined` 的区别
- [ ] 能读懂 FlowAI 的严格 TypeScript 配置

实践任务：

- [ ] 阅读 `frontend/src/features/project-shell/project-model.ts`
- [ ] 为一个未经校验的 API 响应添加 Zod Schema 和推导类型
- [ ] 重构一个复杂组件的 Props，使其不再包含 `any`

## 阶段 2：浏览器、HTTP 与认证

- [ ] 理解 HTTP 方法、状态码、Header 和请求体
- [ ] 理解 Cookie、Local Storage 和 Session Storage 的差异
- [ ] 理解 Access Token 与 Refresh Token 生命周期
- [ ] 理解 `HttpOnly`、`Secure` 和 `SameSite`
- [ ] 理解 CORS、CSRF 和 XSS
- [ ] 掌握 Fetch API 和统一错误处理
- [ ] 使用 `AbortController` 取消请求
- [ ] 处理超时、重试和重复请求
- [ ] 正确处理 `401`、`403`、`404`、`422`、`429` 和 `500`
- [ ] 理解前端路由守卫不能替代后端权限校验

实践任务：

- [ ] 阅读 `frontend/src/api/client.ts`
- [ ] 画出登录、刷新 Token、退出登录的完整时序
- [ ] 为 API Client 补充超时或取消请求练习
- [ ] 验证退出登录后 React Query 缓存被清除

## 阶段 3：React 状态与组件设计

- [ ] 区分本地 UI 状态、表单状态、URL 状态和服务端状态
- [ ] 理解状态提升和状态共置
- [ ] 掌握 `useReducer` 的适用场景
- [ ] 谨慎使用 Context，避免所有状态全局化
- [ ] 设计受控组件和非受控组件
- [ ] 掌握组合优于继承的组件设计
- [ ] 提取可复用 Hook，但避免过早抽象
- [ ] 为 Loading、Error、Empty 和 Success 状态设计统一结构
- [ ] 理解 Error Boundary 的用途和限制

实践任务：

- [ ] 为一个 FlowAI 页面列出全部状态及其正确归属
- [ ] 将一个过大的组件拆分为容器组件和展示组件
- [ ] 为异步页面补齐 Loading、Error 和 Empty 状态

## 阶段 4：React Router 与 URL 状态

- [ ] 掌握嵌套路由和动态参数
- [ ] 掌握查询参数的读取和更新
- [ ] 将筛选、视图模式和分页状态保存在 URL 中
- [ ] 正确处理登录后的重定向
- [ ] 正确处理不存在和无权限的路由
- [ ] 理解浏览器前进、后退和刷新后的状态恢复
- [ ] 学习路由级代码分割和懒加载

实践任务：

- [ ] 阅读 `frontend/src/features/project-shell/route-utils.ts`
- [ ] 验证 Issue 筛选条件在刷新后仍然存在
- [ ] 为一个页面添加可分享的 URL 状态

## 阶段 5：TanStack Query

- [ ] 掌握 `useQuery`、`useMutation` 和 `useInfiniteQuery`
- [ ] 设计稳定、分层的 Query Key
- [ ] 理解 `staleTime`、`gcTime` 和重试策略
- [ ] 掌握缓存失效和精确更新缓存
- [ ] 掌握乐观更新、失败回滚和最终同步
- [ ] 掌握游标分页和无限查询
- [ ] 处理依赖查询和条件查询
- [ ] 处理请求去重和竞态条件
- [ ] 在登录、退出和 Workspace 切换时管理缓存边界
- [ ] 区分 React Query 缓存与业务持久化数据

实践任务：

- [ ] 阅读 `frontend/src/features/board/useBoardQueries.ts`
- [ ] 阅读 `frontend/src/features/board/useBoardMutations.ts`
- [ ] 阅读 `frontend/src/features/board/useBoardColumnPagination.ts`
- [ ] 解释 Issue 拖拽的乐观更新和失败回滚流程
- [ ] 为一个新增 Mutation 添加缓存更新和测试

## 阶段 6：表单与 Zod 校验

- [ ] 掌握 React Hook Form 的注册、Controller 和表单状态
- [ ] 使用 Zod 定义运行时 Schema
- [ ] 使用 `zodResolver` 连接表单与 Schema
- [ ] 处理字段级和表单级错误
- [ ] 将后端校验错误映射到对应字段
- [ ] 处理可选字段、日期、枚举和动态数组
- [ ] 防止重复提交
- [ ] 正确设计提交中、成功和失败状态
- [ ] 理解客户端校验不能替代服务端校验

实践任务：

- [ ] 阅读 `frontend/src/pages/LoginPage.tsx`
- [ ] 阅读 Issue 创建和编辑表单
- [ ] 为一个表单补齐无障碍 Label、错误提示和焦点移动
- [ ] 编写成功提交、校验失败和服务器失败测试

## 阶段 7：CSS、Tailwind 与组件系统

- [ ] 掌握 Box Model、Normal Flow 和定位
- [ ] 熟练使用 Flexbox 和 Grid
- [ ] 理解层叠、选择器优先级和 `z-index`
- [ ] 掌握响应式布局和移动优先设计
- [ ] 掌握 CSS Variables 和主题 Token
- [ ] 掌握 Tailwind CSS 4 的常用模式
- [ ] 理解 shadcn/ui 是源码组件而不是黑盒依赖
- [ ] 掌握 Radix UI 的无障碍交互基础
- [ ] 设计 Button、Dialog、Drawer、Dropdown 等可复用组件
- [ ] 掌握过渡、动画和 `prefers-reduced-motion`

实践任务：

- [ ] 阅读 `frontend/src/index.css`
- [ ] 阅读 `frontend/src/components/ui/button.tsx`
- [ ] 在桌面、平板和手机宽度检查 Project 页面
- [ ] 使用键盘完成 Dialog 打开、操作和关闭流程

## 阶段 8：前端测试

### Vitest 与 Testing Library

- [ ] 理解单元测试、组件测试、集成测试和 E2E 测试的边界
- [ ] 按用户行为测试，不测试组件内部实现
- [ ] 掌握 `screen`、语义查询和 `userEvent`
- [ ] 测试 Loading、Error、Empty 和 Success 状态
- [ ] Mock API 请求和异常响应
- [ ] 测试表单校验与提交
- [ ] 测试乐观更新与失败回滚
- [ ] 避免脆弱的快照测试

### Playwright

- [ ] 掌握 Locator 和自动等待
- [ ] 测试登录、注册和退出
- [ ] 测试创建、编辑和移动 Issue
- [ ] 测试 Workspace 和权限边界
- [ ] 使用 Trace、截图和视频定位失败
- [ ] 保证测试数据隔离和可重复执行

实践任务：

- [ ] 阅读 `frontend/vitest.config.ts`
- [ ] 阅读 `frontend/playwright.config.ts`
- [ ] 为一个关键 Mutation 添加组件测试
- [ ] 为一个核心用户流程添加 Playwright 测试

## 阶段 9：可访问性与安全

- [ ] 使用语义化 HTML
- [ ] 确保全部交互可使用键盘完成
- [ ] 正确管理 Dialog 和 Drawer 的焦点
- [ ] 为表单提供 Label、描述和错误关联
- [ ] 检查颜色对比度
- [ ] 正确使用 ARIA，避免用 ARIA 修补错误 HTML
- [ ] 理解前端 XSS 风险和危险 HTML
- [ ] 不在前端保存 Provider Secret
- [ ] 不将前端隐藏按钮当作权限控制
- [ ] 避免在日志或错误信息中泄露 Token 和敏感数据

验收标准：

- [ ] 仅使用键盘完成登录、项目切换、创建 Issue 和编辑 Issue
- [ ] 使用浏览器无障碍工具检查一个主要页面
- [ ] 人工检查认证数据的存储和清理路径

## 阶段 10：性能与工程化

- [ ] 使用 React DevTools 和 Profiler 定位真实瓶颈
- [ ] 理解 `memo`、`useMemo` 和 `useCallback` 的适用条件
- [ ] 使用路由懒加载减少初始 Bundle
- [ ] 对大型列表使用虚拟化
- [ ] 掌握防抖和节流
- [ ] 优化字体、图片和静态资源
- [ ] 使用浏览器 Network、Performance 和 Lighthouse 面板
- [ ] 掌握 ESLint 和 TypeScript 构建检查
- [ ] 理解 Vite 环境变量及其公开范围
- [ ] 理解 Nginx SPA 路由回退和 `/api` 代理
- [ ] 理解 Docker 多阶段构建和 GitHub Actions CI

实践任务：

- [ ] 分析 FlowAI 当前生产 Bundle
- [ ] 检查是否存在不必要的重复请求或重渲染
- [ ] 验证生产构建刷新嵌套路由不会返回 404
- [ ] 阅读 `.github/workflows/ci.yml`

## 综合项目验收

- [ ] 独立新增一个包含列表、详情和创建表单的功能
- [ ] API 类型和运行时响应都有明确校验
- [ ] 使用 TanStack Query 管理读取、修改和缓存
- [ ] 筛选和分页能够通过 URL 恢复
- [ ] 包含 Loading、Error、Empty 和 Success 状态
- [ ] 支持桌面和移动端
- [ ] 支持键盘操作和基本无障碍要求
- [ ] 包含组件测试和至少一个 E2E 测试
- [ ] `npm run lint` 通过
- [ ] `npm test` 通过
- [ ] `npm run build` 通过
- [ ] 能说明安全、性能和缓存方面的设计取舍

## 暂不优先学习

- [ ] Redux Toolkit：出现复杂的纯客户端全局状态后再学习
- [ ] Next.js：FlowAI 当前是 Vite SPA，不是必要依赖
- [ ] GraphQL：当前 REST API 已足够
- [ ] Webpack 深入配置：当前由 Vite 管理构建
- [ ] 微前端：当前项目规模不需要
- [ ] React Native：与当前 Web 项目目标无关

## 完成定义

满足以下条件时，可以认为已补齐 FlowAI 当前阶段的前端技术栈：

- [ ] 可以在不复制教程代码的情况下独立完成新功能
- [ ] 可以定位并修复请求、状态、类型、样式和测试问题
- [ ] 可以解释本地状态、URL 状态、表单状态和服务端状态的边界
- [ ] 可以安全处理认证、权限错误和敏感信息
- [ ] 可以为关键功能编写可靠的组件测试与 E2E 测试
- [ ] 可以独立完成构建、容器化验证和 CI 问题排查
