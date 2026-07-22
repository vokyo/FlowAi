# FlowAI MVP Roadmap

## Summary

FlowAI is a portfolio MVP for software engineering, full-stack, and backend internship applications in Auckland. It is inspired by Linear-style task management, but it is intentionally scoped as a workspace-first project and issue tracker rather than a full Linear clone.

The project is designed to demonstrate enterprise-style full-stack engineering skills:

- Spring Boot backend development
- JWT authentication and refresh-token rotation
- PostgreSQL schema design
- Flyway migrations
- Workspace membership and role-based access
- React application architecture
- Docker-based local development
- Automated tests and clear technical documentation

## Fixed Technology Direction

- Java 21
- Spring Boot 3.5.x
- Spring Web
- Spring Data JPA / Hibernate
- PostgreSQL
- Flyway
- Spring Security + JWT
- Spring AI for planned AI features
- JUnit 5 + Testcontainers
- Docker Compose
- React + TypeScript
- Vite
- React Router
- TanStack Query
- Tailwind CSS
- shadcn/ui
- Planned: React Hook Form, Zod, dnd-kit, Recharts

## Implementation Phases

### Phase 0: Project Positioning and Engineering Setup

Status: Completed.

Deliverables:

- Create the monorepo structure: `backend/`, `frontend/`, and `docs/`.
- Initialize the Spring Boot backend with Web, Validation, JPA, PostgreSQL, Flyway, Security, Actuator, and Testcontainers.
- Initialize the Vite React TypeScript frontend.
- Integrate Tailwind CSS, shadcn/ui, React Router, and TanStack Query.
- Configure Docker Compose for local PostgreSQL.
- Write the first README version with project positioning, stack, architecture, startup commands, and roadmap.

Acceptance criteria:

- `docker compose up -d postgres` starts PostgreSQL.
- Backend health check is accessible.
- Frontend development server is accessible.
- README explains the project within a few minutes.

### Phase 1: Authentication and Workspace Access

Status: Completed locally.

Deliverables:

- Implement registration, login, token refresh, and current session loading.
- Use JWT for Spring Security authentication.
- Implement workspace-first data model:
  - `users`
  - `workspaces`
  - `workspace_memberships`
  - `refresh_tokens`
- Implement membership roles:
  - `OWNER`
  - `ADMIN`
  - `MEMBER`
  - `GUEST`
- Create a default workspace and `OWNER` membership during registration.
- Implement frontend login page, registration page, protected `/app` route, token storage, automatic access-token refresh, and current session display.

Core APIs:

- `POST /api/auth/register`
- `POST /api/auth/login`
- `POST /api/auth/refresh`
- `GET /api/me`
- `GET /api/workspaces/current`
- `GET /api/workspaces/current/members`

Acceptance criteria:

- Unauthenticated users cannot access `/app`.
- A registered user gets a default workspace.
- Login returns an access token and refresh token.
- Protected API requests use `Authorization: Bearer <token>`.
- Expired access tokens can be refreshed automatically by the frontend.
- `/api/me` returns the current session as `user + workspace`.

### Phase 2: Projects and Issues

Status: Main scope completed.

Deliverables:

- Implement project creation and project membership or invitation flows.
- Implement Issue, WorkflowState, Label, Comment, and ActivityEvent.
- Support issue creation, editing, archiving, assignment, priority, labels, status, and due dates.
- Write activity events when issue status, assignee, priority, or title changes.
- Implement issue list, issue detail drawer, issue form, and filter bar.
- Add React Hook Form + Zod for forms.

Core APIs:

- `GET /api/projects`
- `POST /api/projects`
- `GET /api/projects/{id}`
- `GET /api/issues`
- `POST /api/issues`
- `GET /api/issues/{id}`
- `PATCH /api/issues/{id}`
- `POST /api/issues/{id}/comments`
- `GET /api/issues/{id}/activities`

Acceptance criteria:

- A user can create a project, invite or add members, create issues, update issues, comment, and view activity history.
- Issue queries support filtering by project, status, assignee, priority, and keyword.
- Business queries are isolated by the current workspace and project membership.
- Flyway migrations can build the schema from an empty PostgreSQL database.

### Phase 3: Linear-Inspired Product Experience

Status: Main scope completed.

Deliverables:

- Implement a kanban board that displays issues grouped by WorkflowState.
- Use dnd-kit to support dragging issues between states.
- Use TanStack Query optimistic updates with rollback on failure.
- Implement sidebar navigation with workspace, projects, and views.
- Implement quick issue creation.
- Use shadcn/ui components consistently.

Core APIs:

- `GET /api/issues/board`
- `PATCH /api/issues/{id}/state`
- `PATCH /api/issues/reorder`

