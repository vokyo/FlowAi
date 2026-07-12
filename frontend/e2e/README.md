# Frontend tests

Run fast unit and component tests:

```bash
npm test
```

Install the Playwright browser once:

```bash
npx playwright install chromium
```

Run end-to-end tests:

```bash
npm run test:e2e
```

The Playwright configuration starts an isolated Spring Boot test application on
port `18080`, backed by a temporary Testcontainers PostgreSQL database. It also
starts Vite on port `4173`. Docker Desktop must be running; the development
database and the normal `5173`/`8080` services are not reused.
