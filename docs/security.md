# Security Guide

This document outlines the security architecture, authentication mechanisms, and security considerations for the Solesonic LLM API.

## Overview

The Solesonic LLM API implements a comprehensive security model based on OAuth2 and JWT tokens, with support for multiple authentication flows and secure token management.

### Security Architecture

```
┌─────────────────┐    ┌──────────────────┐    ┌─────────────────┐
│   Frontend      │    │  Solesonic API   │    │  OAuth2         │
│   Application   │◄──►│                  │◄──►│  Provider       │
│                 │    │  - JWT Validation│    │ (any provider) │
└─────────────────┘    │  - Token Broker  │    └─────────────────┘
                       └──────────────────┘
                                │
                                ▼
                       ┌──────────────────┐
                       │  External MCPs   │
                       │  (Jira/Confluence│
                       │   via Token      │
                       │   Broker)        │
                       └──────────────────┘
```

## Authentication and Authorization

### OAuth2 with JWT

The application uses OAuth2 with JSON Web Tokens (JWT) for stateless authentication and authorization.

#### Token Validation Process

1. **Token Reception**: API receives JWT token in Authorization header
2. **Signature Verification**: Token signature validated against JWK Set
3. **Claims Validation**: Token claims (issuer, audience, expiration) verified
4. **User Context**: User information extracted from token for request processing

#### Required Environment Variables

```bash
# OAuth2/JWT Configuration
ISSUER_URI=https://your-issuer
JWK_SET_URI=https://your-issuer/.well-known/jwks.json
```

For complete configuration details, see [docs/configuration.md](configuration.md).

### Authentication Flows

#### Authorization Code Flow (Frontend Applications)

Used by web and mobile applications for user authentication:

1. User redirects to OAuth2 provider
2. User authenticates and consents to application access
3. Provider returns authorization code to application
4. Application exchanges code for access token
5. Application includes access token in API requests

#### Client Credentials Flow (Service-to-Service)

Used by the MCP token broker and other service integrations:

1. Service authenticates with client ID and secret
2. OAuth2 provider issues access token
3. Service includes access token in API requests

### Token Broker Architecture

The Solesonic API includes a sophisticated token broker for secure 3-legged OAuth2 scenarios, particularly for Atlassian API access.

#### Why Token Broker?

- **Security**: Long-lived refresh tokens stored securely (AWS Secrets Manager)
- **Isolation**: MCP servers only receive short-lived access tokens
- **Automatic Rotation**: Refresh tokens rotated transparently
- **User Agency**: Users maintain control over their API access

#### Token Broker Flow

```
┌─────────────┐   ┌──────────────┐   ┌─────────────────┐   ┌─────────────┐
│ MCP Server  │   │ Token Broker │   │ Secrets Manager │   │ Atlassian   │
│             │   │              │   │                 │   │ API         │
└─────────────┘   └──────────────┘   └─────────────────┘   └─────────────┘
       │                  │                    │                   │
       │ 1. Request Token │                    │                   │
       ├─────────────────►│                    │                   │
       │                  │ 2. Fetch Refresh   │                   │
       │                  │    Token           │                   │
       │                  ├───────────────────►│                   │
       │                  │                    │                   │
       │                  │ 3. Refresh Token   │                   │
       │                  │    (if needed)     │                   │
       │                  ├───────────────────────────────────────►│
       │                  │                    │                   │
       │ 4. Access Token  │                    │                   │
       │ (1 hour expiry)  │                    │                   │
       │◄─────────────────┤                    │                   │
       │                  │                    │                   │
       │ 5. API Call      │                    │                   │
       ├─────────────────────────────────────────────────────────►│
```

### JWT Token Structure

#### Standard Claims

- `iss` (Issuer): OAuth2 provider URI
- `sub` (Subject): Unique user identifier
- `aud` (Audience): Application identifier
- `exp` (Expiration): Token expiration timestamp
- `iat` (Issued At): Token issue timestamp
- `jti` (JWT ID): Unique token identifier

#### Custom Claims

- `email`: User's email address
- `email_verified`: Email verification status
- `token_use`: Token type (`access` or `id`)
- `scope`: OAuth2 scopes granted

#### Example JWT Payload

```json
{
  "sub": "550e8400-e29b-41d4-a716-446655440000",
  "iss": "https://your-issuer",
  "aud": "your-client-id",
  "token_use": "access",
  "auth_time": 1704067200,
  "iat": 1704067200,
  "exp": 1704070800,
  "jti": "12345678-1234-1234-1234-123456789abc",
  "username": "user@example.com",
  "email": "user@example.com",
  "email_verified": true
}
```

