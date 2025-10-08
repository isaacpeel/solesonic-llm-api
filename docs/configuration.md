# Configuration Guide

This document serves as the single source of truth for all environment variables used in the Solesonic LLM API. All configuration is externalized through environment variables that can be set in a `.env` file or through your deployment environment.

## Environment Variables by Domain

### Application Configuration

| Variable | Description | Example | Required | Notes |
|----------|-------------|---------|----------|--------|
| `BASE_URI` | Base context path for the application | `api` | No | Sets server.servlet.context-path |
| `BOT_NAME` | Bot name identifier | `solesonic-llm-api` | No | Default: solesonic-llm-api |

### Database Configuration

| Variable | Description | Example | Required | Notes |
|----------|-------------|---------|----------|--------|
| `DB_URL` | PostgreSQL connection URI | `jdbc:postgresql://localhost:5445/solesonic-llm-api` | Yes | Must include pgvector-enabled database |
| `POSTGRES_USER` | Database username | `solesonic-llm-api` | Yes | User must have full permissions |
| `DB_PASSWORD` | Database password | `docker_pw` | Yes | Used by both Spring Boot and Docker Compose |

### Security/JWT Configuration

| Variable | Description | Example                                     | Required | Notes |
|----------|-------------|---------------------------------------------|----------|--------|
| `ISSUER_URI` | OAuth2/JWT token issuer URI | `https://your-issuer`                       | Yes | OAuth2 provider |
| `JWK_SET_URI` | JSON Web Key Set URI | `https://your-issuer/.well-known/jwks.json` | Yes | For JWT token validation |

### Atlassian Integration

| Variable | Description | Example | Required | Notes |
|----------|-------------|---------|----------|--------|
| `ATLASSIAN_OAUTH_CLIENT_ID` | Atlassian OAuth2 client ID | `your_atlassian_client_id` | No | Required for Jira/Confluence integration |
| `ATLASSIAN_OAUTH_CLIENT_SECRET` | Atlassian OAuth2 client secret | `your_atlassian_client_secret` | No | Keep secure; required with client ID |
| `ATLASSIAN_OAUTH_TOKEN_URI` | Atlassian token endpoint | `https://auth.atlassian.com/oauth/token` | No | Standard Atlassian OAuth2 endpoint |
| `JIRA_CLOUD_ID_PATH` | Jira cloud ID path for API access | `/your-cloud-id` | No | Required for Jira API calls |
| `CALLBACK_HOST` | OAuth callback host URL | `https://yourdomain.com/settings` | No | Required for production OAuth flows |
| `ATLASSIAN_TOKENS_SECRETS_PREFIX` | AWS Secrets Manager prefix for tokens | `/solesonic/atlassian/tokens` | No | Default: /solesonic/atlassian/tokens |
| `ATLASSIAN_TOKENS_ADMIN_KEY` | Admin key for token management | `your_admin_key` | No | Required for token storage operations |

### AWS Configuration

| Variable | Description | Example | Required | Notes |
|----------|-------------|---------|----------|--------|
| `AWS_KMS_KEY_ID` | AWS KMS key ID for encryption | `arn:aws:kms:us-east-1:123456789012:key/...` | No | Optional for enhanced security |

### Ollama Configuration

| Variable | Description | Example | Required | Notes |
|----------|-------------|---------|----------|--------|
| `OLLAMA_HOST` | Ollama server host | `localhost` | No | Required for production; default localhost for local |

### MCP (Model Context Protocol) Configuration

| Variable | Description | Example | Required | Notes |
|----------|-------------|---------|----------|--------|
| `SOLESONIC_MCP_URI` | MCP server connection URL | `http://localhost:3001/sse` | No | Required for MCP server integration |
| `MCP_CLIENT_ID` | MCP OAuth2 client ID | `your_mcp_client_id` | No | Required for MCP OAuth2 authentication |
| `MCP_CLIENT_SECRET` | MCP OAuth2 client secret | `your_mcp_client_secret` | No | Required for MCP OAuth2 authentication |

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

# Security/JWT Configuration
ISSUER_URI=https://your-issuer
JWK_SET_URI=https://your-issuer/.well-known/jwks.json

# Atlassian Integration
ATLASSIAN_OAUTH_CLIENT_ID=your_atlassian_client_id
ATLASSIAN_OAUTH_CLIENT_SECRET=your_atlassian_client_secret
ATLASSIAN_OAUTH_TOKEN_URI=https://auth.atlassian.com/oauth/token
JIRA_CLOUD_ID_PATH=/your-cloud-id
CALLBACK_HOST=https://yourdomain.com/settings
ATLASSIAN_TOKENS_SECRETS_PREFIX=/solesonic/atlassian/tokens
ATLASSIAN_TOKENS_ADMIN_KEY=your_admin_key

# MCP Configuration
SOLESONIC_MCP_URI=http://localhost:3001/sse
MCP_CLIENT_ID=your_mcp_client_id
MCP_CLIENT_SECRET=your_mcp_client_secret

# Ollama Configuration
OLLAMA_HOST=localhost

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

- **local**: Relaxed security, suitable for development
- **prod**: Full security enabled, requires all JWT/OAuth2 variables
- **test**: Minimal configuration for automated testing

## Troubleshooting

### Common Configuration Issues

1. **Database connection failures**: Verify `DB_URL` and `POSTGRES_USER` variables match your database setup
2. **Authentication errors**: Ensure `ISSUER_URI` and `JWK_SET_URI` are correctly set and accessible
3. **CORS errors**: Add your frontend URL to `CORS_ALLOWED_ORIGINS`
4. **MCP integration issues**: Verify `SOLESONIC_MCP_URI`, `MCP_CLIENT_ID` and `MCP_CLIENT_SECRET` are properly configured

For more troubleshooting guidance, see [docs/troubleshooting.md](troubleshooting.md).