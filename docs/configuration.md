# Configuration Guide

This document serves as the single source of truth for all environment variables used in the Solesonic LLM API. All configuration is externalized through environment variables that can be set in a `.env` file or through your deployment environment.

## Environment Variables by Domain

### Application Configuration

| Variable | Description | Example | Required | Notes |
|----------|-------------|---------|----------|--------|
| `BASE_URI` | Base context path for the application | `api` | No | Sets server.servlet.context-path |
| `BOT_NAME` | Bot name identifier | `solesonic-llm-api` | No | Default: solesonic-llm-api |
| `SOLESONIC_ELICITATION_TIMEOUT_SECONDS` | Max seconds to wait for elicitation response | `600` | No | Maps to `solesonic.elicitation.timeout-seconds`; default 600 |

### Database Configuration

| Variable | Description | Example | Required | Notes |
|----------|-------------|---------|----------|--------|
| `DB_URL` | PostgreSQL connection URL | `jdbc:postgresql://localhost:5445/solesonic-llm-api` | Yes | Must include pgvector-enabled database |
| `POSTGRES_USER` | Database username | `solesonic-llm-api` | Yes | User must have full permissions |
| `DB_PASSWORD` | Database password | `docker_pw` | Yes | Used by both Spring Boot and Docker Compose |

### Security/JWT Configuration

| Variable | Description | Example | Required | Notes |
|----------|-------------|---------|----------|--------|
| `ISSUER_URI` | OAuth2/JWT token issuer URI | `https://your-issuer` | Yes (prod) | OAuth2 provider |
| `JWK_SET_URI` | JSON Web Key Set URI | `https://your-issuer/.well-known/jwks.json` | Yes (prod) | For JWT token validation |

### Encryption Configuration

| Variable | Description | Example | Required | Notes |
|----------|-------------|---------|----------|--------|
| `ENCRYPTION_PASSWORD` | Password used for encrypting stored tokens | `your-strong-password` | Yes | Used to encrypt Atlassian refresh tokens at rest |
| `ENCRYPTION_SALT` | Salt used for encryption key derivation | `your-salt-value` | Yes | Must be consistent across restarts |

### Redis Configuration

Redis is required for streaming chat (Redis Streams) and for caching (Ollama models, slash commands). In the local profile, it defaults to `localhost:6379` without authentication. In production, set the following variables.

| Variable | Description | Example | Required | Notes |
|----------|-------------|---------|----------|--------|
| `REDDIS_HOST` | Redis server hostname | `redis.internal` | Yes (prod) | Note: the env var name has a double-D typo inherited from early configuration; it must be spelled `REDDIS_HOST` |
| `REDIS_PASSWORD` | Redis password | `your-redis-password` | No | Leave unset if Redis has no authentication |

### Atlassian Integration

| Variable | Description | Example | Required | Notes |
|----------|-------------|---------|----------|--------|
| `ATLASSIAN_OAUTH_CLIENT_ID` | Atlassian OAuth2 client ID | `your_atlassian_client_id` | No | Required for Jira/Confluence integration |
| `ATLASSIAN_OAUTH_CLIENT_SECRET` | Atlassian OAuth2 client secret | `your_atlassian_client_secret` | No | Keep secure; required with client ID |
| `ATLASSIAN_OAUTH_TOKEN_URI` | Atlassian token endpoint | `https://auth.atlassian.com/oauth/token` | No | Standard Atlassian OAuth2 endpoint |
| `JIRA_CLOUD_ID_PATH` | Jira cloud ID path for API access | `/your-cloud-id` | No | Required for Jira API calls |
| `CALLBACK_HOST` | OAuth callback host URL | `https://yourdomain.com/settings` | No | Required for production OAuth flows |
| `ATLASSIAN_TOKENS_ADMIN_KEY` | Admin user ID for service account token operations | `your_admin_key` | No | Required for token storage operations |

### AWS Configuration

| Variable | Description | Example | Required | Notes |
|----------|-------------|---------|----------|--------|
| `AWS_KMS_KEY_ID` | AWS KMS key ID for encryption | `arn:aws:kms:us-east-1:123456789012:key/...` | No | Optional for enhanced security |

### Ollama Configuration

| Variable | Description | Example | Required | Notes |
|----------|-------------|---------|----------|--------|
| `OLLAMA_HOST` | Ollama server hostname | `localhost` | No | Default localhost for local profile; required for production |

### Ollama Model Cache Configuration

The Ollama model cache stores model details and show-model responses in Redis to avoid redundant calls to the Ollama API. A background task keeps the cache warm by refreshing all installed models on a fixed interval.

| Variable | Description | Example | Required | Notes |
|----------|-------------|---------|----------|--------|
| `SOLESONIC_LLM_OLLAMA_CACHE_TTL_SECONDS` | TTL for cached model entries | `120` | No | Default: 120 seconds |
| `SOLESONIC_LLM_OLLAMA_CACHE_REFRESH_ENABLED` | Enable the background cache refresh task | `true` | No | Default: true; set to `false` to disable |
| `SOLESONIC_LLM_OLLAMA_CACHE_REFRESH_SECONDS` | Interval between background refresh runs | `60` | No | Default: 60 seconds |

### Slash Commands Cache Configuration

Slash commands are loaded from the MCP tool catalog and cached in Redis with type-ahead search support.

| Variable | Description | Example | Required | Notes |
|----------|-------------|---------|----------|--------|
| `SOLESONIC_LLM_SLASH_COMMANDS_CACHE_TTL_SECONDS` | TTL for the slash commands cache | `3600` | No | Default: 3600 seconds (1 hour) |
| `SOLESONIC_LLM_SLASH_COMMANDS_CACHE_WARMUP_ON_STARTUP` | Warm the cache on application startup | `true` | No | Default: true |

