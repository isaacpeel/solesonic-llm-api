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

### User Lifecycle Configuration

An hourly sweep purges the memory data — memories, reflection cursors, interest scans, offers and
agents — of users who are no longer around: those Keycloak no longer has or has disabled, and those
with no message of their own in `USER_DORMANT_DAYS`. Reflection skips the same users between sweeps.
The sweep also records each user's username and email from Keycloak, so users who have not signed in
since are shown by name. See [docs/security.md](security.md#user-lifecycle).

The `keycloak-admin` client must be a confidential client in the **user realm** with *Service
accounts roles* enabled and the `realm-management` → `view-users` role assigned to its service
account — nothing more. Removal is concluded only from Keycloak answering `404` (or `enabled:
false`) for a user, after `GET {KEYCLOAK_ADMIN_URI}/users/count` has succeeded in the same sweep; if
that check fails, no one is marked removed.

| Variable | Description | Default | Required | Notes |
|----------|-------------|---------|----------|--------|
| `KEYCLOAK_ADMIN_URI` | Admin REST base of the user realm | — | Yes | `https://auth.example.com/admin/realms/izzy-bot` — the same realm as `ISSUER_URI`, with `/admin` before `/realms` |
| `KEYCLOAK_ADMIN_CLIENT_ID` | Client id of the `keycloak-admin` client | — | Yes | Token from `ISSUER_URI`'s token endpoint, client credentials |
| `KEYCLOAK_ADMIN_CLIENT_SECRET` | Its secret | — | Yes | |
| `USER_LIFECYCLE_ENABLED` | Run the sweep | `true` | No | Reflection still skips dormant and removed users when off |
| `USER_LIFECYCLE_CRON` | Sweep schedule | `0 45 * * * *` | No | Spring cron |
| `USER_DORMANT_DAYS` | Days without a message of their own before a user is dormant | `30` | No | Only `USER` messages count: an interest agent's news chats do not keep a user active. A dormant user who returns has their whole history reflected again |
| `USER_KEYCLOAK_RECHECK_HOURS` | How long a Keycloak lookup is trusted | `24` | No | A user already found removed is not looked up again; a login clears the mark |

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
| `CALLBACK_HOST` | OAuth callback host URL | `https://yourdomain.com/atlassian/auth/callback` | No | Required for production OAuth flows. Must match a redirect URI registered on the Atlassian OAuth app **exactly**, character for character |
| `ATLASSIAN_TOKENS_ADMIN_KEY` | Admin user ID for service account token operations | `your_admin_key` | No | Required for token storage operations |

The `local` profile defaults `jira.api.auth.callback.uri=http://localhost:3000/atlassian/auth/callback`, mirroring the Google pattern below: a dedicated callback route rather than a shared page, so the UI can capture the authorization `code` before any other client-side redirect (e.g. an identity provider's own login flow) has a chance to strip it from the URL.

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

### Model Server Configuration

Every LLM interaction in this application talks to an **OpenAI-compatible** server (llama.cpp
`llama-server` or anything else that speaks the same protocol).

Seven interactions are configured **independently by model name**, but all seven talk to the **same**
OpenAI-compatible server — there is exactly one host variable in the whole application:

| Interaction | Host variable | Model variable | What it does |
|---|---|---|---|
| Chat | `CHAT_OPENAI_HOST` (`spring.ai.openai.base-url`) | `DEFAULT_CHAT_MODEL` | The conversational model |
| Embedding | `CHAT_OPENAI_HOST` | `EMBEDDING_MODEL` | Vectors for the pgvector store |
| ETL | `CHAT_OPENAI_HOST` | `ETL_MODEL` | Keyword + metadata enrichment during document ingestion |
| Vision | `CHAT_OPENAI_HOST` | `VISION_MODEL` | Describing image attachments |
| RAG task | `CHAT_OPENAI_HOST` | `solesonic.llm.rag-task.model` (`DEFAULT_CHAT_MODEL`) | Query rewrite, multi-query expansion, reranking |
| Tool-call task | `CHAT_OPENAI_HOST` | `solesonic.llm.tool-call.model` (`DEFAULT_CHAT_MODEL`) | Slash-command tool-call routing |
| Memory | `CHAT_OPENAI_HOST` | `MEMORY_MODEL` | Reflecting on quiet conversations to learn about their owner |

`CHAT_OPENAI_HOST` is a **full base URL including the `/v1` path** (`http://host:port/v1`) and is
**required** — it carries no masking default, so a missing value fails startup with a clear
placeholder error rather than silently falling back. The six hand-built models in `config/openai`
(ETL ×2, vision, RAG task, tool-call, memory) each inject `spring.ai.openai.base-url` directly via `@Value`
rather than pointing at hosts of their own — there is no per-purpose host property, only a
per-purpose model name. Running ETL or vision on genuinely separate hardware would require
reintroducing a purpose-specific host property and threading it through the relevant `@Bean`
method, which is not wired up today.

Model names are environment variables as well, because a `llama-server`-style process serves
whichever single model it was launched with regardless of what is requested. On such a server the
model name mainly seeds a new user's default model preference and labels the request.

Every client is built with `spring.ai.openai.api-key` (`OPENAI_API_KEY`), but that shared key is
not what a user's calls carry: each user's own key, registered through `/llm-keys`, replaces it on
every model call made for them (see [Security](security.md#per-user-model-keys)). The shared key is
sent only to ingest global documents, which have no owner, and any other request on it is refused
before it leaves. No new variable is needed — user keys live in the database, not in configuration.

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

`spring.ai.openai.api-key` is `${OPENAI_API_KEY}`, the shared service key described above.

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

Fixed in `application.properties` rather than exposed as variables:

- `solesonic.llm.rag-task.openai.read-timeout=5m` / `solesonic.llm.tool-call.openai.read-timeout=5m`
  — each bean passes its own read timeout into `OpenAiChatOptions`, following the ETL/vision pattern.
  Spring AI's `OpenAiChatModel` always sets a per-call request timeout from `OpenAiChatOptions`, and
  that timeout defaults to a hardcoded 60 seconds when a bean doesn't set one explicitly — a cold
  model load past that mark would otherwise surface as a timeout rather than a wait.

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

Image generation calls the MCP server's image tools — one per configured ComfyUI workflow, discovered
from the MCP catalog; nothing here names one. They are backed by a single GPU and have no admission
control of their own — concurrent calls serialize there while each one holds a request thread on the
MCP server for up to its full deadline. The ceiling is therefore enforced here. Callers past the
ceiling wait up to `IMAGE_ADMISSION_TIMEOUT` and are then told to retry (`RATE_LIMITED`).

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

### User Memory Configuration

What the application learns about each user from their conversations. A background task reads
conversations once they have gone quiet, asks the memory model what is durable, validates that in
code, and stores it; plain chat turns then carry the user's standing instructions, and the facts and
preferences relevant to the turn, in the system prompt. On
first deploy every existing conversation is eligible, which is the whole retroactive backfill — it
drains at `MEMORY_REFLECTION_BATCH_SIZE` chats per `MEMORY_REFLECTION_CRON` tick. See
[docs/api.md](api.md#user-memory) and [docs/security.md](security.md#user-memory).

Every variable is optional; the Default column is what applies when it is unset.

| Variable | Description | Default | Required | Notes |
|----------|-------------|---------|----------|--------|
| `MEMORY_MODEL` | Model reflection runs against | `DEFAULT_CHAT_MODEL` | No | Temperature 0. Never follows a chat's `/model` selection |
| `MEMORY_READ_TIMEOUT` | Read timeout for the memory model client | `5m` | No | Spring duration |
| `MEMORY_REFLECTION_ENABLED` | Run the reflection task | `true` | No | Operational switch for the whole deployment |
| `MEMORY_REFLECTION_CRON` | Reflection schedule | `0 */5 * * * *` | No | Spring cron |
| `MEMORY_REFLECTION_IDLE_MINUTES` | How long a conversation must be quiet before it is read | `30` | No | Also how old an `IN_PROGRESS` claim must be before another instance may take it over |
| `MEMORY_REFLECTION_BATCH_SIZE` | Conversations claimed per tick | `5` | No | Processed one model call at a time |
| `MEMORY_REFLECTION_MAX_ATTEMPTS` | Failures before a conversation is given up on | `3` | No | A failure never changes existing memories |
| `MEMORY_REFLECTION_MAX_TRANSCRIPT_CHARACTERS` | Size of one window of a conversation | `12000` | No | Longer conversations are read in windows; nothing is skipped |
| `MEMORY_REFLECTION_SHOWN_MEMORIES` | Existing memories shown to the reflection model | `30` | No | Past it, only those nearest what the user said in the window. Every live memory is still checked for key collisions and duplicates |
| `MEMORY_REFLECTION_DUPLICATE_SIMILARITY` | Cosine similarity at which a proposed addition counts as a live memory said again | `0.9` | No | It becomes a confirmation, or a supersession when it is more detailed than an active memory. Depends on the embedding model |
| `MEMORY_RETENTION_ENABLED` | Run the retention sweep | `true` | No | |
| `MEMORY_RETENTION_CRON` | Retention sweep schedule | `0 15 3 * * *` | No | Spring cron |
| `MEMORY_RETENTION_PENDING_DAYS` | Days an inferred pending memory may go unconfirmed | `30` | No | Then the sweep removes it |
| `MEMORY_RETENTION_MIN_SCORE` | Decayed score below which an active inferred memory is removed | `0.3` | No | Score is confidence halved every 90 days without confirmation, so `0.3` retires a `0.9` memory after about 140 days |
| `MEMORY_INJECTION_ENABLED` | Put active memories in the plain-chat system prompt | `false` | No | Deploy with this off first, review the backfill through the memory panel, then turn it on |
| `MEMORY_PROMPT_MAX_CHARACTERS` | Ceiling on the memory block in the system prompt | `2000` | No | The context window is shared with history and retrieval |
| `MEMORY_RETRIEVAL_MODE` | Which facts and preferences a turn carries: `RELEVANT` (only those relevant to the message and the conversation so far) or `ALL` | `RELEVANT` | No | Standing instructions are carried on every turn either way. `ALL` is the behaviour before retrieval, kept for comparison |
| `MEMORY_RETRIEVAL_TOP_K` | Nearest memories fetched per search | `8` | No | Each turn searches with the message alone and with the message plus earlier user messages |
| `MEMORY_RETRIEVAL_SIMILARITY_THRESHOLD` | Cosine similarity a memory needs to be carried | `0.55` | No | Depends on the embedding model; tune it from the `Memory for user …` log line |
| `MEMORY_RETRIEVAL_QUERY_WINDOW_MESSAGES` | User messages, this one included, that relevance is judged against | `3` | No | `1` judges by the message alone |
| `MEMORY_EMBEDDING_CRON` | Schedule for embedding memories not embedded when written | `0 * * * * *` | No | Spring cron. Covers the backfill on first deploy, failed calls and a change of `EMBEDDING_MODEL` |
| `MEMORY_EMBEDDING_BATCH_SIZE` | Memories embedded per call | `50` | No | |
| `MEMORY_MAX_ACTIVE` | Active memories kept per user | `60` | No | Past it, the weakest inferred memory is evicted; one the user set never is |
| `MEMORY_MAX_CHARACTERS` | Longest single memory | `500` | No | Applies to the panel and to reflection |
| `MEMORY_MIN_CONFIDENCE` | Proposals below this confidence are dropped | `0.6` | No | Between 0 and 1 |

### Push Notification Configuration

Chrome Web Push (RFC 8030 delivery, RFC 8291 encryption, RFC 8292 VAPID). Chrome's push service is
`fcm.googleapis.com`: it needs no Firebase project, account or SDK, and relays only ciphertext.
`PUSH_ENABLED` and the VAPID variables are required and have no default: the key pair and subject
are validated at startup even when push is disabled, and identify one deployment.

| Variable | Description | Default | Required | Notes |
|----------|-------------|---------|----------|--------|
| `PUSH_ENABLED` | Whether push is offered at all | — | Yes | `false` makes `GET /push/vapid-public-key` answer `503` and the UI hide the feature |
| `PUSH_VAPID_PUBLIC_KEY` | VAPID public key | — | Yes | base64url, 65-byte uncompressed P-256 point |
| `PUSH_VAPID_PRIVATE_KEY` | VAPID private key | — | Yes | base64url, 32-byte scalar. **Secret** |
| `PUSH_VAPID_SUBJECT` | Contact for the push service | — | Yes | A `mailto:` or `https:` URI, e.g. `mailto:ops@example.com` |
| `PUSH_ALLOWED_ENDPOINT_HOSTS` | Push service hosts a subscription may name | `fcm.googleapis.com` | No | Comma-separated; matched exactly, never by suffix. The default is Chrome's push service |
| `PUSH_CONNECT_TIMEOUT` | Connect timeout to the push service | `5s` | No | |
| `PUSH_READ_TIMEOUT` | Read timeout to the push service | `10s` | No | |
| `PUSH_MAX_ATTEMPTS` | Sends per push on a `5xx` or an unreachable service | `3` | No | Backoff 1s, 2s, 4s |
| `PUSH_MAX_FAILURES` | Failed pushes before a subscription is deleted | `5` | No | Reset by any delivery |
| `PUSH_ELICITATION_REMINDER_SECONDS` | Delay before reminding of an unanswered elicitation | `120` | No | Must be greater than 0 and less than `solesonic.elicitation.timeout-seconds` |
| `PUSH_PRESENCE_TTL_SECONDS` | How long a reader counts as watching without a heartbeat | `45` | No | |
| `PUSH_PRESENCE_HEARTBEAT_SECONDS` | How often a reader refreshes itself | `15` | No | Must be greater than 0 and less than `PUSH_PRESENCE_TTL_SECONDS` |

**Generating the VAPID keys.** One P-256 pair per deployment, generated once:

```bash
npx web-push generate-vapid-keys
```

or, without Node:

```bash
openssl ecparam -name prime256v1 -genkey -noout -out vapid.pem
openssl ec -in vapid.pem -pubout -outform DER | tail -c 65 | base64 | tr '+/' '-_' | tr -d '=\n'  # public
openssl ec -in vapid.pem -outform DER | tail -c +8 | head -c 32 | base64 | tr '+/' '-_' | tr -d '=\n'  # private
```

Startup fails if either key does not decode, if the two are not a pair, or if the subject is not a
`mailto:`/`https:` URI — rather than on the first send, after browsers had already subscribed.

**Rotating the keys invalidates every browser subscription.** The push service answers `403` for each
one, and the row is deleted on its next send; the UI notices the changed public key and subscribes
again. Rotate only when the private key may have leaked.

### Interest Agent Configuration

Discovery reads what memory has learned and offers a news agent; accepted agents search, summarise and
deliver news chats. Both use the memory model (`MEMORY_MODEL`). See [docs/api.md](api.md#interest-agents)
and [docs/security.md](security.md#interest-agents). Every variable is optional; the Default column is what
applies when it is unset. Both tasks default to off.

| Variable | Description | Default | Required | Notes |
|----------|-------------|---------|----------|--------|
| `INTEREST_DISCOVERY_ENABLED` | Run discovery | `false` | No | Also expires lapsed offers and withdraws orphaned ones |
| `INTEREST_DISCOVERY_CRON` | Discovery schedule | `0 */30 * * * *` | No | Spring cron |
| `INTEREST_DISCOVERY_BATCH_SIZE` | Users read per tick | `5` | No | One model call at a time |
| `INTEREST_DISCOVERY_MAX_ATTEMPTS` | Failures before a user is skipped until their memories change | `3` | No | |
| `INTEREST_DISCOVERY_MIN_CONFIDENCE` | Candidates below this are dropped | `0.7` | No | Between 0 and 1 |
| `INTEREST_OFFER_COOLDOWN_DAYS` | Minimum gap between two offers to one user | `7` | No | |
| `INTEREST_OFFER_TTL_DAYS` | How long an offer can be accepted | `7` | No | Also the offer push's TTL |
| `INTEREST_DECLINE_COOLDOWN_DAYS` | How long a declined topic is not offered again | `90` | No | |
| `INTEREST_MAX_AGENTS_PER_USER` | Agents a user may have | `5` | No | |
| `INTEREST_NEWS_ENABLED` | Run news agents | `false` | No | The global kill switch for every agent. Deploy with this `false` first to review offers |
| `INTEREST_NEWS_CRON` | News schedule | `0 */15 * * * *` | No | Spring cron |
| `INTEREST_NEWS_BATCH_SIZE` | Agents run per tick | `10` | No | |
| `INTEREST_NEWS_MAX_RESULTS` | Results per search | `10` | No | 1–10, the tool's limit |
| `INTEREST_NEWS_EXTRACT_TOP` | New results read in full | `3` | No | 0–5, the extract tool's limit; `0` uses snippets only |
| `INTEREST_NEWS_MAX_ARTICLE_CHARACTERS` | Cap on one article's text | `4000` | No | |
| `INTEREST_NEWS_MAX_SOURCE_CHARACTERS` | Cap on the summary model's input | `20000` | No | ~5–6k tokens; check the memory server's `--ctx-size` before raising it |
| `INTEREST_NEWS_MAX_SOURCES_PER_UPDATE` | Sources one update may cite | `6` | No | |
| `INTEREST_NEWS_MAX_SUMMARY_CHARACTERS` | Cap on the summary text | `2500` | No | |
| `INTEREST_NEWS_DELIVERY_HOUR` | Local hour updates settle onto | `8` | No | 0–23, in the user's own time zone |
| `INTEREST_NEWS_JITTER_MINUTES` | Random spread added to each run | `30` | No | |
| `INTEREST_NEWS_RUN_LEASE_MINUTES` | How long a claimed run is held before another instance may retry it | `30` | No | |
| `INTEREST_NEWS_MAX_FAILURES` | Consecutive failures before an agent is paused | `5` | No | |
| `INTEREST_NEWS_ITEM_RETENTION_DAYS` | How long an agent remembers a URL it has seen | `30` | No | At least 7, the widest search window |
| `INTEREST_NEWS_MANUAL_RUN_COOLDOWN_MINUTES` | Minimum gap between a run and a manual run | `10` | No | |

**The API's service-account client must hold the `mcp-web-search` role.** A background run has no user
token, so it searches on the application's client credentials; without the role every run fails as an
`isError` result and the agent is paused after `INTEREST_NEWS_MAX_FAILURES`.

### Shared Session Configuration

All are optional: set one only to change the default shown.

| Variable | Default | Meaning |
|---|---|---|
| `SHARED_SESSION_AGENTS` | `feud` | Comma-separated A2A agent names a session may be opened with. An invite naming any other agent is `400 INVALID_INVITE`; a listed agent that isn't registered is `503 AGENT_UNAVAILABLE`. Startup fails if it names none |
| `SHARED_SESSION_INVITE_TTL_HOURS` | `24` | How long an invite stays open |
| `SHARED_SESSION_MAX_PENDING_INVITES` | `5` | Open invites one user may have sent |
| `SHARED_SESSION_MAX_MESSAGE_CHARACTERS` | `500` | Limit on a message and on an invite's opening message; at most the agent's `A2A_MAX_MESSAGE_CHARACTERS` |
| `SHARED_SESSION_MAX_NAME_CHARACTERS` | `40` | Participant names are cut to this; at most the agent's own name limit (`FEUD_MAX_NAME_CHARACTERS`) and at most 64 |
| `SHARED_SESSION_RUN_LOCK_SECONDS` | `600` | One run at a time per session; startup fails unless greater than `solesonic.a2a.timeout-seconds` |
| `SHARED_SESSION_IDLE_DAYS` | `7` | Sessions with no message this long expire; match the agent's own state lifetime (`FEUD_GAME_TTL_MINUTES`) |
| `SHARED_SESSION_EXPIRY_CRON` | `0 */10 * * * *` | When invites and idle sessions are expired |

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
| `A2A_LITELLM_BASE_URI` | Base URL of the LiteLLM proxy whose agent gateway serves agents such as `/network` | `https://litellm.yourdomain.com` | Yes | Maps to `solesonic.a2a.litellm.base-uri`; no default. The API calls only `{base}/v1/agents`, `{base}/a2a/{agent}/.well-known/agent-card.json` and the card's own interface URL, and refuses any card whose endpoints are not on this scheme, host and port under `{path}/a2a/`. Use `https` unless LiteLLM is on the same host or a private network, since the virtual key travels on every call. An unreachable LiteLLM at startup is tolerated: discovery gives up after 10 seconds per call, and the API starts without its agents and logs a WARN |
| `A2A_LITELLM_API_KEY` | LiteLLM virtual key, sent as `x-litellm-api-key: Bearer <key>` | `sk-…` | Yes | Maps to `solesonic.a2a.litellm.api-key`; no default. Scope it in LiteLLM to the agents izzybot may call (start with `network`) and treat it like `MCP_CLIENT_SECRET`. The user's token is never sent to LiteLLM |

Sounds an agent sends with its reply are governed by two fixed properties, not environment
variables: `solesonic.a2a.sound.max-bytes=5MB` (a larger clip is ignored) and
`solesonic.a2a.sound.ttl=P7D` (how long a clip stays fetchable from `/sounds/{soundId}` after it was
last cued). Both are required; neither has a default.

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
A2A_LITELLM_BASE_URI=https://litellm.yourdomain.com
A2A_LITELLM_API_KEY=sk-your_litellm_virtual_key

# Push notifications (required — generate the VAPID pair once)
PUSH_ENABLED=true
PUSH_VAPID_PUBLIC_KEY=your_base64url_vapid_public_key
PUSH_VAPID_PRIVATE_KEY=your_base64url_vapid_private_key
PUSH_VAPID_SUBJECT=mailto:ops@yourdomain.com
PUSH_ALLOWED_ENDPOINT_HOSTS=fcm.googleapis.com
PUSH_CONNECT_TIMEOUT=5s
PUSH_READ_TIMEOUT=10s
PUSH_MAX_ATTEMPTS=3
PUSH_MAX_FAILURES=5
PUSH_ELICITATION_REMINDER_SECONDS=120
PUSH_PRESENCE_TTL_SECONDS=45
PUSH_PRESENCE_HEARTBEAT_SECONDS=15

# Interest agents and user memory are optional: every variable has a default


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
CALLBACK_HOST=https://yourdomain.com/atlassian/auth/callback
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

# User Memory Configuration
MEMORY_MODEL=qwen2.5:32b
MEMORY_READ_TIMEOUT=5m
MEMORY_REFLECTION_ENABLED=true
MEMORY_REFLECTION_CRON=0 */5 * * * *
MEMORY_REFLECTION_IDLE_MINUTES=30
MEMORY_REFLECTION_BATCH_SIZE=5
MEMORY_REFLECTION_MAX_ATTEMPTS=3
MEMORY_REFLECTION_MAX_TRANSCRIPT_CHARACTERS=12000
MEMORY_REFLECTION_SHOWN_MEMORIES=30
MEMORY_REFLECTION_DUPLICATE_SIMILARITY=0.9
MEMORY_RETENTION_ENABLED=true
MEMORY_RETENTION_CRON=0 15 3 * * *
MEMORY_RETENTION_PENDING_DAYS=30
MEMORY_RETENTION_MIN_SCORE=0.3
MEMORY_INJECTION_ENABLED=false
MEMORY_PROMPT_MAX_CHARACTERS=2000
MEMORY_RETRIEVAL_MODE=RELEVANT
MEMORY_RETRIEVAL_TOP_K=8
MEMORY_RETRIEVAL_SIMILARITY_THRESHOLD=0.55
MEMORY_RETRIEVAL_QUERY_WINDOW_MESSAGES=3
MEMORY_EMBEDDING_CRON=0 * * * * *
MEMORY_EMBEDDING_BATCH_SIZE=50
MEMORY_MAX_ACTIVE=60
MEMORY_MAX_CHARACTERS=500
MEMORY_MIN_CONFIDENCE=0.6

# Push Notification Configuration
PUSH_ENABLED=true
PUSH_VAPID_PUBLIC_KEY=your_base64url_vapid_public_key
PUSH_VAPID_PRIVATE_KEY=your_base64url_vapid_private_key
PUSH_VAPID_SUBJECT=mailto:ops@yourdomain.com
PUSH_ALLOWED_ENDPOINT_HOSTS=fcm.googleapis.com
PUSH_CONNECT_TIMEOUT=5s
PUSH_READ_TIMEOUT=10s
PUSH_MAX_ATTEMPTS=3
PUSH_MAX_FAILURES=5
PUSH_ELICITATION_REMINDER_SECONDS=120
PUSH_PRESENCE_TTL_SECONDS=45
PUSH_PRESENCE_HEARTBEAT_SECONDS=15

# Interest Agent Configuration
INTEREST_DISCOVERY_ENABLED=true
INTEREST_DISCOVERY_CRON=0 */30 * * * *
INTEREST_DISCOVERY_BATCH_SIZE=5
INTEREST_DISCOVERY_MAX_ATTEMPTS=3
INTEREST_DISCOVERY_MIN_CONFIDENCE=0.7
INTEREST_OFFER_COOLDOWN_DAYS=7
INTEREST_OFFER_TTL_DAYS=7
INTEREST_DECLINE_COOLDOWN_DAYS=90
INTEREST_MAX_AGENTS_PER_USER=5
INTEREST_NEWS_ENABLED=true
INTEREST_NEWS_CRON=0 */15 * * * *
INTEREST_NEWS_BATCH_SIZE=10
INTEREST_NEWS_MAX_RESULTS=10
INTEREST_NEWS_EXTRACT_TOP=3
INTEREST_NEWS_MAX_ARTICLE_CHARACTERS=4000
INTEREST_NEWS_MAX_SOURCE_CHARACTERS=20000
INTEREST_NEWS_MAX_SOURCES_PER_UPDATE=6
INTEREST_NEWS_MAX_SUMMARY_CHARACTERS=2500
INTEREST_NEWS_DELIVERY_HOUR=8
INTEREST_NEWS_JITTER_MINUTES=30
INTEREST_NEWS_RUN_LEASE_MINUTES=30
INTEREST_NEWS_MAX_FAILURES=5
INTEREST_NEWS_ITEM_RETENTION_DAYS=30
INTEREST_NEWS_MANUAL_RUN_COOLDOWN_MINUTES=10

# A2A Configuration
A2A_BASE_URI=https://agents.yourdomain.com
A2A_LITELLM_BASE_URI=https://litellm.yourdomain.com
A2A_LITELLM_API_KEY=sk-your_litellm_virtual_key

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