## Security Configuration

### Profile-Based Security

#### Local Profile
- **Relaxed Authentication**: For development convenience
- **Optional JWT Validation**: May allow anonymous access for testing
- **CORS Permissive**: Allows `http://localhost:3000` by default

#### Production Profile
- **Strict Authentication**: All endpoints require valid JWT tokens
- **Full JWT Validation**: All token claims validated
- **CORS Restrictive**: Only configured origins allowed

### CORS (Cross-Origin Resource Sharing)

CORS is configured to prevent unauthorized cross-origin requests:

```bash
# Single origin
CORS_ALLOWED_ORIGINS=https://yourdomain.com

# Multiple origins
CORS_ALLOWED_ORIGINS=https://yourdomain.com,https://app.yourdomain.com
```

### Rate Limiting

Not implemented at the application level. The per-IP budgets live in nginx —
`limit_req` for request rate and `limit_conn` for the streaming routes, where one long-lived
connection is not a burst — see [deploy/nginx/solesonic-llm-api.conf](../deploy/nginx/solesonic-llm-api.conf).

fail2ban reacts to a pattern over time and does not stop the first hundred requests, so the nginx
limits are what blunt a burst. An in-app Redis-backed limiter is the remaining piece; it would emit
`ratelimit.exceeded` (already in `SecurityEvent`) and would survive a direct-to-Tomcat request if
the loopback bind ever regressed.

## Threat Model

### Assets

1. **User Data**: Chat conversations, user preferences
2. **Atlassian Data**: Jira issues, Confluence pages accessed via API
3. **Authentication Tokens**: JWT tokens, OAuth2 refresh tokens
4. **Configuration Secrets**: Database passwords, OAuth2 client secrets

### Threats and Mitigations

#### T1: Unauthorized API Access
- **Threat**: Attackers accessing API without valid authentication
- **Mitigation**: JWT token validation on all endpoints
- **Detection**: Monitor failed authentication attempts

#### T2: Token Theft and Reuse
- **Threat**: Stolen JWT tokens used for unauthorized access
- **Mitigation**: Short token expiration times, HTTPS everywhere
- **Detection**: Monitor for unusual access patterns

#### T3: Man-in-the-Middle Attacks
- **Threat**: Interception of API traffic
- **Mitigation**: TLS 1.2+ everywhere, certificate pinning where possible
- **Detection**: Monitor for certificate anomalies

#### T4: Injection Attacks
- **Threat**: SQL injection, prompt injection in LLM interactions
- **Mitigation**: Parameterized queries, input validation, prompt sanitization
- **Detection**: Monitor for unusual database queries and LLM responses

#### T4a: Cross-User Memory Exposure and Memory Poisoning
- **Threat**: One user's learned memories reaching another user, or text the user did not write (an
  email, a web page, a tool result) being recorded as something true about them
