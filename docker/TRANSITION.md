# Production Transition

## 1. Update `docker/.env`

Add the following variables:

```dotenv
REDIS_PASSWORD=<strong-password>
SPRING_PROFILES_ACTIVE=prod-nginx
SPRING_DATA_REDIS_PASSWORD=<same value as REDIS_PASSWORD>
```

`SPRING_PROFILES_ACTIVE` is required — the profile is no longer hard-coded in the Dockerfile.

## 2. Create the external network

```bash
docker network create solesonic-network
```

## 3. Back up Postgres

```bash
docker exec solesonic-llm-api-db \
  pg_dump -U "${POSTGRES_USER}" "${POSTGRES_DB}" \
  > ~/backup-$(date +%Y%m%d-%H%M%S).sql
```

## 4. Verify the Postgres volume name

```bash
docker volume ls | grep postgres
```

The new compose expects `solesonic-llm-api_postgres_data`. If the existing volume has this name, data carries over automatically and no further action is needed. If the name differs, copy it:

```bash
docker volume create solesonic-llm-api_postgres_data

docker run --rm \
  -v <old-volume-name>:/source:ro \
  -v solesonic-llm-api_postgres_data:/target \
  alpine sh -c "cp -av /source/. /target/"
```

## 5. Stop the old stacks

```bash
docker compose -f docker/docker-compose.yml down
docker compose -f docker/docker-compose-db.yml down
docker stop solesonic-llm-api-redis && docker rm solesonic-llm-api-redis
```

## 6. Deploy

```bash
docker compose -f docker/docker-compose.yml up --build -d
```

## 7. Verify

```bash
# All three containers should reach (healthy)
docker ps

# Confirm prod-nginx profile is active
curl -s http://localhost:8080/actuator/env | jq '.activeProfiles'

# Confirm database data is intact
docker exec solesonic-llm-api-db \
  psql -U "${POSTGRES_USER}" -d "${POSTGRES_DB}" -c "SELECT COUNT(*) FROM vector_store;"
```
