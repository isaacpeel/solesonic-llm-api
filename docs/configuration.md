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

Redis is required for streaming chat (Redis Streams) and for caching the slash-command catalog. In the local profile, it defaults to `localhost:6379` without authentication. In production, set the following variables.

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

### Model Server Configuration

Every LLM interaction in this application talks to an **OpenAI-compatible** server (llama.cpp
`llama-server` or anything else that speaks the same protocol). There is no Ollama anywhere: the
`spring-ai-starter-model-ollama` dependency, all `Ollama*` types, and Ollama-only concepts
(keep-alive, pull-on-missing) were removed.

Six interactions are configured **independently**, each with its own host, so each can be pointed at
whichever hardware suits it:

| Interaction | Host variable | Model property | What it does |
|---|---|---|---|
| Chat | `CHAT_OPENAI_HOST` | `solesonic.llm.chat.model` | The conversational model |
| ETL | `ETL_OPENAI_HOST` | `solesonic.llm.etl.model` (`ETL_MODEL`) | Keyword + metadata enrichment during document ingestion |
| Vision | `VISION_OPENAI_HOST` | `solesonic.llm.vision.model` (`VISION_MODEL`) | Describing image attachments |
| Embedding | `EMBEDDING_OPENAI_HOST` | `solesonic.llm.embedding.model` | Vectors for the pgvector store |
| RAG task | `RAG_TASK_OPENAI_HOST` | `solesonic.llm.rag-task.model` | Query rewrite, multi-query expansion, reranking |
| Tool-call task | `TOOL_CALL_OPENAI_HOST` | `solesonic.llm.tool-call.model` | Slash-command tool-call routing |

Every `*_OPENAI_HOST` is a **full base URL including the `/v1` path** (`http://host:port/v1`) and
every one is **required** — none carries a masking default, so a missing one fails startup with a
clear placeholder error rather than silently falling back. Nothing requires them to be six different
hosts; pointing several at one server is fine.

The model-name properties live in `application-{local,test,prod,prod-nginx}.properties` rather than
being exposed as variables, because a `llama-server`-style process serves whichever single model it
was launched with regardless of what is requested. On such a server the model name mainly seeds a
new user's default model preference and labels the request.

Each server is expected to run with no API key enforcement — the client is wired in Spring AI's
no-auth mode, so no `Authorization` header is sent.

**What is a server-launch concern rather than app configuration.** Context size, batch size, thread
count and GPU placement are flags on the target server (`--ctx-size`, `--batch-size`, `--threads`,
`--n-gpu-layers` on `llama-server`), not per-request client options. In particular the vision server
has to be launched with a context large enough to hold an image, the model's reasoning and the
description — 32k was the working figure.

**Both Spring AI auto-configurations are disabled** (`spring.ai.model.chat=none`,
`spring.ai.model.embedding=none`). Every model here is hand-built in `config/openai`, and the
embedding one has to be the sole unqualified `EmbeddingModel` bean in the context because Spring
AI's own `PgVectorStoreAutoConfiguration.vectorStore(EmbeddingModel, ...)` takes an unqualified
parameter there is no way to add a qualifier to. Left enabled, the starter's own
`openAiEmbeddingModel` would be a second candidate and the context would fail to start.

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
| `ATTACHMENT_DOCUMENT_MAX_SIZE_BYTES` | Largest document attachment that will be indexed for retrieval | `10MB` | No | Default: 10MB. Bounds how long a user waits on the turn the document is sent, not storage. A document past it is still stored and downloadable, just not indexed |

### Chat Configuration

Main chat is served by its own OpenAI-compatible server, pinned to a URI dedicated to chat inference
— separate from the hosts that back ETL, vision, embeddings, RAG query-rewrite, and slash-command
tool routing. See [Model Server Configuration](#model-server-configuration) for the whole set.

The variable is **required**: the application will not start without it.

| Variable | Description | Example | Required | Notes |
|----------|-------------|---------|----------|--------|
| `CHAT_OPENAI_HOST` | Base URL for the OpenAI-compatible chat endpoint | `http://izzy-bot-chat:8080/v1` | Yes | A **full base URL** including the `/v1` path, matching the shape `spring.ai.openai.base-url`/OpenAI itself expects |

Fixed in `application*.properties` rather than exposed as a variable:

- `solesonic.llm.chat.model` — the model name the server was started with. A `llama-server`-style
  process serves whichever single model it was launched with regardless of what's requested, so this
  mainly seeds a new user's default model preference.

### ETL Configuration

Uploaded documents are split, then enriched with keywords and summary metadata before being embedded.
Enrichment makes an LLM call per chunk, so it runs against its own host and can be sized for
throughput rather than latency.

| Variable | Description | Example | Required | Notes |
|----------|-------------|---------|----------|--------|
| `ETL_OPENAI_HOST` | Base URL for the OpenAI-compatible enrichment endpoint | `http://izzy-bot-spark:8080/v1` | Yes | A **full base URL** including the `/v1` path |
| `ETL_MODEL` | Model used for keyword and metadata enrichment | `llama3.1:8b` | Yes | Small and fast beats large here — it is one call per chunk |

Fixed in `application.properties` rather than exposed as variables:

- `solesonic.llm.etl.openai.read-timeout=5m` — a cold model load outlives the default read timeout
  and surfaces as a timeout on the first ingest rather than a wait.

Note that chat *document attachments* deliberately bypass this path entirely: they are split and
embedded inline on the turn they are sent, with no enrichment, because the user is waiting.

### Embedding Configuration

Vectors for the pgvector store.

| Variable | Description | Example | Required | Notes |
|----------|-------------|---------|----------|--------|
| `EMBEDDING_OPENAI_HOST` | Base URL for the OpenAI-compatible embedding endpoint | `http://izzy-bot:8080/v1` | Yes | A **full base URL** including the `/v1` path |

The model name is fixed per profile as `solesonic.llm.embedding.model`. **Changing it changes the
vectors**, so an existing corpus has to be re-ingested rather than mixed — a store holding two
models' embeddings ranks incoherently.

### RAG and Tool-Call Task Configuration

Two small models that never talk to the user: one runs the RAG pipeline's own prompts (query
rewrite, multi-query expansion, LLM reranking), the other turns a slash command into a single tool
call. They are configured separately because they are asked for completely different things.