- **Mitigation**: See [User Memory](#user-memory)
- **Detection**: Reflection logs drop counts per validation reason, never content

#### T5: Secrets Exposure
- **Threat**: Exposure of configuration secrets (passwords, keys)
- **Mitigation**: Environment variables only, secrets management systems
- **Detection**: Monitor for secrets in logs or repositories

#### T6: Privilege Escalation
- **Threat**: Users accessing resources beyond their permissions
- **Mitigation**: Proper scope validation, user context enforcement
- **Detection**: Monitor for access pattern anomalies

### Security Controls

#### Preventive Controls
- JWT token validation
- HTTPS/TLS encryption
- Input validation and sanitization
- Secure secrets management
- CORS policy enforcement

#### Detective Controls
- Access logging and monitoring
- Failed authentication tracking
- Anomaly detection in usage patterns
- Security scanning of dependencies

#### Responsive Controls
- Automated token revocation capabilities
- Incident response procedures
- Security update processes

#### Security Settings
- **Password Policy**: Strong password requirements
- **MFA Support**: Multi-factor authentication available
- **Account Recovery**: Secure account recovery mechanisms
- **User Verification**: Email verification required

#### Client Applications
1. **Frontend Client**: Authorization code flow
2. **Token Broker Client**: Client credentials flow

### JWK Set Validation

The application validates JWT tokens against an issuer JWK Set:

```bash
# JWK Set endpoint
https://your-issuer/.well-known/jwks.json
```

## Security Best Practices

### Development

1. **Never commit secrets**: Use environment variables and `.env` files (excluded from git)
2. **Use HTTPS locally**: Configure local SSL certificates for realistic testing
3. **Rotate secrets regularly**: Change development secrets periodically
4. **Validate inputs**: Always validate and sanitize user inputs
5. **Monitor dependencies**: Keep dependencies updated, scan for vulnerabilities

### Deployment

1. **Secrets Management**: Use AWS Secrets Manager, Kubernetes secrets, or similar
2. **Network Security**: Use VPCs, security groups, and network policies
3. **Monitoring**: Implement comprehensive logging and monitoring
4. **Updates**: Establish regular security update processes
5. **Backup Security**: Encrypt backups and control access

### Operations

1. **Access Control**: Implement least-privilege access to infrastructure
2. **Audit Logging**: Log all administrative actions
3. **Incident Response**: Maintain incident response procedures
4. **Security Testing**: Regular penetration testing and security assessments
5. **Training**: Keep team updated on security best practices

## Security Logging

Two log streams, with two different jobs.

| Stream | Format | Consumer | Path |
| --- | --- | --- | --- |
| Application log | ECS JSON | humans, a future log shipper | `/var/log/solesonic-llm-api/solesonic-llm-api.log` |
| Security log | plain text, fixed grammar | fail2ban | `/var/log/solesonic-llm-api/security.log` |

The application log is JSON via `logging.structured.format.*` in `application.properties` — no
extra dependency, Boot 4 has it natively. Note that `logback-spring.xml` cannot use Boot's
`base.xml` for it: `base.xml` includes the *plain* console and file appenders, which ignore
`logging.structured.format.*`. The structured includes are used instead, selected by profile so the
console stays human-readable outside prod.

`RequestLoggingFilter` writes one line per request **at completion**, with structured key/value
pairs (`http.response.status_code`, `event.duration`, `client.ip`, redacted `url.query`).
`MdcRequestFilter` adds `request.id`, `client.ip`, and `user.id`; `ReactorMdcPropagationConfig`
carries the MDC across the thread hops the streaming path makes deliberately, so a turn's logs are
still correlated.

### The security log

```
2026-08-02T14:03:11.442Z SECURITY event=authn.failure ip=203.0.113.10 method=GET path="/actuator/env" status=401 reason=missing_token route=unknown
```

Written by `service/security/SecurityEventLogger` — the only place the grammar exists — from the
closed enums `SecurityEvent` and `SecurityEventReason`. Its format is a machine interface: the jail
filters in `deploy/fail2ban` match it literally, so changing a field order or the appender pattern
silently stops a jail from matching rather than breaking a build.

Three properties are load-bearing:

- **The address is never read from a header.** `request.getRemoteAddr()` only —
  `server.forward-headers-strategy=native` under `prod-nginx` makes Tomcat resolve it, and nginx is
  configured to *overwrite* `X-Forwarded-For` rather than append. An address an attacker can
  influence is a remote-controlled firewall.
- **No free-form input reaches the file.** No `User-Agent`, no header, no body, no query string.
  The path is the one attacker-influenced field kept, sanitized against an allowlist and truncated,
  so no request can write a line of its own.
- **`route=known|unknown` is what makes an aggressive jail safe.** In prod every request is
  authenticated, so a scanner is turned away with 401 and never reaches a controller — there is no
  404 to match on. The classification happens at the point of rejection instead, and only the
  half that fires on paths the application does not serve is banned on one strike.

Events currently emitted: `authn.failure`, `authz.denied`, `broker.denied`, `route.unknown`,
`method.rejected`. The enum also carries `authn.rejected_subject`, `authn.rejected_issuer`,
`authn.rejected_audience`, `oauth.state_mismatch`, and `ratelimit.exceeded`, whose emission sites
arrive with the subject allowlist, the OAuth state check, and a rate limiter respectively.

`authz.denied` with `reason=wrong_subject` is `service/security/ResourceOwnershipService`, which
enforces that a `{userId}` path segment names the caller: `GET`/`POST`/`PUT /users/{userId}/preferences`
and `GET /chats/users/{userId}` all answer `403` for a mismatched subject (see
[docs/api.md](api.md)). The streaming chat routes enforce the same rule independently, via
`ChatStreamAccessService`, and do not emit this event.

### What is never logged

Chat message content, prompt content, retrieved document text, and vision descriptions are never
logged above DEBUG, and never in the security log at all.
`spring.ai.chat.client.observations.log-prompt` is `false` in every profile.

## User Memory

What is learned about a user is read, written, shown and injected for its owner and nobody else, on
every path. This is enforced by structure rather than convention:

- **Every row carries `user_id`**, and evidence and supersession reference their memory through
  composite foreign keys that include it.
- **Ownership is a `where` clause**, never a fetch-then-compare. Another user's memory is a `404`.
- **Relevance search is scoped the same way.** The vector search filters on `user_id`, and what it
  returns is only carried if it is also among the owner's active memories read for the turn.
  Embeddings live on the memory row, not in `vector_store`, so document retrieval can never return a
  memory.
- **The one cross-user access is administration, by the `izzy-bot-admin` Keycloak group** — the
  `groups` claim, mapped to `GROUP_*` authorities so no role can impersonate a group. On
  `/users/{userId}/memories` the group passes the ownership check, but every query is still scoped
  to the path's `{userId}`, so an administrator acts on one named user at a time under exactly that
  user's rules, and each such call is logged with the administrator's id. The cross-user listings,
  `/memories` and `/memories`, are read-only and gated by `@PreAuthorize` on the whole
  controller; their unscoped reads live in their own `UserMemoryAdminRepository`, so
  `UserMemoryRepository` keeps every method `user_id`-scoped, and memory content is never a sort
  key. Nothing on these paths reaches the model.
- **Reflection works on one user at a time.** The owner comes from the chat row and nowhere else, and
  the only memories the model is shown are that owner's.
- **The model's output is validated in code** (`ReflectionValidator`). Any memory id that is not a
  live memory of that owner is dropped. Any evidence id that is not a `USER` message of the window
  being read is dropped, and an operation left without evidence is dropped. This is the
  prompt-injection defence: mail, Jira, Confluence, web pages, retrieved chunks and A2A replies only
  ever reach the transcript inside `ASSISTANT` rows, so text the user did not write cannot be cited
  as evidence. The transcript renders every message as one quoted line, so nothing inside a message
  can forge a `USER` line of its own.
- **Citing a user message is not enough; the memory must be grounded in it.** An addition or
  supersession is dropped (`UNGROUNDED`) unless at least half of its content words, and every number
  in it, appear in the `USER` messages it cites, so neither a line lifted from a summarised email nor
  the answer to the user's own question can be pinned to their message. Content that is not a
  statement about the user at all is dropped first (`NOT_ABOUT_USER`). This deliberately misses some
  memories the user only implied: a missed memory is cheaper than a planted one. Assistant replies are
  shown to the reflection model only as a 300-character excerpt.
- **Pasted text is not the user talking about themselves.** A `USER` message over 1000 characters is
  shown to the reflection model cut to that length and labelled `pasted=true`, and can evidence a
  fact but never a preference or a standing instruction (`PASTED_EVIDENCE`).
- **The user's name** (the token's `given_name`, else `name`) is recorded in `user_profile` by the JWT
  filters and given to the reflection model, so the user is never recorded as a third person. It is
  never logged and never sent anywhere else.
