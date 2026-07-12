# Containerized Environment

## Architecture

Browser -> Nginx frontend -> Spring Boot backend -> PostgreSQL

## Prerequisites

- Docker
- Docker Compose

## Environment Setup

Copy `.env.example` to `.env` and configure local secrets.

## Build and Start

docker compose up --build -d

## Verify Services

docker compose ps
curl http://localhost:8080/health
docker compose exec backend curl http://localhost:8080/actuator/health

## View Logs

docker compose logs -f
docker compose logs -f backend
docker compose logs -f frontend
docker compose logs -f postgres

## Stop Services

docker compose down

## Data Persistence

PostgreSQL data is stored in the `flowai_postgres_data` named volume.
Running `docker compose down` preserves data.

## Reset Local Data

Warning: the following command permanently deletes local database data.

docker compose down -v

## Rebuild

docker compose up --build -d

## Troubleshooting

- Port 8080 already in use
- Backend datasource authentication failure
- Nginx returns 502
- Container remains unhealthy