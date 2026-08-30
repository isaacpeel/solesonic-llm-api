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

### Xero Integration

Three-legged OAuth2 against Xero, scoped to a single Xero organisation per user. The user consents
once; the token — refresh token included — is encrypted into `user_preferences.xero_access_token`
and never leaves the application. Unlike the Atlassian integration there is no token broker: this
application is Xero's only caller.

Every variable below is Xero-specific and shares nothing with the Atlassian or Google flows, so a
change to one integration's callback cannot silently move another's.

| Variable | Description | Example | Required | Notes |
|----------|-------------|---------|----------|--------|
| `XERO_OAUTH_CLIENT_ID` | OAuth2 client ID of the Xero app | `A1B2C3D4E5F6...` | Yes | From the app's Configuration tab in the Xero developer portal |
| `XERO_OAUTH_CLIENT_SECRET` | OAuth2 client secret paired with the client ID | `xero-client-secret` | Yes | Keep secure; Xero shows it once at generation |
| `XERO_AUTH_CALLBACK_URI` | Redirect URI for the `test` and `local` profiles | `http://localhost:3000/xero/auth/callback` | Yes (test, local) | Must match a redirect URI registered on the Xero app **exactly** |
| `XERO_CALLBACK_HOST` | Redirect URI for the `prod` and `prod-nginx` profiles | `https://yourdomain.com/xero/auth/callback` | Yes (prod) | Registered separately from the local one; Xero allows several per app |
| `XERO_DEFAULT_CONTACT_ID` | The single Xero `ContactID` every invoice created through this API is billed to | `0d7a8f61-3c2e-4a55-9c9c-1f2f1c0b7e11` | Yes | Not caller-supplied and not looked up: there is one contact per deployment. Copy it from the contact's URL in Xero. No default, so a missing value fails startup |

Fixed in `application.properties` rather than exposed as variables, since they are Xero's own
endpoints and identical in every deployment:

- `xero.oauth.auth-uri=https://login.xero.com/identity/connect/authorize`
- `xero.oauth.base-uri=https://identity.xero.com` — the token endpoint, at `/connect/token`
- `xero.api.uri=https://api.xero.com` — both `GET /connections` and the Accounting API

Note that consent and token exchange are on **different hosts** (`login.xero.com` and
`identity.xero.com`), which is why they are two properties rather than one base URI.

The scopes requested are `openid profile email accounting.transactions offline_access`.
`offline_access` is mandatory: without it Xero issues no refresh token at all, and the connection
dies 30 minutes later when the access token expires with nothing able to renew it.

Xero's token endpoint takes `application/x-www-form-urlencoded`, like Google's and unlike
Atlassian's, which accepts JSON.

After the token exchange the callback resolves the organisation from `GET /connections` and stores
its `tenantId` alongside the token — the Accounting API needs it on an `xero-tenant-id` header, and
that call is the only place it exists. Xero's consent screen cannot be limited to one organisation
up front, so a user who grants several has the first taken and a warning logged. A grant covering no
organisation is rejected rather than stored.

### AWS Configuration

| Variable | Description | Example | Required | Notes |
|----------|-------------|---------|----------|--------|
| `AWS_KMS_KEY_ID` | AWS KMS key ID for encryption | `arn:aws:kms:us-east-1:123456789012:key/...` | No | Optional for enhanced security |

### Model Server Configuration

Every LLM interaction in this application talks to an **OpenAI-compatible** server (llama.cpp
`llama-server` or anything else that speaks the same protocol).

Six interactions are configured **independently by model name**, but all six talk to the **same**
OpenAI-compatible server — there is exactly one host variable in the whole application:

| Interaction | Host variable | Model variable | What it does |
|---|---|---|---|
| Chat | `CHAT_OPENAI_HOST` (`spring.ai.openai.base-url`) | `DEFAULT_CHAT_MODEL` | The conversational model |
| Embedding | `CHAT_OPENAI_HOST` | `EMBEDDING_MODEL` | Vectors for the pgvector store |
| ETL | `CHAT_OPENAI_HOST` | `ETL_MODEL` | Keyword + metadata enrichment during document ingestion |
| Vision | `CHAT_OPENAI_HOST` | `VISION_MODEL` | Describing image attachments |
| RAG task | `CHAT_OPENAI_HOST` | `solesonic.llm.rag-task.model` (`DEFAULT_CHAT_MODEL`) | Query rewrite, multi-query expansion, reranking |
| Tool-call task | `CHAT_OPENAI_HOST` | `solesonic.llm.tool-call.model` (`DEFAULT_CHAT_MODEL`) | Slash-command tool-call routing |