Acceptance criteria:

- Dragging an issue updates status immediately and persists.
- Board order remains stable after refresh.
- Failed API requests restore the previous frontend state.
- The first screen feels like a real workbench, not a landing page.

### Phase 4: AI and Analytics

Status: In progress. Analytics and the Spring AI Copilot are complete; the LangGraph Agent remains pending.

Deliverables:

- Integrate Spring AI with a configurable provider.
- Implement structured AI issue breakdown with persisted review drafts and transactional, idempotent Apply.
- Implement Issue Summary and Project Summary with bounded context and server-owned source statistics.
- Implement analytics overview: total issues, completion rate, status distribution, assignee distribution, and completion trend.
- Render the analytics overview, distributions, and completion trend in the project UI.
- Keep AI suggestions as creator-scoped drafts with Get, Dismiss, lazy expiry, and URL restoration.
- Share a user/workspace generation limit across Copilot features and expose low-cardinality request, duration, token, suggestion, and Apply metrics.
- Keep a real OpenAI smoke integration test opt-in and outside normal PR execution.

Core APIs:

- `GET /api/ai/status`
- `POST /api/ai/issues/{issueId}/breakdown`
- `POST /api/ai/issues/{issueId}/summary`
- `POST /api/ai/projects/{projectId}/summary`
- `GET /api/ai/suggestions/{suggestionId}`
- `POST /api/ai/suggestions/{suggestionId}/dismiss`
- `POST /api/ai/suggestions/{suggestionId}/apply`
- `GET /api/analytics/overview`

Acceptance criteria:

- AI-generated content does not automatically write official issue data.
- AI features can be disabled clearly when no provider key is configured.
- Analytics displays real issue data.

### Phase 5: Testing, Deployment, and Application Materials

Status: Planned.

Deliverables:

- Add backend service, controller, and repository integration tests.
- Use Testcontainers for PostgreSQL + Flyway + JPA integration tests.
- Complete frontend build, lint, and validation checks.
- Make Docker Compose support one-command startup for the complete application.
- Complete README screenshots, architecture diagram, database design, API examples, test commands, and deployment instructions.
- Prepare resume bullet points, interview talking points, and a demo checklist.

Acceptance criteria:

- Backend tests pass.
- Frontend build and lint pass.
- A new machine can start the project by following the README.
- Resume bullet points clearly demonstrate Spring Boot, JWT, workspace authorization, PostgreSQL, Flyway, React, drag and drop, AI, testing, and Docker.

## Public Data Types

Implemented now:

- `User`: account identity.
- `Workspace`: collaboration boundary.
- `WorkspaceMembership`: user's role inside a workspace.
- `RefreshToken`: rotated long-lived token for session continuity.

Planned next:

- `Project`: project inside a workspace.
- `ProjectMember` or `ProjectInvitation`: project-level collaboration.
- `WorkflowState`: issue status column, such as Backlog, Todo, In Progress, Done.
- `Issue`: core work item.
- `Comment`: issue discussion.
- `ActivityEvent`: issue change history.
- `AnalyticsOverview`: analytics page data.
- `AiSuggestion`: draft suggestions returned by AI.

## API Conventions

- Authenticated requests use `Authorization: Bearer <token>`.
- The frontend stores access and refresh tokens locally for the MVP.
- Access tokens are short lived.
- Refresh tokens are rotated on `/api/auth/refresh`.
- Business APIs derive current user and workspace from JWT context.
- The frontend should not directly provide privileged `workspaceId` values to bypass authorization.

## Test Plan

Backend tests:

- Auth: registration, login, token refresh, invalid token, expired token.
- Workspace access: current workspace, current members, inactive memberships.
- Security: role coverage for `OWNER`, `ADMIN`, `MEMBER`, and `GUEST`.
- Data isolation: users cannot read or write projects and issues outside their allowed workspace/project.
- Flyway: a fresh PostgreSQL container can run all migrations and start JPA successfully.
- AI: provider configured, provider missing, and graceful degradation.

Frontend checks:

- Registration redirects into `/app`.
- Login redirects into `/app`.
- Unauthenticated access to `/app` redirects to `/login`.
- Protected requests include `Authorization`.
- Expired access token triggers refresh and retries the original request.
- Refresh failure signs the user out.
- Production build has no TypeScript errors.
- Main pages do not overflow on common desktop and mobile widths.

## Assumptions

- Engineering completeness is prioritized over flashy UI because the target audience is internship hiring teams.
- The backend remains a modular monolith.
- Real-time collaboration, email notifications, file attachments, webhooks, mobile apps, microservices, and complex roadmap or cycle features are out of scope for the MVP.
