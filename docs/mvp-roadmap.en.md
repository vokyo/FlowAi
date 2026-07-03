# FlowAI MVP Roadmap: A Linear-Inspired Task Management Project for Auckland Internship Applications

## Summary

FlowAI is positioned as a portfolio project for software engineering, full-stack, or backend internship applications in Auckland. The goal is not to fully clone Linear, but to build an MVP within 4 weeks that is runnable, deployable, demo-friendly, and strong enough to discuss in technical interviews.

The project is designed to demonstrate enterprise-style full-stack engineering skills: a Spring Boot backend, JWT authentication, multi-tenant authorization, PostgreSQL data modeling, Flyway database migrations, React interaction design, Kanban drag and drop, AI assistance, automated testing, Docker Compose startup, and clear README plus interview materials.

The fixed technology stack is:

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

### Phase 0: Project Positioning and Engineering Setup, 0.5 Week

Deliverables:

- Create the monorepo structure: `backend/`, `frontend/`, `docker/`, `docs/`.
- Initialize the Spring Boot 3.5.x backend with Web, Validation, JPA, PostgreSQL, Flyway, Security, Spring AI, Actuator, and Testcontainers.
- Initialize the Vite React TypeScript frontend and integrate Tailwind CSS, shadcn/ui, React Router, and TanStack Query.
- Configure Docker Compose for PostgreSQL, backend, and frontend development.
- Write the first README version with project introduction, technology stack, architecture diagram, startup commands, and demo account placeholder.

Acceptance criteria:

- `docker compose up` can start PostgreSQL.
- Backend health check is accessible.
- Frontend home page is accessible.
- The README lets an interviewer understand the project positioning within 3 minutes.

### Phase 1: Authentication, Organization, and Authorization, 0.75 Week

Deliverables:

- Implement registration, login, token refresh, and current user profile.
- Use JWT for Spring Security authentication.
- Implement the foundational Organization / Workspace / Member model.
- Implement roles: `OWNER`, `ADMIN`, `MEMBER`, `GUEST`.
- Reserve `organization_id` on all business tables and enforce tenant isolation from day one.
- Implement the frontend login page, registration page, protected routes, main app layout, and user menu.

Core APIs:

- `POST /api/auth/register`
- `POST /api/auth/login`
- `POST /api/auth/refresh`
- `GET /api/me`
- `GET /api/organizations/current`
- `GET /api/organizations/current/members`

Acceptance criteria:

- Unauthenticated users cannot access `/app`.
- Authenticated users can enter the default workspace.
- Backend APIs can identify the current user and organization from JWT.
- Restricted APIs return the correct 403 response for insufficient roles.

### Phase 2: Core Issue Management, 1 Week

Deliverables:

- Implement Project, Issue, WorkflowState, Label, Comment, and ActivityEvent.
- Support project creation, issue creation, issue editing, and issue deletion or archiving.
- Support issue status, priority, assignee, labels, and due date.
- Write activity events whenever issue status, assignee, priority, or title changes.
- Implement Issue List, Issue Detail Drawer, Issue Form, and Filter Bar on the frontend.
- Use React Hook Form + Zod for forms and TanStack Query for API caching.

Core APIs:

- `GET /api/projects`
- `POST /api/projects`
- `GET /api/issues`
- `POST /api/issues`
- `GET /api/issues/{id}`
- `PATCH /api/issues/{id}`
- `POST /api/issues/{id}/comments`
- `GET /api/issues/{id}/activities`

Acceptance criteria:

- A user can complete the full workflow: create project -> create issue -> edit status/assignee/priority -> comment -> view activity history.
- Issue queries support filtering by project, status, assignee, priority, and keyword.
- All queries are isolated by the current organization.
- Flyway migrations can build the full schema from an empty database.

### Phase 3: Linear-Inspired Product Experience, 0.75 Week

Deliverables:

- Implement a Kanban Board that displays issues grouped by WorkflowState.
- Use dnd-kit to support dragging issues to change status and order.
- Use TanStack Query optimistic updates on the frontend, with rollback on failure.
- Implement the sidebar with Workspace, Projects, and Views.
- Implement a quick issue creation dialog.
- Use shadcn/ui for consistent Button, Form, Dialog, Sheet, Dropdown, Tabs, and Command components.

Core APIs:

- `GET /api/issues/board`
- `PATCH /api/issues/{id}/state`
- `PATCH /api/issues/reorder`