- **User lifecycle** <a id="user-lifecycle"></a>— memory data is purged for users who are no longer
  around, and reflection skips them in the meantime. *Removed* means Keycloak answered `404` or
  `enabled: false` for the user, and is only ever concluded after `GET /users/count` on the configured
  realm succeeded in the same sweep, so a wrong `KEYCLOAK_ADMIN_URI`, a missing role or an outage marks
  nobody removed. *Dormant* means no `USER` message in `USER_DORMANT_DAYS`; an interest agent's news
  chats do not count. The `keycloak-admin` client holds only `realm-management/view-users`. A login
  clears a removed mark, since a valid token proves the account exists.
- **Username and email** (`preferred_username`, `email`) are recorded in the same row. The username
  is returned only by admin-gated listings (`/memories`, `/memories/reflections`, `/memories/status`,
  `/images/admin`), next to the `userId` they already expose. The email is stored for later use and
  returned by no endpoint. Neither is logged.
- **Memory in tool calls.** Plain chat turns can call web-search tools, so the memory block tells the
  model to put nothing from it in a tool call or search query unless the request needs it. This is
  an instruction, not a control.
- **`/remember` and chat deletion serialize** on a row lock on the chat, so a memory is either
  written before the delete and forgotten with it, or finds the chat already gone.