`CHAT_OPENAI_HOST` is a **full base URL including the `/v1` path** (`http://host:port/v1`) and is
**required** — it carries no masking default, so a missing value fails startup with a clear
placeholder error rather than silently falling back. The five hand-built models in `config/openai`
(ETL ×2, vision, RAG task, tool-call) each inject `spring.ai.openai.base-url` directly via `@Value`
rather than pointing at hosts of their own — there is no per-purpose host property, only a
per-purpose model name. Running ETL or vision on genuinely separate hardware would require
reintroducing a purpose-specific host property and threading it through the relevant `@Bean`
method, which is not wired up today.

Model names are environment variables as well, because a `llama-server`-style process serves
whichever single model it was launched with regardless of what is requested. On such a server the
model name mainly seeds a new user's default model preference and labels the request.

Each server is expected to run with no API key enforcement — the client is wired in Spring AI's
no-auth mode, so no `Authorization` header is sent.

**What is a server-launch concern rather than app configuration.** Context size, batch size, thread
count and GPU placement are flags on the target server (`--ctx-size`, `--batch-size`, `--threads`,
`--n-gpu-layers` on `llama-server`), not per-request client options. In particular the vision server
has to be launched with a context large enough to hold an image, the model's reasoning and the
description — 32k was the working figure.

**Chat and embedding come from Spring AI's OpenAI auto-configuration** (`spring.ai.model.chat=openai`,
with embedding left at its default), driven by `spring.ai.openai.base-url`, `spring.ai.openai.model`
and `spring.ai.openai.embedding.model`. The auto-configured `EmbeddingModel` is what backs pgvector,
because Spring AI's own `PgVectorStoreAutoConfiguration.vectorStore(EmbeddingModel, ...)` takes an
unqualified parameter there is no way to add a qualifier to — so nothing else may be a default
candidate. The remaining four models are hand-built in `config/openai` as
`@Bean(defaultCandidate = false)` with a `@Qualifier`, and must be injected by qualifier only.

`spring.ai.openai.api-key=none` is set because each server is expected to run with no API key
enforcement.

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
| `DEFAULT_CHAT_MODEL` | Model name the chat server was started with | `qwen2.5:32b` | Yes | Also backs the RAG task and tool-call routing models. A `llama-server`-style process serves whichever single model it was launched with regardless of what's requested, so this mainly seeds a new user's default model preference |

### ETL Configuration

Uploaded documents are split, then enriched with keywords and summary metadata before being embedded.
Enrichment makes an LLM call per chunk, run against the same `CHAT_OPENAI_HOST` as chat, with its
own model chosen for throughput rather than latency.

| Variable | Description | Example | Required | Notes |
|----------|-------------|---------|----------|--------|
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
| `EMBEDDING_MODEL` | Model used to embed documents and queries | `mxbai-embed-large` | Yes | Maps to `spring.ai.openai.embedding.model` |

Embeddings are served from `CHAT_OPENAI_HOST`: Spring AI's OpenAI auto-configuration gives the
embedding client the same `spring.ai.openai.base-url` as chat, and there is no separate host
variable. Dimensions are pinned at 1024 in `spring.ai.openai.embedding.dimensions` and
`spring.ai.vectorstore.pgvector.dimensions`; the two must agree.

**Changing the model changes the vectors**, so an existing corpus has to be re-ingested rather than
mixed — a store holding two models' embeddings ranks incoherently.

### RAG and Tool-Call Task Configuration

Two small models that never talk to the user: one runs the RAG pipeline's own prompts (query
rewrite, multi-query expansion, LLM reranking), the other turns a slash command into a single tool
call. They are configured separately because they are asked for completely different things.

| Variable | Description | Example | Required | Notes |
|----------|-------------|---------|----------|--------|
| `CHAT_OPENAI_HOST` | Base URL both tasks run against | `http://izzy-bot-chat:8080/v1` | Yes | Both beans inject `spring.ai.openai.base-url` directly; there is no separate host property for either |
| `DEFAULT_CHAT_MODEL` | Model both tasks run | `qwen2.5:32b` | Yes | The properties files point `solesonic.llm.rag-task.model` and `solesonic.llm.tool-call.model` at it |

Both model properties are required in every profile — neither carries a Java-side default. The RAG
task model runs at temperature 0 so a rewritten query and a rerank verdict are reproducible for the
same input; the tool-call model needs to be one that calls tools reliably. Pointing either at a host
of its own would require adding a purpose-specific host property back and threading it through the
relevant `@Bean` method in `config/openai`.

### Vision Configuration

Image attachments are described by a vision model, and that description is what the chat model sees —
the image bytes are never sent to it. A description is generated once per attachment and stored, so
later turns reuse it without another vision call.

