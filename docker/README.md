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

## Standard build (no GPU)

Uses `Dockerfile` — a plain JRE image with no CUDA dependency. Suitable for local development and CPU-only deployments.

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

## NVIDIA GPU build

Uses `Dockerfile.nvidia` — a two-stage build that layers the JRE onto a CUDA runtime base image. Requires an NVIDIA GPU and the [NVIDIA Container Toolkit](https://docs.nvidia.com/datacenter/cloud-native/container-toolkit/install-guide.html) installed on the host.

The `docker-compose.nvidia-gpu.yml` overlay adds the GPU device reservation and switches the build to `Dockerfile.nvidia`.

**Build and start:**
```bash
docker compose -f docker/docker-compose.yml -f docker/docker-compose.nvidia-gpu.yml up --build -d
```

**Start without rebuilding:**
```bash
docker compose -f docker/docker-compose.yml -f docker/docker-compose.nvidia-gpu.yml up -d
```

**Stop:**
```bash
docker compose -f docker/docker-compose.yml -f docker/docker-compose.nvidia-gpu.yml down
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