- **Reflection cannot delete**, and cannot change a memory the user set (`REMEMBERED`/`AUTHORED`).
- **`/remember` exists only as a slash command**, which only the user can type. It is never offered
  to the model as a tool: a model-callable "remember" is an injection target. Its owner comes from
  the conversation in the server-side tool context, never from tool arguments.
- **Memories never enter `vector_store`, Redis, or any shared cache**, and leave the process only in
  the owner's own plain-chat turns — never to A2A agents, never into MCP tool arguments.
- **Never logged above DEBUG, never in the security log.** Reflection logs counts only.
- **Deleting a conversation forgets it**: nothing learned from it survives the delete.

The rule against recording health, religion, sexuality, politics, union membership, immigration
status, credentials or financial account details is in the reflection prompt only; no code can
enforce it reliably. The memory panel (`/users/{userId}/memories`) is the backstop.

## Push Notifications

Browser push sends to a URL a client supplied, through Google's relay, to a phone's lock screen. Each
of those is a boundary:

- **The endpoint is an SSRF surface.** `PushEndpointValidator` runs on every `PUT` and again before
  every send, so a row stored under an older allowlist is never posted to. It requires `https`; a host
  that is **exactly** one of `PUSH_ALLOWED_ENDPOINT_HOSTS`, with no suffix matching
  (`fcm.googleapis.com.evil.example` and `evil.fcm.googleapis.com` are both refused); no user-info, no
  explicit port, no IP literal, no fragment; at most 2048 characters. The push `RestClient` never
  follows a redirect, which would take the request off the allowlisted host.
- **Browser keys are checked before use.** `p256dh` must be an uncompressed P-256 point that lies on
  the curve, so ECDH is never run against an off-curve point (the invalid-curve attack); `auth` must be
  16 bytes.
- **The endpoint and keys are a capability**: whoever holds them can push to that browser. They are
  never returned (`PushSubscriptionSummary` is the only response shape), never logged (logs name the
  subscription id), and the records that carry them redact themselves in `toString`.
- **No conversation content in any push.** `PushPayloadFactory` builds every payload from fixed strings
  and ids alone; no chat title, message text or tool data is ever put in one.
- **Ownership.** A `{userId}` that is not the caller is a `404` on every push endpoint. Every repository
  method takes the owner, except the endpoint lookup the upsert needs.
- **Fan-out is bounded.** A user keeps at most 20 subscriptions (the oldest are evicted), and a row the
  push service keeps refusing counts toward `PUSH_MAX_FAILURES` and deletes itself.
- **A leaked endpoint can be re-registered** under another account with other keys: the owner's browser
  then receives pushes it cannot decrypt and silently stops being notified. The attacker learns nothing,
  since payloads carry only ids and fixed text. The move is logged at INFO with the subscription id.
- **The VAPID private key is a secret.** A startup failure names the variable, never its value.
  Rotating the pair invalidates every subscription: the push service answers `403`, and each row is
  deleted on its next send.
- **Presence is server-side** because Chrome's `userVisibleOnly` contract makes the service worker show
  every push it receives; a push cannot be dropped on arrival. A presence failure counts as "not
  watching" — an extra push beats a missing one.

## Interest Agents

Discovery reads a user's memories and offers a news agent; an accepted agent reads the web on the
user's behalf and writes a chat. Each of those is a boundary:

- **Opt-in only.** Discovery creates offers, never agents. An agent exists only after the owner calls
  `accept` with their own token.
- **One owner per row, enforced by structure.** Every table carries `user_id`, every reference includes
  it, and every repository method takes it, so another user's offer, agent or update is a `404`. The
  owner always comes from a row — the claimed scan or agent — never from model output or a tool
  argument, and discovery only accepts memory ids the model was shown, which are the owner's own active
  facts and preferences.
- **Web content is untrusted data.** Search results and extracted articles reach the summary model only
  as quoted, single-line fields (`PromptQuoting`) with no tools offered, and reach the user only as an
  `ASSISTANT` row — which reflection never accepts as memory evidence.
