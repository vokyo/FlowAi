# Contributing to FlowAI

## Where does my code go?

Most review comments about structure come from this question being unwritten, so
here it is. The rules below are enforced by tooling, not etiquette — see
[Enforcement](#enforcement).

### Frontend (`frontend/src`)

| Directory | Holds | May import from |
|---|---|---|
| `features/<name>/` | One user-facing capability: its components, hooks, and stylesheet | `ui`, `domain`, `api`, `routing`, `lib`, `auth`, and **itself only** |
| `features/project-shell/` | The composition root — wires features into routes | anything, including other features |
| `ui/` | Presentational components shared by more than one feature | `domain`, `lib` |
| `domain/` | Shared types and pure logic. No React, no HTTP | `lib` |
| `api/` | HTTP transport. Every `*-api.ts` lives here, no exceptions | `domain`, `lib` |
| `routing/` | Route construction and parsing helpers | `domain` |
| `auth/` | Session and token infrastructure. No UI | `api`, `lib` |
| `pages/` | Route-level page components | anything |
| `lib/` | Framework-level helpers. `utils.ts` is pinned by shadcn — do not rename | — |
| `components/ui/` | shadcn-generated. Owned by the generator; do not hand-edit names | — |

**A feature may not import from a sibling feature.** If two features need the same
thing, it moves to `ui/`, `domain/`, `api/`, or `routing/`. There is no
`src/<domain>/` tier — features live in `features/`.

There is one declared exception, listed in `eslint.config.js` under
`ALLOWED_CROSS_FEATURE`: `issue-list` lazy-loads `board` because the list/board
view toggle currently lives inside `IssueListFeature`. Adding to that map requires
a reason in the comment next to it.

### Naming

| Kind | Convention | Example |
|---|---|---|
| Component file | `PascalCase.tsx` | `BoardFeature.tsx` |
| Multi-component UI module | `kebab-case.tsx` | `ui/feature-ui.tsx` |
| Hook | `useThing.ts` | `useBoardQueries.ts` |
| Any other module | `kebab-case.ts` | `work-api.ts` |
| Stylesheet | `kebab-case.css`, next to what it styles | `features/board/board.css` |
| Test | `<subject>.test.ts(x)`, next to its subject | `board-utils.test.ts` |
| Directory | `kebab-case` | `features/issue-detail/` |

A test file must sit next to a module of the same name. `foo.test.ts` with no
`foo.ts` fails the Vitest config before any test runs.

### Backend (`backend/src`)

Packages are named for the domain they serve (`issue`, `workspace`, `ai/summary`),
never for a technical layer. There is no `config` package: a `@Configuration` class
lives beside the domain it configures and is named `*Configuration`, not `*Config`.

Test packages mirror main packages. The two exceptions are deliberate:

- `com.vokyo.backend.integration` holds every `*IntegrationTests` class plus the
  Testcontainers fixtures. Failsafe selects it by the `**/integration/*Tests.java`
  glob; Surefire excludes it.
- `com.vokyo.backend.architecture` holds the structure rules themselves.

Surefire and Failsafe both pin their `<includes>` explicitly. Do not remove those
— the Maven defaults also match `**/Test*.java`, which sweeps up fixtures like
`TestBackendApplication` and tries to run them as tests.

## Enforcement

| Rule | Enforced by | Fails at |
|---|---|---|
| Filename and directory casing | `eslint-plugin-check-file` | `npm run lint` |
| Feature boundaries, no import cycles | `eslint-plugin-import-x` | `npm run lint` |
| Test file has a subject module | `vitest.config.ts` | `npm test` |
| Backend package structure, `*Configuration` suffix | `PackageStructureTests` (ArchUnit) | `./mvnw test` |
| Whitespace, line endings, final newline | `.editorconfig` | editor |

All of these run in CI on every pull request.

## Running the stack

```bash
cd frontend && npm ci && npm run dev     # frontend on :5173, proxies /api to :8080
cd backend && ./mvnw spring-boot:run     # backend on :8080
```

Tests:

```bash
cd frontend && npm run lint && npm test && npm run build
```

```bash
cd backend && ./mvnw test && ./mvnw -Pintegration verify
```

End-to-end (boots both servers itself, needs Docker for Testcontainers):

```bash
cd frontend && npm run test:e2e
```
