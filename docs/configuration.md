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
| `ENCRYPTION_PASSWORD` | Password used for encrypting stored tokens | `your-strong-password` | Yes | Used to encrypt Atlassian and Google refresh tokens at rest |
| `ENCRYPTION_SALT` | Salt used for encryption key derivation | `your-salt-value` | Yes | Must be consistent across restarts |

### Redis Configuration

Redis is required for streaming chat (Redis Streams) and for caching (Ollama models, slash commands). In the local profile, it defaults to `localhost:6379` without authentication. In production, set the following variables.

| Variable | Description | Example | Required | Notes |
|----------|-------------|---------|----------|--------|
| `REDIS_HOST` | Redis server hostname | `redis.internal` | Yes (prod) | Note: the env var name has a double-D typo inherited from early configuration; it must be spelled `REDIS_HOST` |
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

### Google Integration

Three-legged OAuth2 against Google, scoped to Gmail. The user consents once; the refresh token is
encrypted into `user_preferences.google_access_token` and never leaves the application. MCP servers
acting on the user's behalf ask the broker (`POST /broker/google/token`) for a short-lived access
token instead — see [docs/mcp-integration.md](mcp-integration.md).

| Variable | Description | Example | Required | Notes |
|----------|-------------|---------|----------|--------|
| `GOOGLE_OAUTH_CLIENT_ID` | OAuth2 client ID of the Google Cloud web application client | `761157506466-....apps.googleusercontent.com` | Yes | From the client secret JSON downloaded in the Google Cloud console |
| `GOOGLE_OAUTH_CLIENT_SECRET` | OAuth2 client secret paired with the client ID | `GOCSPX-...` | Yes | Keep secure; never commit the downloaded client secret JSON |
| `GOOGLE_AUTH_CALLBACK_URI` | Redirect URI for the `test` profile | `http://localhost:3000/google/auth/callback` | Yes (test) | Must match a redirect URI registered on the OAuth client **exactly** |
| `GOOGLE_CALLBACK_HOST` | Redirect URI for the `prod` and `prod-nginx` profiles | `https://yourdomain.com/google/auth/callback` | Yes (prod) | Google requires HTTPS for anything other than localhost |

Fixed in `application.properties` rather than exposed as variables, since they are Google's own
endpoints and identical in every deployment:

- `google.oauth.auth-uri=https://accounts.google.com/o/oauth2/v2/auth`
- `google.oauth.base-uri=https://oauth2.googleapis.com` — token and revocation endpoints
- `google.api.uri=https://gmail.googleapis.com`

The `local` profile hard-codes `google.api.auth.callback.uri=http://localhost:3000/google/auth/callback`.

Two things have to be done in the Google Cloud console before any of this works, and neither fails
at startup — both surface only when a user tries to connect:

1. **Enable the Gmail API** for the project (APIs & Services → Library). Without it the token
   exchange still succeeds and every Gmail call returns 403. `GET /google/auth/profile` is the
   cheapest way to find out.
2. **Register the redirect URI** exactly as configured above. Google compares it character for
   character, including the trailing path.

The three Gmail scopes requested (`gmail.readonly`, `gmail.send`, `gmail.modify`) are Google
*restricted* scopes. They work for the listed test users while the consent screen is in Testing
mode; publishing to general users additionally requires a CASA security assessment. Refresh tokens
issued by a consent screen in Testing mode expire after seven days, after which the user must
re-consent.

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

### Chat Attachment Configuration

Images attached to chat messages. Attachments are staged at upload and claimed when a message is
sent; staged attachments that are never sent are swept, which is what bounds attachment storage.
Upload size is bounded by `spring.servlet.multipart.max-file-size` rather than a separate variable.

| Variable | Description | Example | Required | Notes |
|----------|-------------|---------|----------|--------|
| `ATTACHMENT_STAGED_TTL` | How long an unsent attachment is kept | `PT24H` | No | Default: PT24H. ISO-8601 duration |
| `ATTACHMENT_SWEEP_ENABLED` | Enable the staged-attachment sweep task | `true` | No | Default: false |
| `ATTACHMENT_SWEEP_CRON` | Sweep schedule | `0 0 * * * *` | No | Default: hourly. Only read when the sweep is enabled |

### Vision Configuration

Image attachments are described by a vision model, and that description is what the chat model sees —
the image bytes are never sent to it. A description is generated once per attachment and stored, so
later turns reuse it without another vision call.

The vision model is configured independently of the chat model, so it can run on different hardware.
Both variables are **required**: the application will not start without them.

| Variable | Description | Example | Required | Notes |
|----------|-------------|---------|----------|--------|
| `VISION_MODEL` | Ollama model used to describe images | `qwen2.5vl` | Yes | Must be vision-capable. A text-only model produces confident nonsense rather than an error |
| `VISION_OLLAMA_HOST` | Ollama base URL for the vision model | `http://izzy-bot-spark:11434` | Yes | A **full base URL**, like `ETL_OLLAMA_HOST` — not the bare hostname that `OLLAMA_HOST` holds |

