# Getting Started

This guide will walk you through setting up and running the Solesonic LLM API for the first time.

## Prerequisites

Before you begin, ensure you have the following installed:

- **Java 24** - The application requires Java 24 or later
- **Docker and Docker Compose** - For running the PostgreSQL database with pgvector
- **Ollama** - For LLM chat and embedding models
- **Git** - To clone the repository (if applicable)

### Installing Prerequisites

#### Java 24
Download and install Java 24 from [Oracle](https://www.oracle.com/java/technologies/downloads/) or use your preferred JDK distribution.

Verify installation:
```bash
java -version
```

#### Docker
Install Docker Desktop from [docker.com](https://www.docker.com/products/docker-desktop/) or use your platform's package manager.

Verify installation:
```bash
docker --version
docker-compose --version
```

#### Ollama
Install Ollama from [ollama.ai](https://ollama.ai/) and ensure it's running locally.

Verify installation:
```bash
ollama --version
```

The application expects Ollama to be available at `http://localhost:11434`.

## Initial Setup

### 1. Start the Database

Start the PostgreSQL database with pgvector extension:

```bash
docker compose -f docker/docker-compose-db.yml up -d
```

This command will:
- Create a PostgreSQL container with pgvector extension
- Set up the database on port 5445
- Initialize required extensions (uuid-ossp, vector, hstore)
- Create the application database and user

**Verify the database is running:**
```bash
docker compose -f docker/docker-compose-db.yml ps
```

You should see the postgres service running.

### 2. Configure Environment Variables

Create a `.env` file in the project root directory with the minimum required configuration:

```bash
# Database (required)
SPRING_DATASOURCE_URI=jdbc:postgresql://localhost:5445/solesonic-llm-api
SPRING_DATASOURCE_USERNAME=solesonic-llm-api
SPRING_DATASOURCE_PASSWORD=docker_pw
DB_PASSWORD=docker_pw

# Security (required for production, optional for local development)
ISSUER_URI=https://your-issuer
JWK_SET_URI=https://your-issuer/.well-known/jwks.json

# Application
APPLICATION_NAME=solesonic-llm-api

# CORS (adjust for your frontend)
CORS_ALLOWED_ORIGINS=http://localhost:3000
```

**Important:** The database password must be defined in the `.env` file as shown above.

For a complete list of all available configuration options, see [docs/configuration.md](configuration.md).

### 3. Start Ollama Models

Ensure Ollama is running and pull the required models:

```bash
# Chat model (default for local development)
ollama pull qwen2.5:7b

# Embedding model (default for local development)
ollama pull twine/mxbai-embed-xsmall-v1:latest
```

### 4. Build and Run the Application

#### Using Maven (Recommended)

```bash
# Build the application
./mvnw clean verify

# Run with local profile
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

#### Using an IDE

1. Import the project into your IDE (IntelliJ IDEA, Eclipse, etc.)
2. Set the active profile to `local`
3. Run the main class: `com.solesonic.llmapi.SolesonicLlmApiApplication`

## Verification

### 1. Check Application Status

The application should start successfully and be available at:
- **Base URL**: `http://localhost:8080`
- **Context Path**: `http://localhost:8080/izzybot` (if BASE_URI environment variable is set)

### 2. Health Check

Check if the application is healthy:

```bash
curl http://localhost:8080/actuator/health
```

Expected response:
```json
{
  "status": "UP"
}
```

### 3. Test Database Connection

The application will automatically run Flyway migrations on startup. Check the logs for successful migration messages:

```
INFO  - Flyway Community Edition x.x.x by Redgate
INFO  - Database: jdbc:postgresql://localhost:5445/solesonic-llm-api
INFO  - Successfully applied x migrations to schema "public"
```

### 4. Test LLM Integration

You can test the chat functionality using the API endpoints (see [docs/api.md](api.md) for detailed endpoint documentation).

## Port Information

The application uses the following ports by default:

| Service | Port | Description |
|---------|------|-------------|
| Application | 8080 | Main application (local profile) |
| Application | 8443 | Main application (prod profile with TLS) |
| PostgreSQL | 5445 | Database with pgvector |
| Ollama | 11434 | LLM service |

## Troubleshooting

### Common Issues

#### Port Conflicts
If you encounter port conflicts:

1. **Database port 5445 in use:**
   ```bash
   # Check what's using the port
   lsof -i :5445
   # Stop conflicting services or change the port in docker-compose-db.yml
   ```

2. **Application port 8080 in use:**
   ```bash
   # Use a different port
   ./mvnw spring-boot:run -Dspring-boot.run.profiles=local -Dserver.port=8081
   ```

#### Database Connection Issues

1. **Connection refused:**
   - Verify the database container is running: `docker compose -f docker/docker-compose-db.yml ps`
   - Check the database logs: `docker compose -f docker/docker-compose-db.yml logs postgres`

2. **Authentication failed:**
   - Verify the `.env` file contains the correct database credentials
   - Ensure `SPRING_DATASOURCE_PASSWORD` and `DB_PASSWORD` match

#### Missing Environment Variables

If you see errors about missing environment variables:

1. Verify your `.env` file is in the project root directory
2. Check that the variable names match exactly (case-sensitive)
3. For security variables (`ISSUER_URI`, `JWK_SET_URI`), these are optional for local development

#### Ollama Connection Issues

1. **Ollama not responding:**
   ```bash
   # Check if Ollama is running
   ollama list
   
   # Start Ollama service if needed
   ollama serve
   ```

2. **Models not found:**
   ```bash
   # Pull required models
   ollama pull qwen2.5:7b
   ollama pull twine/mxbai-embed-xsmall-v1:latest
   ```

#### Java Version Issues

Ensure you're using Java 24:
```bash
java -version
# Should show version 24.x.x

# If using JAVA_HOME
echo $JAVA_HOME
```

### Getting Help

If you continue to experience issues:

1. Check the application logs for detailed error messages
2. Verify all prerequisites are correctly installed
3. Review the [docs/configuration.md](configuration.md) for configuration details
4. See [docs/troubleshooting.md](troubleshooting.md) for advanced troubleshooting

## Next Steps

Once you have the application running:

1. **Explore the API**: See [docs/api.md](api.md) for endpoint documentation
2. **Configure integrations**: Set up Atlassian or MCP integrations using [docs/configuration.md](configuration.md)
3. **Production deployment**: Review [docs/deployment.md](deployment.md) for production setup
4. **Security setup**: Configure OAuth2/JWT following [docs/security.md](security.md)

For development contributions, see [docs/contributing.md](contributing.md).