The vision model is configured independently of the chat model by name, but runs against the same
`CHAT_OPENAI_HOST`. `VISION_MODEL` is **required**: the application will not start without it.

| Variable | Description | Example | Required | Notes |
|----------|-------------|---------|----------|--------|
| `VISION_MODEL` | Model used to describe images | `qwen2.5vl` | Yes | Must be vision-capable. A text-only model produces confident nonsense rather than an error |

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

Keeping a model resident is not an application concern: there is no keep-alive or pull-on-missing
option in the OpenAI protocol, and a `llama-server`-style process loads its one model at startup and
holds it for its lifetime anyway. What remains is the read timeout, which is what lets a first request
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

### A2A (Agent-to-Agent) Configuration

| Variable | Description | Example | Required | Notes |
|----------|-------------|---------|----------|--------|
| `A2A_BASE_URI` | Base URI of the remote A2A agent host | `https://agents.yourdomain.com` | Yes | Maps to `solesonic.a2a.base-uri`; no default, so a missing value fails startup. The request timeout is fixed at `solesonic.a2a.timeout-seconds=300` |

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

# Model server (required — full base URL including /v1, shared by every interaction)
CHAT_OPENAI_HOST=http://localhost:8080/v1
DEFAULT_CHAT_MODEL=qwen2.5:32b
EMBEDDING_MODEL=mxbai-embed-large
ETL_MODEL=llama3.1:8b
VISION_MODEL=qwen2.5vl

# Image generation admission control (required)
IMAGE_MAX_CONCURRENT=2
IMAGE_ADMISSION_TIMEOUT=30s

# A2A (required)
A2A_BASE_URI=https://agents.yourdomain.com

# Xero (required — no defaults, so the application will not start without them)
XERO_OAUTH_CLIENT_ID=your_xero_client_id
XERO_OAUTH_CLIENT_SECRET=your_xero_client_secret
XERO_AUTH_CALLBACK_URI=http://localhost:3000/xero/auth/callback
XERO_DEFAULT_CONTACT_ID=0d7a8f61-3c2e-4a55-9c9c-1f2f1c0b7e11
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
CALLBACK_HOST=https://yourdomain.com/settings
ATLASSIAN_TOKENS_ADMIN_KEY=your_admin_key

# Google Integration
GOOGLE_OAUTH_CLIENT_ID=your_google_client_id.apps.googleusercontent.com
GOOGLE_OAUTH_CLIENT_SECRET=your_google_client_secret
GOOGLE_CALLBACK_HOST=https://yourdomain.com/google/auth/callback

# Xero Integration
XERO_OAUTH_CLIENT_ID=your_xero_client_id
XERO_OAUTH_CLIENT_SECRET=your_xero_client_secret
XERO_CALLBACK_HOST=https://yourdomain.com/xero/auth/callback
XERO_DEFAULT_CONTACT_ID=0d7a8f61-3c2e-4a55-9c9c-1f2f1c0b7e11

# MCP Configuration
SOLESONIC_MCP_URI=http://localhost:3001/sse
MCP_CLIENT_ID=your_mcp_client_id
MCP_CLIENT_SECRET=your_mcp_client_secret
MCP_ISSUER_URI=https://your-auth-server
TOKEN_ENDPOINT=https://your-auth-server/token

# Model Server Configuration — one OpenAI-compatible endpoint, a full base URL
# including /v1, shared by chat, embedding, ETL, vision, RAG task and tool-call routing.
CHAT_OPENAI_HOST=http://localhost:8080/v1
DEFAULT_CHAT_MODEL=qwen2.5:32b
EMBEDDING_MODEL=mxbai-embed-large
ETL_MODEL=llama3.1:8b
VISION_MODEL=qwen2.5vl

# Image Generation Configuration
IMAGE_MAX_CONCURRENT=2
IMAGE_ADMISSION_TIMEOUT=30s

# A2A Configuration
A2A_BASE_URI=https://agents.yourdomain.com

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
6. **Startup fails on a missing placeholder**: `CHAT_OPENAI_HOST` (`spring.ai.openai.base-url`) is required and has no default — every model interaction (chat, embedding, ETL, vision, RAG task, tool-call) injects it. The error names the property; set it to a full base URL including `/v1`
7. **`required a single bean, but 2 were found` for `EmbeddingModel`**: `spring.ai.model.embedding` is not `none`, so the OpenAI starter's own auto-configured embedding bean is competing with the hand-built one for pgvector's unqualified injection point

For more troubleshooting guidance, see [docs/troubleshooting.md](troubleshooting.md).