Fixed in `application.properties` rather than exposed as variables:

- `solesonic.llm.vision.ollama.read-timeout=5m` — a cold vision-model load outlives the default
  read timeout.
- `solesonic.llm.vision.max-image-bytes=5MB` — images above this are left undescribed rather than
  stalling the turn.

### Image Generation Configuration

Text-to-image generation calls the `generate_image` MCP tool, which is backed by a single GPU and has
no admission control of its own — concurrent calls serialize there while each one holds a request
thread on the MCP server for up to its full deadline. The ceiling is therefore enforced here. Callers
past the ceiling wait up to `IMAGE_ADMISSION_TIMEOUT` and are then told to retry (`RATE_LIMITED`).

Both variables are **required**: the application will not start without them.

| Variable | Description | Example | Required | Notes |
|----------|-------------|---------|----------|--------|
| `IMAGE_MAX_CONCURRENT` | Generations this instance will have in flight at once | `2` | Yes | Counted per instance, not per cluster. Above the number of GPUs behind the MCP server it only lengthens queues |
| `IMAGE_ADMISSION_TIMEOUT` | How long a caller waits for a free slot before being refused | `30s` | Yes | Spring duration. Long enough to absorb a burst, short enough that a refusal beats a stalled request |

Fixed in `application.properties` rather than exposed as variables:

- `solesonic.mcp.client.max-in-memory-size=16MB` — ceiling on a buffered MCP response. **Load-bearing
  for image generation**: the tool returns a whole PNG inline as base64, around 2MB, and the WebClient
  default of 256KB aborts the connection mid-body. That failure is not clean — the JSON-RPC response
  is never delivered, so the blocking caller parks until its request timeout and the client sees a
  stream with no terminal frame. It must be set on the `WebClient.Builder` itself;
  `spring.codec.max-in-memory-size` has no effect, because that builder is created directly and
  bypasses Boot's codec auto-configuration.

The MCP request timeout (`spring.ai.mcp.client.request-timeout`, 600s) must stay above the image
server's own 180s generation deadline, or the API abandons requests the server is still working on.
Independently of it, a generation stream that hears nothing for 200s ends itself with
`GENERATION_TIMEOUT` rather than leaving the client waiting on the full request timeout.

No separate credential is configured: generation travels on the calling user's own token, exchanged
for an on-behalf-of token like every other MCP call. The user's JWT must carry the
`mcp-generate-image` role — see [docs/api.md](api.md#image-generation).
- `solesonic.llm.vision.ollama.keep-alive=-1m` — pins the model in Ollama so an idle period does not
  evict it: Ollama reads any negative duration as "keep loaded forever". The unit is mandatory —
  Spring AI sends `keep_alive` as a JSON string, and Ollama rejects a unitless one with
  `400 time: missing unit in duration "-1"`. Set a positive duration such as `30m` instead if the
  vision host also serves other models and needs the VRAM back.
- `solesonic.llm.vision.ollama.warmup-on-startup=true` — preloads the model just after startup, on a
  background thread, so the first image-bearing turn does not pay the cold load.

The model is pulled on startup if missing (`WHEN_MISSING`), so the first boot against a host without
the model will download it before the application becomes ready.

The last two settings exist because a cold load is what makes the vision pass fail: it can take tens
of seconds, and a turn whose vision pass times out still answers normally — just as though no image
were attached. Keeping the model resident makes that rare; the `attachment` SSE event
([docs/api.md](api.md#attachment-event-payload)) makes it visible when it happens anyway. A skipped
image is logged at WARN with the attachment id, the elapsed time, and the reason.

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
REDIS_HOST=redis.internal
REDIS_PASSWORD=your-redis-password

# Atlassian Integration
ATLASSIAN_OAUTH_CLIENT_ID=your_atlassian_client_id
ATLASSIAN_OAUTH_CLIENT_SECRET=your_atlassian_client_secret
ATLASSIAN_OAUTH_TOKEN_URI=https://auth.atlassian.com/oauth/token
JIRA_CLOUD_ID_PATH=/your-cloud-id
CALLBACK_HOST=https://yourdomain.com/settings
ATLASSIAN_TOKENS_ADMIN_KEY=your_admin_key

# Google Integration
GOOGLE_OAUTH_CLIENT_ID=your_google_client_id.apps.googleusercontent.com
GOOGLE_OAUTH_CLIENT_SECRET=your_google_client_secret
GOOGLE_CALLBACK_HOST=https://yourdomain.com/google/auth/callback

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
4. **Redis connection failures**: Verify Redis is running and that `REDIS_HOST` (note the double-D) is set correctly for non-local environments
5. **MCP integration issues**: Verify `SOLESONIC_MCP_URI`, `MCP_CLIENT_ID`, `MCP_CLIENT_SECRET`, and `MCP_ISSUER_URI` are all configured

For more troubleshooting guidance, see [docs/troubleshooting.md](troubleshooting.md).