### MCP (Model Context Protocol) Configuration

| Variable | Description | Example | Required | Notes |
|----------|-------------|---------|----------|--------|
| `SOLESONIC_MCP_URI` | MCP server connection URL | `http://localhost:3001/sse` | No | Required for MCP server integration |
| `MCP_CLIENT_ID` | OAuth2 client ID for MCP authentication | `your_mcp_client_id` | No | Required for MCP OAuth2 authentication |
| `MCP_CLIENT_SECRET` | OAuth2 client secret for MCP authentication | `your_mcp_client_secret` | No | Required for MCP OAuth2 authentication |
| `MCP_ISSUER_URI` | OAuth2 issuer URI for the MCP auth server | `https://your-auth-server` | No | Required for MCP client credentials flow |
| `TOKEN_ENDPOINT` | Token exchange endpoint URL | `https://your-auth-server/token` | No | Used for MCP token exchange |

### CORS Configuration

| Variable | Description | Example | Required | Notes |
|----------|-------------|---------|----------|--------|
| `CORS_ALLOWED_ORIGINS` | Comma-separated allowed origins | `http://localhost:3000,https://yourdomain.com` | No | Default: http://localhost:3000 for local profile |

## Sample Configuration Files

### Minimal Configuration (.env)

This is the minimum configuration needed to run the application locally:

```bash
# Database (required)
DB_URL=jdbc:postgresql://localhost:5445/solesonic-llm-api
POSTGRES_USER=solesonic-llm-api
DB_PASSWORD=docker_pw

# Encryption (required)
ENCRYPTION_PASSWORD=your-strong-password
ENCRYPTION_SALT=your-salt-value

# Security (required for production)
ISSUER_URI=https://your-issuer
JWK_SET_URI=https://your-issuer/.well-known/jwks.json

# CORS (adjust for your frontend)
CORS_ALLOWED_ORIGINS=http://localhost:3000
```

### Full Configuration (.env)

Complete configuration with all optional features enabled:

```bash
# Application Configuration
BASE_URI=api
BOT_NAME=solesonic-llm-api

# Database Configuration
DB_URL=jdbc:postgresql://localhost:5445/solesonic-llm-api
POSTGRES_USER=solesonic-llm-api
DB_PASSWORD=docker_pw

# Encryption Configuration
ENCRYPTION_PASSWORD=your-strong-password
ENCRYPTION_SALT=your-salt-value

# Security/JWT Configuration
ISSUER_URI=https://your-issuer
JWK_SET_URI=https://your-issuer/.well-known/jwks.json

# Redis Configuration (production; local profile uses localhost:6379 by default)
REDDIS_HOST=redis.internal
REDIS_PASSWORD=your-redis-password

# Atlassian Integration
ATLASSIAN_OAUTH_CLIENT_ID=your_atlassian_client_id
ATLASSIAN_OAUTH_CLIENT_SECRET=your_atlassian_client_secret
ATLASSIAN_OAUTH_TOKEN_URI=https://auth.atlassian.com/oauth/token
JIRA_CLOUD_ID_PATH=/your-cloud-id
CALLBACK_HOST=https://yourdomain.com/settings
ATLASSIAN_TOKENS_ADMIN_KEY=your_admin_key

# MCP Configuration
SOLESONIC_MCP_URI=http://localhost:3001/sse
MCP_CLIENT_ID=your_mcp_client_id
MCP_CLIENT_SECRET=your_mcp_client_secret
MCP_ISSUER_URI=https://your-auth-server
TOKEN_ENDPOINT=https://your-auth-server/token

# Ollama Configuration
OLLAMA_HOST=localhost

# Ollama Model Cache (optional overrides)
SOLESONIC_LLM_OLLAMA_CACHE_TTL_SECONDS=120
SOLESONIC_LLM_OLLAMA_CACHE_REFRESH_ENABLED=true
SOLESONIC_LLM_OLLAMA_CACHE_REFRESH_SECONDS=60

# AWS Configuration (optional)
AWS_KMS_KEY_ID=arn:aws:kms:us-east-1:123456789012:key/your-key-id

# CORS Configuration
CORS_ALLOWED_ORIGINS=http://localhost:3000,https://yourdomain.com
```

## Security Considerations

- **Never commit `.env` files to version control**
- Store secrets securely in production (AWS Secrets Manager, Kubernetes secrets, etc.)
- Use different credentials for different environments
- Regularly rotate OAuth2 client secrets
- Ensure database passwords are strong and unique

## Profile-Specific Behavior

The application supports different profiles with varying configuration requirements:

- **local**: Relaxed security, suitable for development. Redis defaults to `localhost:6379` without a password.
- **prod**: Full security enabled, requires all JWT/OAuth2 variables
- **test**: Minimal configuration for automated testing

## Troubleshooting

### Common Configuration Issues

1. **Database connection failures**: Verify `DB_URL` and `POSTGRES_USER` variables match your database setup
2. **Authentication errors**: Ensure `ISSUER_URI` and `JWK_SET_URI` are correctly set and accessible
3. **CORS errors**: Add your frontend URL to `CORS_ALLOWED_ORIGINS`
4. **Redis connection failures**: Verify Redis is running and that `REDDIS_HOST` (note the double-D) is set correctly for non-local environments
5. **MCP integration issues**: Verify `SOLESONIC_MCP_URI`, `MCP_CLIENT_ID`, `MCP_CLIENT_SECRET`, and `MCP_ISSUER_URI` are all configured

For more troubleshooting guidance, see [docs/troubleshooting.md](troubleshooting.md).