Acceptance criteria:

- After an issue is dragged to another column, its status updates immediately and persists.
- Board order remains stable after page refresh.
- When an API request fails, the frontend restores the previous state and shows an error.
- The first screen feels like a real workbench rather than a landing page.

### Phase 4: AI, Analytics, and Portfolio Highlights, 0.5 Week

Deliverables:

- Integrate Spring AI with a configurable model provider.
- Implement AI issue breakdown: input a large task and return suggested subtasks.
- Implement AI summary: summarize an issue description and comments.
- Implement a basic analytics page: total issues, completion rate, distribution by status, distribution by assignee, and completion trend over the last 14 days.
- Use Recharts to display analytics charts.
- Keep AI suggestions as drafts only; write them to business data only after user confirmation.

Core APIs:

- `POST /api/ai/issues/breakdown`
- `POST /api/ai/issues/summarize`
- `GET /api/analytics/overview`

Acceptance criteria:

- AI-generated content does not automatically pollute official issue data.
- When no AI key is configured, the system can disable AI features and show a clear message.
- The analytics page displays real issue data and does not use hardcoded mock data.

### Phase 5: Testing, Deployment, and Application Materials, 1 Week

Deliverables:

- Add backend Service, Controller, and Repository integration tests.
- Use Testcontainers to run PostgreSQL + Flyway + JPA integration tests.
- Complete frontend build, lint, and core form validation checks.
- Make Docker Compose support one-command startup for the complete application.
- Complete the README with feature screenshots, architecture diagram, database design, API examples, test commands, and deployment instructions.
- Prepare resume bullet points, interview talking points, and a demo checklist.

Acceptance criteria:

- Backend `test` passes.
- Frontend `build` passes.
- A new machine can start the project by following the README.
- Resume bullet points clearly demonstrate Spring Boot, JWT, multi-tenancy, PostgreSQL, Flyway, React, drag and drop, AI, testing, and Docker.

## Public APIs / Interfaces

The main data types are fixed as follows:

- `User`: user account information.
- `Organization`: tenant boundary.
- `Member`: a user's role within an organization.
- `Project`: project that owns issues.
- `WorkflowState`: issue status column, such as Backlog, Todo, In Progress, Done.
- `Issue`: the core task entity.
- `Comment`: issue comment.
- `ActivityEvent`: issue change history.
- `AnalyticsOverview`: analytics page data.
- `AiSuggestion`: draft suggestions returned by AI.

Unified API conventions:

- Authenticated requests use `Authorization: Bearer <token>`.
- Paginated APIs use `page`, `size`, and `sort`.
- Error responses include `code`, `message`, and `details`.
- All business APIs derive the current user and organization from JWT context. The frontend must not directly provide `organizationId` for privileged querying.

## Test Plan

Backend tests:

- Auth: registration, login, token refresh, invalid token, expired token.
- Security: coverage for `OWNER`, `ADMIN`, `MEMBER`, and `GUEST` permissions.
- Tenant isolation: users cannot read or write projects and issues from another organization.
- Issue: create, edit, filter, status change, reorder, comment, and activity stream.
- Flyway: a fresh PostgreSQL container can run all migrations and start JPA successfully.
- AI: successful response, provider not configured, and graceful degradation when model calls fail.

Frontend tests and acceptance checks:

- After login, the user is redirected to the app; unauthenticated access to the app is redirected.
- Issue Form shows correct Zod validation messages.
- Issue List filtering matches backend results.
- Board drag and drop performs optimistic updates and rolls back on failure.
- Production build has no TypeScript errors.
- Main pages have no obvious overflow or content overlap on desktop and mobile widths.

## Assumptions

- The default target roles are software engineering, full-stack, or backend internships in Auckland, so engineering completeness is prioritized over flashy UI.
- The default MVP timeline is 4 weeks. If only 2 weeks are available, remove AI summary, analytics, and part of the test coverage, while keeping authentication, issues, board, Docker, and README.
- The default final delivery is an online demo + GitHub README + local Docker Compose.
- Real-time collaboration, email notifications, file attachments, webhooks, mobile apps, microservice splitting, and complex Roadmap/Cycle features are out of scope by default.
- The backend starts as a modular monolith. Enterprise quality is demonstrated through authorization, testing, migrations, auditing, deployment, and documentation rather than premature microservices.