- **Every link comes from a search result.** The model can cite only by number, and `NewsUpdateRenderer`
  builds each link itself from a result's normalized URL. Everything the model or a page wrote — summary,
  headline, notes, titles, the label — is rendered as plain text: **every ASCII punctuation character is
  backslash-escaped**, so no link, image, reference definition, HTML, heading, quote or table can form
  however it is spelled (escaped or nested brackets, definitions inside quotes or lists). A regex alone
  cannot recognise every CommonMark link form; escaping does not need to. Only `- ` bullets and paragraphs
  are kept. Before escaping, `ModelTextSanitizer` removes what a renderer would autolink with no markup:
  URLs, `www.` hosts, `//host`, scheme references and email addresses.
- **Extraction cannot be pointed inward by a search result.** `NewsUrlNormalizer` keeps only `http`/`https`
  URLs on a public domain name, with no user-info and no explicit port: IP literals, `localhost` and
  internal suffixes (`.local`, `.internal`, …) are refused before the extraction tool is asked to fetch
  them. The MCP server still needs its own resolve-time checks, since a public name can resolve inward.
- **A news chat is a normal chat.** Its summary is web-derived text in an `ASSISTANT` row, and a follow-up
  runs on the default route with the user's tools. That is the same exposure as any web search result
  in a conversation, but it is worth knowing when judging a summary's instructions.
- **No topic in any push.** `INTEREST_OFFER` and `INTEREST_UPDATE` payloads carry fixed text and ids
  only; the topic, the headline and the news are shown only inside the app.
- **Background searches use the application's identity.** A run has no user token, so the MCP call
  travels on the API's client credentials. The user's own right to search (`mcp-web-search`) is checked
  **once, at accept**. **Known limitation:** losing the role later does not stop an existing agent;
  `INTEREST_NEWS_ENABLED=false` stops every agent at once.
- **Deleting a conversation still forgets it.** A pending offer none of whose source memories is still
  live is withdrawn in the same transaction as the forget, and by a sweep for memories that stop being
  live by supersession or eviction. An accepted agent survives: the user turned it on, and it stores only
  its own label and query.
- **Topic labels, queries, search results and summaries are never logged above DEBUG** and never reach
  the security log; run logs carry counts and ids only (`DiscoveryRun`, `NewsRun`).

## Per-User Model Keys

