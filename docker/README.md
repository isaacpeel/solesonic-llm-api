# Docker

## Prerequisites

### External network

All compose files share a common bridge network that must exist before starting any services:

```bash
docker network create solesonic-network
```

### Environment file

Copy the example env file and fill in your values:

```bash
cp docker/.env.example docker/.env
```

All commands below assume you are running from the **project root**.

---

## Full stack (API, Redis, Postgres)

Uses `Dockerfile` — a plain JRE image. The API needs no GPU of its own: every model call (chat, embedding, ETL, vision, image generation) goes out over HTTP to Ollama or the MCP image server, which run outside this compose file.

**Build and start:**
```bash
docker compose -f docker/docker-compose.yml up --build -d
```

**Start without rebuilding:**
```bash
docker compose -f docker/docker-compose.yml up -d
```

**Stop:**
```bash
docker compose -f docker/docker-compose.yml down
```

---

## Database only

Starts only the PostgreSQL (pgvector) container. Useful when running the API locally outside Docker (e.g. from the IDE) but still wanting a managed database.

```bash
docker compose -f docker/docker-compose-db.yml up -d
```

---

## Logs

```bash
docker logs solesonic-llm-api -f
```