| Variable | Description | Example | Required | Notes |
|----------|-------------|---------|----------|--------|
| `RAG_TASK_OPENAI_HOST` | Base URL for the RAG task endpoint | `http://izzy-bot:8080/v1` | Yes | A **full base URL** including the `/v1` path |
| `TOOL_CALL_OPENAI_HOST` | Base URL for the tool-call routing endpoint | `http://izzy-bot:8080/v1` | Yes | A **full base URL** including the `/v1` path |

The model names are fixed per profile as `solesonic.llm.rag-task.model` and
`solesonic.llm.tool-call.model`. Both are required in every profile — neither carries a Java-side
default any more. The RAG task model runs at temperature 0 so a rewritten query and a rerank verdict
are reproducible for the same input; the tool-call model needs to be one that calls tools reliably.

### Vision Configuration

Image attachments are described by a vision model, and that description is what the chat model sees —
the image bytes are never sent to it. A description is generated once per attachment and stored, so
later turns reuse it without another vision call.

The vision model is configured independently of the chat model, so it can run on different hardware.
Both variables are **required**: the application will not start without them.

| Variable | Description | Example | Required | Notes |
|----------|-------------|---------|----------|--------|
| `VISION_MODEL` | Model used to describe images | `qwen2.5vl` | Yes | Must be vision-capable. A text-only model produces confident nonsense rather than an error |
| `VISION_OPENAI_HOST` | Base URL for the OpenAI-compatible vision endpoint | `http://izzy-bot-spark:8080/v1` | Yes | A **full base URL** including the `/v1` path |

Fixed in `application.properties` rather than exposed as variables:

- `solesonic.llm.vision.openai.read-timeout=5m` — a cold vision-model load outlives the default
  read timeout and surfaces as a timeout on the first image rather than a wait.
- `solesonic.llm.vision.max-image-bytes=5MB` — images above this are left undescribed rather than
  stalling the turn.

The vision server must be **launched** with a context window large enough for an image, the model's
reasoning and the description — 32k is the working figure. That is a `--ctx-size` flag on the
server, not something this application can set per request. A budget that runs out mid-reasoning
yields an empty description rather than a truncated one.

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

Keeping a model resident is no longer something this application asks for: Ollama's `keep_alive` and
pull-on-missing have no OpenAI-protocol equivalent, and a `llama-server`-style process loads its one
model at startup and holds it for its lifetime anyway — which is exactly the behaviour those
settings were emulating. What remains is the read timeout, which is what lets a first request
survive a slow load rather than aborting it. A turn whose vision pass fails still answers normally,
just as though no image were attached; the `attachment` SSE event
([docs/api.md](api.md#attachment-event-payload)) is what makes that visible. A skipped image is
logged at WARN with the attachment id, the elapsed time, and the reason.

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

# Model Server Configuration — six OpenAI-compatible endpoints, each a full base URL
# including /v1. All six are required; several may point at the same server.
CHAT_OPENAI_HOST=http://localhost:8080/v1
ETL_OPENAI_HOST=http://localhost:8080/v1
ETL_MODEL=llama3.1:8b
VISION_OPENAI_HOST=http://localhost:8080/v1
VISION_MODEL=qwen2.5vl
EMBEDDING_OPENAI_HOST=http://localhost:8080/v1
RAG_TASK_OPENAI_HOST=http://localhost:8080/v1
TOOL_CALL_OPENAI_HOST=http://localhost:8080/v1

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
6. **Startup fails on a missing placeholder**: all six `*_OPENAI_HOST` variables are required and none has a default. The error names the property; set it to a full base URL including `/v1`
7. **`required a single bean, but 2 were found` for `EmbeddingModel`**: `spring.ai.model.embedding` is not `none`, so the OpenAI starter's own auto-configured embedding bean is competing with the hand-built one for pgvector's unqualified injection point

For more troubleshooting guidance, see [docs/troubleshooting.md](troubleshooting.md).