Every model call made for a user — chat, embeddings, vision, document enrichment, RAG tasks, tool-call
routing, memory reflection, interest discovery and news summaries — carries that user's own
model-server key rather than the shared `OPENAI_API_KEY`, so the model server (LiteLLM) attributes
spend, budgets and model access to the person. Rules: [API](api.md#model-access-keys).

- **Only the `llm-key-admin` role manages keys.** Every `/llm-keys` endpoint, reads included, is gated
  on the role; a key hint, and who holds model access at all, are an administrator's business.
- **A key is write-only.** It is encrypted at rest with the same `BytesEncryptor` as the stored
  Atlassian and Google tokens, no response ever carries it, and only its last four characters are
  kept in the clear, for telling keys apart. `LlmApiKey.toString()` and the request body's
  `toString()` are redacted, and nothing logs a key or any part of one.
- **A key is checked before it is stored.** It is used once to list the model server's models; a key
  the server refuses is a `400`, and the shared service key is refused outright.
- **No key fails closed.** A user without one gets a `403` from both send endpoints and from
  `GET /chats/models`, before anything is persisted, and background work for them (memory reflection,
  interest discovery, news runs, memory embedding, queued document ingestion) is skipped at claim time
  or fails rather than falling back to the shared key.
- **The shared key is for the shared corpus only.** Ingesting a global document — which has no owner —
  is the one model work that runs on `OPENAI_API_KEY`, and it has to say so (`LlmCredentialScope.asService`).
  `UserLlmKeyInterceptor`, on every OpenAI client in the application, refuses any other request that
  would leave on the shared key, so a call site that forgot whose turn it is fails loudly instead of
  quietly spending on it.
- **A2A is unchanged.** The LiteLLM agent gateway is still called with `A2A_LITELLM_API_KEY`, below.

## A2A Agents through LiteLLM

Agents such as `/network` are reached through LiteLLM's agent gateway instead of the A2A agent host.

- **Every authenticated user can read household network data. This is a stated decision, not an
  oversight.** `/network` has no role gate, and the agent does no per-user authorization, so per-person
  device ownership and browsing categories (`adult` included) are available to every izzybot user. A
  multi-tenant deployment must revisit this before it ships.
- **The user's token never reaches LiteLLM.** A LiteLLM agent call carries only
  `x-litellm-api-key: Bearer <A2A_LITELLM_API_KEY>`. `LiteLlmA2AAuthInterceptor` removes any
  `Authorization` header, whatever its case, and never calls the token exchange. LiteLLM authenticates to
  the agent with a static header of its own, so the agent never learns who asked.
- **The virtual key is a shared service credential.** Scope it in LiteLLM to the izzybot agents and
  treat it like `MCP_CLIENT_SECRET`. It is never logged, never put in an exception message, and never
  written into a card.
- **Cards are pinned to the gateway.** `LiteLlmAgentDiscovery` keeps only the path of each
  `supportedInterfaces` URL. That path must sit under the gateway's agent routes, `{base path}/a2a/`,
  with no encoded dots or slashes, and the endpoint is rebuilt on the scheme and host of
  `A2A_LITELLM_BASE_URI`. The legacy `url` and `additionalInterfaces` are dropped, since the client
  never routes through them. Whatever host or scheme a mis-curated card names, the key and users'
  messages only ever go to the configured gateway over its configured scheme, and never to another
  gateway route such as key management or chat completions. Rebuilding, rather than refusing a
  mismatch, is what lets this work behind a TLS-terminating proxy, where LiteLLM writes `http://` into
  the card.
- **Agent names are command names.** The slash command is the `agent_name` LiteLLM lists, not the
  card's `name` (LiteLLM serves the display name there). An `agent_name` that does not match
  `[A-Za-z0-9][A-Za-z0-9._-]{0,63}` is skipped before its card is fetched. The name becomes a path
  segment, a slash command, log text and the text of the "not available" error, so nothing else reaches
  any of them. Refusals log only sanitized values (`Redactor.sanitizePath`).
- **The card's security fields are ignored.** `securitySchemes`/`security` describe authenticating to
  LiteLLM, which the virtual key header already does. They are removed before parsing because LiteLLM
  writes them in a shape the 1.0 SDK rejects.
- **Discovery cannot hold up startup.** LiteLLM discovery has its own 10-second timeout per call rather
  than the 300-second turn timeout. Agent-host cards are not pinned, which predates this feature.
- **`A2AClientProperties.Litellm.toString()` redacts the key.**
- **Agent answers are untrusted text.** The network agent quotes device-chosen hostnames. Answers reach
  the UI through the same Markdown rendering as every other agent's text, and reach the transcript only
  as `ASSISTANT` rows, which memory reflection never accepts as evidence.

## Security Monitoring

### Metrics to Monitor

1. **Authentication Failures**: Failed JWT validation attempts
2. **Unusual Access Patterns**: Access from new locations or unusual times
3. **Token Usage**: Token refresh patterns and anomalies
4. **API Usage**: Unusual API call patterns or rates
5. **Error Rates**: Spikes in 4xx/5xx HTTP responses

### Alerting

Configure alerts for:
- Multiple authentication failures from same IP
- Access attempts with expired or invalid tokens
- Unusual geographic access patterns
- High error rates or response times
- Failed secret rotation attempts

## Compliance Considerations

### Data Protection
- **GDPR/CCPA**: User data handling and deletion capabilities
- **Data Retention**: Configurable data retention policies
- **Encryption**: Data encrypted in transit and at rest
- **Access Logs**: Comprehensive audit trails

### Industry Standards
- **OWASP Top 10**: Regular assessment against OWASP security risks
- **NIST Framework**: Alignment with NIST cybersecurity framework
- **SOC 2**: Consider SOC 2 compliance for enterprise customers

## Incident Response

### Security Incident Types
1. **Token Compromise**: Suspected JWT token theft or misuse
2. **Data Breach**: Unauthorized access to user or system data
3. **Service Compromise**: Suspected compromise of API or infrastructure
4. **Dependency Vulnerability**: Critical security vulnerability in dependencies

### Response Procedures
1. **Detection**: Monitor and detect security incidents
2. **Assessment**: Evaluate severity and impact
3. **Containment**: Isolate and contain the incident
4. **Eradication**: Remove threat and vulnerabilities
5. **Recovery**: Restore normal operations
6. **Lessons Learned**: Post-incident analysis and improvements

## Related Documentation

- **Configuration**: [docs/configuration.md](configuration.md) - Security-related environment variables
- **MCP Integration**: [docs/mcp-integration.md](mcp-integration.md) - Token broker implementation
- **Deployment**: [docs/deployment.md](deployment.md) - Production security considerations
- **Troubleshooting**: [docs/troubleshooting.md](troubleshooting.md) - Security-related troubleshooting