# API Documentation

This document provides an overview of the Solesonic LLM API endpoints and how to interact with them.

## API Base URL

The API is available at:
- **Local Development**: `http://localhost:8080`
- **With Context Path**: `http://localhost:8080/{BASE_URI}` (when the `BASE_URI` environment variable is set)
- **Production**: Varies based on deployment (typically HTTPS on port 8443)

## Authentication

The API uses OAuth2 with JWT for authentication. All requests in production require a valid JWT token in the Authorization header:

```
Authorization: Bearer <your-jwt-token>
```

For local development, authentication may be more relaxed depending on configuration.

---

## Streaming Chat API

All chat creation and continuation happens over Server-Sent Events (SSE). There is no non-streaming chat creation endpoint.

`{userId}` must be the subject of the bearer token, and `{chatId}` must name a chat that user owns —
otherwise `403`. An unknown `{chatId}` is `404`.

### Start Streaming Chat

- **Endpoint**: `POST /streaming/chats/users/{userId}`
- **Produces**: `text/event-stream`
- **Path Parameters**:
  - `userId` (UUID): The user starting the chat
- **Request Body**: `ChatRequest`

### Continue Streaming Chat

- **Endpoint**: `PUT /streaming/chats/{chatId}/users/{userId}`
- **Produces**: `text/event-stream`
- **Path Parameters**:
  - `chatId` (UUID): The existing chat session to continue
  - `userId` (UUID): The user continuing the chat
- **Request Headers**:
  - `Last-Event-ID` (optional, deprecated): Replays instead of starting a turn, exactly as the
    resume endpoint does, including its status codes. Kept for clients that already do this; new
    clients should use `GET .../stream`, which needs no request body.
- **Request Body**: `ChatRequest`

### Resume a Stream

- **Endpoint**: `GET /streaming/chats/{chatId}/users/{userId}/stream`
- **Produces**: `text/event-stream`
- **Request Headers**:
  - `Last-Event-ID` (optional): the `id:` of the last frame the client received. Also accepted as
    `?lastEventId=` for debuggability; the header wins when both are present. Omitted, or `0`,
    replays the whole retained stream.

Replays every buffered frame after the cursor — progress frames included, so a client's step log
survives — then continues live through `done`. Resuming never re-runs a turn: generation and
persistence are already independent of any listener, so this is purely a second view of work that
is happening regardless.

| Status | Meaning |
|--------|---------|
| `200` | Replaying, then live through `done` |
| `204` | The turn finished and the cursor already covers every frame of it |
| `400` | `Last-Event-ID` is not a stream id — see the id format below |
| `403` | The chat is not the caller's |
| `404` | No such chat |
| `410` | The buffer expired, or the cursor predates the oldest retained frame (replaying would leave a gap) |

Every non-`200` is decided before the response is committed, so it arrives immediately rather than
as a stream that never produces anything. On `404` or `410` the fallback is `GET /chats/{chatId}`,
which returns the persisted turn.

Frames are retained for `redis.stream.retention-seconds` (default 900) past a chat's most recent
frame, on a sliding expiry.

### Event IDs

Every frame carries an `id:` — a Redis stream entry id of the form
`<millisecondsSinceEpoch>-<sequence>`, for example `1754062831251-1`. Ids increase monotonically
within a chat (not just within a turn) and are what `Last-Event-ID` expects back, verbatim.

Treat them as opaque strings. **Do not parse one as an integer** — `parseInt` silently discards the
sequence half, collapsing every frame emitted in the same millisecond onto one cursor value and
dropping frames on resume. To compare two ids, split on `-` and compare the halves numerically,
most significant first.

`0` is the "from the beginning" sentinel and is never the id of a real frame.

### Keepalives

While a stream is in flight and nothing has been sent for `redis.stream.keepalive-seconds`
(default 15), the server writes an SSE comment:

```
: keepalive

```

A turn that is still thinking emits no bytes at all, which is what mobile radios, load balancers
and proxies reap. Per the SSE spec a line beginning with `:` is a comment; keepalives carry no
`id:` and no `data:`, and must not advance a client's resume cursor.

Streaming responses also set `Cache-Control: no-cache` and `X-Accel-Buffering: no`, the latter so
an nginx in front of the API does not buffer away the frames whose value is in arriving early.

### Stream Event Types

Both streaming endpoints emit the following SSE event types:

| Event | Description |
|-------|-------------|
| `init` | Sent at stream start. Payload carries the persisted user message id — see below |
| `chunk` | Incremental assistant response text |
| `progress` | A long-running step started — an MCP tool, or the vision pass on one attached image |
| `attachment` | Terminal outcome for one attached image — see below |
| `image` | An image generated during this turn, by reference — see below |
| `elicitation` | Interactive form request from an MCP tool |
| `cancel` | Emitted when a user cancels an elicitation |
| `done` | Final event containing the structured chat response |

### image Event Payload

Emitted when a turn generates an image — `/generate_image`, or the model calling the tool itself.
The payload is a `GeneratedImageSummary`, identical in shape to the `complete` frame of
[explicit generation](#image-generation):

```json
{
  "imageId": "7c2f...",
  "chatMessageId": null,
  "imageUrl": "/izzybot/images/7c2f...",
  "prompt": "a small red lighthouse",
  "model": "FLUX.1-schnell",
  "seed": 8339331079448168597,
  "width": 1024,
  "height": 1024,
  "steps": 4,
  "elapsedSeconds": 6.1,
  "fileSizeBytes": 1502931,
  "created": "2026-07-31T16:40:14Z"
}
```

Never bytes. The image data stops at the API boundary and is fetched separately from `imageUrl`.

The frame is emitted from the tool result, which lands before the model has written its first word,
so it always arrives ahead of `chunk` text and well ahead of `done`. `chatMessageId` is `null` here —
the assistant turn it belongs to has not been written yet — and is filled in by the time the same
image appears in history.

The same references are repeated on the `done` payload as `message.generatedImages`, so a client
that reconnected mid-stream and missed this frame still finalizes the turn with the image on it.
De-duplicate by `imageId`.

### init Event Payload

```json
{
  "chatId": "0a4b...",
  "messageId": "7f3c..."
}
```

`messageId` is the id of the user message persisted at the start of the turn. Clients that
uploaded attachments (see [Chat Attachments](#chat-attachments)) use it to associate them with the
rendered message. Clients that ignore the `init` body are unaffected.

### attachment Event Payload

```json
{
  "attachmentId": "3f9a...",
  "chatId": "0a4b...",
  "described": true,
  "reason": null
}
```

The vision pass opens with a `progress` event per image and closes with an `attachment` event per
image. Guarantees a client can rely on:

- **Exactly one `attachment` event per id in `ChatRequest.attachmentIds`** — including images
  skipped before any work started, and images the server could not resolve at all. A client never
  has to interpret a missing event.
- **Always before `done`**, so the event lands while the assistant message is still streaming.

Nothing else in the turn distinguishes a described image from a skipped one: a skipped image still
produces a normal answer, just one written as though no image were attached.

When `described` is `false`, `reason` is one of a closed set:

| Reason | Meaning |
|--------|---------|
| `VISION_TIMEOUT` | The vision model was reachable but did not answer in time, most often a cold model load |
| `VISION_UNAVAILABLE` | The vision host could not be reached, or returned an error |
| `IMAGE_TOO_LARGE` | The image exceeds `solesonic.llm.vision.max-image-bytes` |
| `IMAGE_UNREADABLE` | The vision model returned nothing usable, or the attachment could not be loaded |
| `EXCEEDED_IMAGE_LIMIT` | More images were attached to one message than the vision pass describes |

`reason` is `null` when `described` is `true`. Unlike `progress`, these events are not persisted as
`SYSTEM` chat messages — the durable form of the same signal is `described` on the attachment
summary in chat history.

### ChatRequest Body

```json
{
  "chatMessage": "Your message here",
  "commands": ["/ask"],
  "attachmentIds": ["3f9a...", "b721..."]
}
```

- `commands` (optional): slash commands to invoke for this turn.
- `attachmentIds` (optional): ids returned by `POST /attachments`. Every id must be one the caller
  uploaded and has not already sent, otherwise the turn is rejected with `409 Conflict` and no
  message is persisted.

### Submit Elicitation Response

When an MCP tool issues an elicitation, the frontend receives an `elicitation` SSE event and must POST the user's response before the stream can continue.

- **Endpoint**: `POST /streaming/chats/{chatId}/{elicitationId}/elicitation-response`
- **Path Parameters**:
  - `chatId` (UUID): The active chat session
  - `elicitationId` (UUID): The specific elicitation to respond to
- **Request Body**:
```json
{
  "elicitationResponse": {
    "name": "delete-confirmation",
    "fields": { "confirmed": "accept" },
    "action": "accept"
  }
}
```
- **Action values**: `accept`, `decline`, `cancel`
- **Responses**:
  - `200 OK` - Response accepted
  - `400 Bad Request` - Invalid payload
  - `404 Not Found` - No matching pending elicitation

See [elicitation.md](elicitation.md) for full architecture and examples.

### cURL Example

```bash
curl -N -X POST "http://localhost:8080/streaming/chats/users/${USER_ID}" \
  -H "Content-Type: application/json" \
  -d '{
    "chatMessage": "Create a Jira issue for adding dark mode support",
    "model": "qwen2.5:7b"
  }'
```

### JavaScript Example

```typescript
const eventSource = new EventSource(`${baseUrl}/streaming/chats/users/${userId}`);

eventSource.addEventListener('chunk', (event) => {
  console.log('Text chunk:', event.data);
});

eventSource.addEventListener('elicitation', async (event) => {
  const payload = JSON.parse(event.data);
  // Render form and collect user input, then:
  await fetch(`${baseUrl}/streaming/chats/${payload.chatId}/${payload.elicitationId}/elicitation-response`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      elicitationResponse: { name: payload.name, fields: { confirmed: 'accept' }, action: 'accept' }
    })
  });
});

eventSource.addEventListener('done', (event) => {
  console.log('Chat complete:', JSON.parse(event.data));
  eventSource.close();
});
```

---

## Chat History API

These endpoints retrieve existing chat history. They do not create or send messages.

### Get All Chats for a User

- **Endpoint**: `GET /chats/users/{userId}`
- **Path Parameters**:
  - `userId` (UUID): The user whose chats to retrieve
- **Query Parameters**:
  - `page` (int, default `0`): Zero-based page index
  - `size` (int, default `20`, max `100`): Page size
- **Response**: A page of chat objects, newest first (`timestamp` descending, `id` as a tiebreaker)

Parameters are bounded rather than rejected: a negative `page` is treated as `0`, a `size` above the
`spring.data.web.pageable.max-page-size` limit is capped at it, and scrolling past the last page
returns an empty `content` array instead of an error. A `sort` parameter is accepted by the resolver
but ignored: the ordering is fixed by the repository query, so that pages cannot overlap or skip a chat.

```json
{
  "content": [
    { "id": "...", "userId": "...", "timestamp": "...", "chatGroupId": null, "chatMessages": [] }
  ],
  "page": {
    "size": 20,
    "number": 0,
    "totalElements": 137,
    "totalPages": 7
  }
}
```

For infinite scroll, request `page = 0, 1, 2, …` and stop when `page.number + 1 >= page.totalPages`.
The ordering is deterministic, so pages never overlap or skip a chat.

### Get a Specific Chat

- **Endpoint**: `GET /chats/{chatId}`
- **Path Parameters**:
  - `chatId` (UUID): The chat session to retrieve
- **Response**: Complete chat object with message history

### Rename a Chat

- **Endpoint**: `PUT /chats/{chatId}/name`
- **Path Parameters**:
  - `chatId` (UUID): The chat session to rename
- **Request Body**: `ChatRenameRequest` — `{ "name": "..." }`
- **Response**: The updated chat object

The caller's identity comes only from the bearer token (`UserRequestContext`, resolved from the JWT
subject), never from a request parameter, so there is no `userId` to supply or spoof. A chat that
does not exist, or is not owned by the caller, is `404`. A blank name or one over 255 characters is
`400`.

---

## Conversation Groups

Optional, user-owned sections a conversation can be filed under. Grouping is entirely additive: a
chat belongs to at most one group, every chat starts ungrouped, and nothing about a conversation
changes when it is filed or unfiled. Each chat carries its membership as a read-only `chatGroupId`
wherever a chat is returned — `null` when it is ungrouped.

Like [Rename a Chat](#rename-a-chat), these endpoints take no `userId`: the caller's identity comes
only from the bearer token. A group or a chat that does not exist, or that belongs to another user,
is `404` in both cases — the endpoints do not confirm that an id exists to someone who cannot read
it.

Their own path rather than a segment of `/chats`, because `/chats/{chatId}` takes a UUID and a
literal segment underneath it would be matched as a chat id.

### Create a Group

- **Endpoint**: `POST /chatgroups`
- **Request Body**: `ChatGroupRequest` — `{ "name": "..." }`
- **Response**: `201 Created`, a `Location` header, and the created group

```json
{
  "id": "b8f1...",
  "userId": "0c31...",
  "name": "Work",
  "timestamp": "2026-08-23T16:40:14Z"
}
```

The name is trimmed. A blank name or one over 255 characters is `400`. Names are not required to be
unique — two groups may share one, and are ordered against each other by id so a listing never
reshuffles.

### List Groups

- **Endpoint**: `GET /chatgroups`
- **Response**: Every group the caller owns, ordered by name

### Get a Group

- **Endpoint**: `GET /chatgroups/{chatGroupId}`
- **Response**: The group

### Get the Conversations in a Group

- **Endpoint**: `GET /chatgroups/{chatGroupId}/chats`
- **Query Parameters**:
  - `page` (int, default `0`): Zero-based page index
  - `size` (int, default `20`, max `100`): Page size
- **Response**: A page of chat objects, in the same shape and the same order as
  [`GET /chats/users/{userId}`](#get-all-chats-for-a-user) — newest first, with messages hydrated.
  A `sort` parameter is accepted by the resolver but ignored, for the same reason it is there.

### Add a Conversation to a Group

- **Endpoint**: `PUT /chatgroups/{chatGroupId}/chats/{chatId}`
- **Response**: `204 No Content`

A `PUT` because it is idempotent: filing a conversation that is already in this group is a success.
Filing one that is in another group moves it, since a chat carries at most one group.

### Remove a Conversation from a Group

- **Endpoint**: `DELETE /chatgroups/{chatGroupId}/chats/{chatId}`
- **Response**: `204 No Content`

Ungroups the conversation; the chat and its messages are untouched. A chat that is not in this group
is `404` rather than a silent success — the client's picture of where the conversation lives is
wrong, and reporting the removal as done would leave it wrong.

---

## Ollama Model Management

These endpoints manage the application's catalog of Ollama model configurations stored in the database, and can also query which models are currently installed in Ollama.

### List All Models

- **Endpoint**: `GET /ollama/models`
- **Query Parameters**:
  - `refresh` (boolean, default `false`): When `true`, evicts the Redis model cache before returning results
- **Response**: Array of `OllamaModel` objects

### Get a Specific Model

- **Endpoint**: `GET /ollama/models/{id}`
- **Path Parameters**:
  - `id` (UUID): The model record ID
- **Response**: `OllamaModel` object

### Create a Model Record

- **Endpoint**: `POST /ollama/models`
- **Request Body**: `OllamaModel`
- **Response**: The created `OllamaModel`

### Update a Model Record

- **Endpoint**: `PUT /ollama/models/{id}`
- **Path Parameters**:
  - `id` (UUID): The model record to update
- **Request Body**: `OllamaModel`
- **Response**: The updated `OllamaModel`

### List Installed Ollama Models

- **Endpoint**: `GET /ollama/installed`
- **Description**: Queries the Ollama server for currently installed models, enriched with database metadata
- **Response**: Array of `OllamaModel` objects

### Refresh Model Cache

- **Endpoint**: `POST /ollama/models/refresh`
- **Description**: Eagerly refetches all installed models from Ollama and repopulates the Redis cache (model details + show-model responses have no TTL and are only updated via this endpoint, a one-time warmup at startup, or a model record save/update)
- **Authorization**: Requires `model-admin` role
- **Response**: `204 No Content`

---

## Document and Training Data

### Upload a Document

- **Endpoint**: `POST /documents/data/upload`
- **Request**: `multipart/form-data` with a `file` field
- **Description**: Queues a document for processing and ingestion into the vector store. Supported formats include PDF and plain text.
- **Response**: `201 Created` with a `Location` header pointing to the queued training document record

### Vector Search

- **Endpoint**: `POST /documents/data/search`
- **Request Body**: `VectorSearch` containing the query text
- **Response**: Array of matching document text excerpts ranked by similarity

### List Training Documents

- **Endpoint**: `GET /trainingdocuments`
- **Response**: Array of `TrainingDocument` objects, including processing status (`QUEUED`, `PROCESSING`, `COMPLETED`, `FAILED`)

---

## User Preferences

User preferences control per-user settings such as which Ollama model to use for chat and the similarity threshold for RAG retrieval.

Stored OAuth tokens are **never serialized** on any of these endpoints. Connection state travels as
two booleans instead — `atlassianAuthentication` and `googleAuthentication` — which is what a client
should read to decide whether to show "connected" or a "connect" link:

```json
{
  "userId": "user-uuid-here",
  "model": "qwen3.5:9b",
  "similarityThreshold": 0.75,
  "atlassianAuthentication": true,
  "googleAuthentication": false,
  "created": "2026-08-11T16:55:00Z",
  "updated": "2026-08-11T16:55:00Z"
}
```

Because tokens cannot round-trip, a `POST` or `PUT` body that omits them does **not** clear them —
the stored tokens are preserved. Disconnecting is an explicit action
([`POST /google/auth/revoke`](#revoke-access)), never a side effect of saving preferences.

### Get Preferences

- **Endpoint**: `GET /users/{userId}/preferences`
- **Path Parameters**:
  - `userId` (UUID): The user whose preferences to retrieve
- **Response**: `UserPreferences` object

### Create Preferences

- **Endpoint**: `POST /users/{userId}/preferences`
- **Path Parameters**:
  - `userId` (UUID)
- **Request Body**: `UserPreferences`
- **Response**: `201 Created` with the created `UserPreferences`

### Update Preferences

- **Endpoint**: `PUT /users/{userId}/preferences`
- **Path Parameters**:
  - `userId` (UUID)
- **Request Body**: `UserPreferences`
- **Response**: Updated `UserPreferences`

---

## Slash Commands

Slash commands are loaded from the connected MCP tool catalog and cached in Redis. The type-ahead endpoint powers command pickers in frontend clients.

### Type-Ahead Search

- **Endpoint**: `GET /slash/commands`
- **Query Parameters**:
  - `command` (string, optional): Prefix to filter commands. Omit to return all commands.
- **Response**: `SlashCommandCatalogResponse` containing a list of matching `SlashCommand` objects

---

## Atlassian Authentication

These endpoints handle the OAuth2 authorization code flow for connecting a user's Atlassian account (Jira/Confluence).

### Get Authorization URI

- **Endpoint**: `GET /atlassian/auth/uri`
- **Response**: `AtlassianAuthLinkResponse` containing the URL the user should visit to authorize access

### OAuth Callback

- **Endpoint**: `GET /atlassian/auth/callback`
- **Query Parameters**:
  - `code` (string): The authorization code returned by Atlassian
- **Response**: `204 No Content`

### Get Accessible Resources

- **Endpoint**: `GET /atlassian/auth/accessible-resources`
- **Description**: Returns the Atlassian sites the authenticated user has access to
- **Response**: JSON string from the Atlassian API

---

## Atlassian Token Broker

The token broker provides short-lived Atlassian access tokens to MCP servers without exposing long-lived refresh tokens. Callers must hold the `token-mint-jira` role.

### Mint Token

- **Endpoint**: `POST /broker/atlassian/token`
- **Authorization**: Requires `token-mint-jira` role
- **Request Body**:
```json
{
  "subject_token": "user-uuid-here",
  "audience": "site-id-optional"
}
```
- **Response**:
```json
{
  "accessToken": "eyJ0eXAiOiJKV1QiLCJhbGciOiJSUzI1NiIs...",
  "expiresInSeconds": 3600,
  "issuedAt": "2025-09-03T16:55:00Z",
  "userId": "user-uuid-here",
  "siteId": "site-id-optional"
}
```

See [mcp-integration.md](mcp-integration.md) for the full token broker architecture and integration guide.

---

## Google Authentication

These endpoints handle the OAuth2 authorization code flow for connecting a user's Google account (Gmail).

### Get Authorization URI

- **Endpoint**: `GET /google/auth/uri`
- **Description**: Builds the Google consent URL. Always requests `access_type=offline` and `prompt=consent`, which is what makes Google return a refresh token.
- **Response**: `GoogleAuthLinkResponse` containing the URL the user should visit to authorize access

```json
{
  "uri": "https://accounts.google.com/o/oauth2/v2/auth?client_id=...&scope=..."
}
```

### OAuth Callback

- **Endpoint**: `GET /google/auth/callback`
- **Query Parameters**:
  - `code` (string): The authorization code returned by Google
- **Description**: Exchanges the code for tokens and stores them, encrypted, against the authenticated user. If Google returns no refresh token — which it does whenever the grant already existed — the previously stored one is kept rather than overwritten.
- **Response**: `204 No Content`

### Get Gmail Profile

- **Endpoint**: `GET /google/auth/profile`
- **Description**: Returns the connected mailbox's own profile. Useful as a post-connect check: a token exchange succeeds even when the Gmail API is not enabled for the project, and that misconfiguration shows up here rather than at connect time.
- **Response**: `application/json` — the Gmail API's own profile document, passed through unchanged

```json
{
  "emailAddress": "someone@example.com",
  "messagesTotal": 12043,
  "threadsTotal": 8871,
  "historyId": "992144"
}
```

### Revoke Access

- **Endpoint**: `POST /google/auth/revoke`
- **Description**: Revokes the grant at Google and deletes the stored token. Safe to call when nothing is connected.
- **Response**: `204 No Content`

### Google Error Responses

Every Google failure — on these endpoints and on the token broker — arrives as a failing status code
with a stable `code` to branch on. Google's own error text is logged, never returned.

```json
{
  "code": "RECONNECT_REQUIRED",
  "message": "Google access is no longer valid. Reconnect your Google account."
}
```

| Code | Status | Meaning |
|---|---|---|
| `RECONNECT_REQUIRED` | `400` | No token stored, or Google answered `invalid_grant` (revoked, expired, or a Testing-mode token past seven days). Retrying cannot fix it — send the user through `GET /google/auth/uri` again |
| `RATE_LIMITED` | `429` | Google is throttling. Back off and retry |
| `UPSTREAM_UNAVAILABLE` | `503` | Google, or the call to it, failed in a way a retry may fix |
| `INTERNAL` | `500` | Anything else |

Unlike the Atlassian endpoints, which render a failure as `200 OK` carrying a chat message, these are
plain REST: a success is `204` and a failure is a 4xx/5xx. A client can branch on the status line
alone.

---

## Google Token Broker

The token broker provides short-lived Google access tokens to MCP servers without exposing long-lived refresh tokens. Callers must hold the `token-mint-gmail` role.

### Mint Token

- **Endpoint**: `POST /broker/google/token`
- **Authorization**: Requires `token-mint-gmail` role
- **Request Body**:
```json
{
  "subject_token": "user-uuid-here"
}
```
- **Response**:
```json
{
  "accessToken": "ya29.a0AfB_byC...",
  "expiresInSeconds": 3599,
  "issuedAt": "2026-08-11T16:55:00Z",
  "userId": "user-uuid-here"
}
```

A user who has never connected Google, or whose grant has been revoked, produces a message telling them to re-consent — retrying will not fix it.

See [mcp-integration.md](mcp-integration.md) for the full token broker architecture and integration guide.

---

## Confluence Pages

These endpoints proxy to the Confluence REST API using the authenticated user's stored OAuth token.

### List Pages

- **Endpoint**: `GET /confluence/pages`
- **Response**: `ConfluencePagesResponse`

### Get a Page

- **Endpoint**: `GET /confluence/pages/{id}`
- **Path Parameters**:
  - `id` (string): Confluence page ID
- **Response**: `Page` object

### Create a Page

- **Endpoint**: `POST /confluence/pages`
- **Request Body**: `Page` object in Confluence storage format
- **Response**: `201 Created` with the created `Page`

### Update a Page

- **Endpoint**: `PUT /confluence/pages/{id}`
- **Path Parameters**:
  - `id` (string): Confluence page ID
- **Request Body**: `Page` object
- **Response**: Updated `Page`

### Delete a Page

- **Endpoint**: `DELETE /confluence/pages/{id}`
- **Path Parameters**:
  - `id` (string): Confluence page ID
- **Query Parameters**:
  - `purge` (boolean, default `false`): Permanently delete rather than move to trash
  - `draft` (boolean, default `false`): Delete the draft version
- **Response**: `204 No Content`

---

## Confluence Spaces

### List Spaces

- **Endpoint**: `GET /confluence/spaces`
- **Response**: `SpacesResponse`

### Get a Space

- **Endpoint**: `GET /confluence/spaces/{id}`
- **Path Parameters**:
  - `id` (string): Confluence space ID
- **Response**: `Space` object

### Create a Space

- **Endpoint**: `POST /confluence/spaces`
- **Request Body**: `Space` object
- **Response**: Created `Space`

---

## Health and Monitoring

### Health Check

- **Endpoint**: `GET /actuator/health`
- **Response**: Application health status
- **Use Case**: Monitoring, load balancer health checks

---

## Chat Attachments

Images attached to a chat message. An attachment is uploaded **before** the message exists — it is
staged against the uploading user, then claimed by a message when the client names its id in
`ChatRequest.attachmentIds`. Staged attachments that are never sent are swept after
`solesonic.llm.attachment.staged-ttl`.

Accepted content types: `image/png`, `image/jpeg`, `image/gif`, `image/webp`. Upload size is bounded
by `spring.servlet.multipart.max-file-size`.

### Upload an Attachment

- **Endpoint**: `POST /attachments`
- **Consumes**: `multipart/form-data`
- **Form Parameters**:
  - `file` (required): the image
  - `description` (optional): free text describing what the image contains
- **Response**: `201 Created`, `Location` header, and the attachment summary

```json
{
  "id": "3f9a...",
  "chatMessageId": null,
  "fileName": "screenshot.png",
  "description": "the login screen",
  "contentType": "image/png",
  "fileSizeBytes": 20481,
  "described": false,
  "descriptionFailureReason": null
}
```

`chatMessageId` stays `null` until a message claims the attachment.

`description` is the note the uploader supplied — never model output. `described` says whether the
vision model produced a description of the image; it is `false` on a freshly staged attachment,
because the vision pass runs on the turn the attachment is sent. `descriptionFailureReason` carries
the same closed set of values as the [`attachment` stream event](#attachment-event-payload), and is
`null` both when the image was described and when it has not been through the vision pass yet. The
description text itself is not exposed: it is a paragraph of prose per image.

### Download an Attachment

- **Endpoint**: `GET /attachments/{attachmentId}`
- **Response**: raw image bytes with the stored content type, a strong `ETag`, and a long-lived
  `Cache-Control` — bytes never change once written, so repeat history loads revalidate rather than
  re-download.

### Delete an Attachment

- **Endpoint**: `DELETE /attachments/{attachmentId}`
- **Response**: `204 No Content`

Works whether or not the attachment has been claimed by a message. Deleting a claimed attachment
leaves the message itself intact.

### Attachments in Chat History

`GET /chats/{chatId}` and `GET /chats/users/{userId}` return each `ChatMessage` with an
`attachments` array of the same summary shape. Bytes are not included; fetch them from
`GET /attachments/{attachmentId}`.

`described` is the durable half of the `attachment` stream event: it survives a reload, so an old
conversation can still show that an image the assistant answered around was never actually read.

### Generated Images in Chat History

The same endpoints return each `ChatMessage` with a `generatedImages` array of
`GeneratedImageSummary` — the durable half of the [`image` stream event](#image-event-payload). An
assistant turn that generated no images carries an empty array.

This is what makes a reloaded conversation render its images without regenerating them. Bytes are
not included; fetch them from `GET /images/{imageId}`.

---

## Image Generation

Text-to-image generation, backed by the `generate_image` MCP tool. The prompt is the whole input
surface: size (1024x1024), step count, and the seed are fixed by the image server and are not
caller-tunable.

The call travels on the caller's own identity, so the user's JWT must carry the
`mcp-generate-image` role. A token without it comes back as `FORBIDDEN`.

There are two ways in, and the model never sees an image on either. The tool returns roughly 2MB of
base64, which the API decodes once, stores, and replaces with a reference; nothing downstream carries
the bytes.

1. **Explicit** — `POST /images`, below. The model is not involved at all.
2. **In a conversation** — the `/generate_image` slash command, or the model calling the tool
   itself. The image is intercepted out of the tool result before that result re-enters the model's
   context, and reaches the client as an [`image` stream event](#image-event-payload) and as
   `message.generatedImages` on [`done`](#stream-event-types). It is persisted against the assistant
   turn, so [history](#generated-images-in-chat-history) renders it without regenerating.

### Generate an Image (streaming)

- **Endpoint**: `POST /images`
- **Produces**: `text/event-stream`
- **Body**: `{ "prompt": "a lighthouse on a cliff in a storm, dramatic lighting, photorealistic" }`

The stream carries any number of `progress` frames followed by exactly one terminal frame, either
`complete` or `error`.

```
event: progress
data: {"percent":15,"message":"Queued as 4f1c8e2a-..."}

event: progress
data: {"percent":85,"message":"Generating…"}

event: complete
data: {"imageId":"7c2f...","chatMessageId":null,"imageUrl":"/izzybot/images/7c2f...","prompt":"a lighthouse ...",
       "model":"FLUX.1-schnell","seed":8339331079448168597,"width":1024,"height":1024,"steps":4,
       "elapsedSeconds":8.2,"fileSizeBytes":1502931,"created":"2026-07-31T16:40:14Z"}
```

`percent` is **approximate**. It is monotonic — it never goes backwards — but between 15 and 85 it
is derived from an expected duration rather than real per-step progress, so it can sit at 85 for a
while on a slow run. Show the `message` text; treat the number as a hint. It is `null` on a frame
that carried no total.

Closing the stream does not cancel the generation. The image is still produced and stored, and can
be fetched by id afterwards.

Typical latency is 5-15 seconds; the hard deadline is 180 seconds.

### Generate an Image (non-streaming)

- **Endpoint**: `POST /images/sync`
- **Response**: `201 Created`, `Location` header, and the same body as the `complete` frame

For scripts and tests. It blocks for the whole generation and reports failure as a status code
rather than as an in-band frame. Its own path rather than the streaming one negotiated by `Accept`,
because a client accepting any media type would match both handlers ambiguously.

### Download a Generated Image

- **Endpoint**: `GET /images/{imageId}`
- **Auth**: same bearer token as every other endpoint
- **Response**: raw image bytes, a strong `ETag` (the SHA-256 of the bytes), and a long-lived
  `Cache-Control` — an image never changes once written

Readable only by the user who generated it. The lookup is user-scoped, so another user's image is
**`404`, not `403`** — deliberately, so the endpoint does not confirm that an id exists to someone
who cannot read it.

Because it requires a header, a bare `<img src>` will not work: fetch with the token and hand the
result to the DOM as a blob URL. There are no pre-signed URLs — these are user-generated from
free-text prompts, and an unauthenticated URL would be a capability that outlives the session.

`imageUrl` on every payload is **context-relative** (`/izzybot/images/<uuid>`), not absolute, so it
stays correct behind a proxy that rewrites the host.

### Generated Image Metadata

- **Endpoint**: `GET /images/{imageId}/metadata`
- **Response**: the same body as the `complete` frame, without the bytes

For rendering an image that arrived long before the current page load. `prompt` and `seed` together
are the provenance record: the prompt is the image's `alt` text, and the seed is what lets someone
say *this specific image* when reporting a problem. Every field except `imageId`, `imageUrl`,
`prompt`, `fileSizeBytes`, and `created` may be `null` — the image server reports its metadata as a
text block, and an unparsed field costs a null rather than a failed generation.

### Image Generation Errors

Failures collapse onto a closed set of codes. The message is always user-safe; the underlying detail
(including the image server's internal prompt id) is logged rather than returned.

| Code | Status on `/images/sync` | Meaning |
|---|---|---|
| `INVALID_PROMPT` | `400` | The prompt was empty or the tool rejected it |
| `FORBIDDEN` | `403` | The token does not carry `mcp-generate-image` |
| `RATE_LIMITED` | `429` | Too many generations already in flight — retry |
| `BACKEND_UNAVAILABLE` | `503` | The MCP server or the image backend behind it failed |
| `GENERATION_TIMEOUT` | `504` | Generation did not finish within the image server's deadline |
| `INTERNAL` | `500` | Anything else |

On the streaming endpoint the same payload arrives as the terminal `error` frame, since the response
status is already committed by the time a generation can fail:

```
event: error
data: {"code":"GENERATION_TIMEOUT","message":"Image generation is taking longer than expected. Please try again."}
```

---

## Error Handling

### Standard HTTP Status Codes

- `200 OK` - Successful request
- `201 Created` - Resource created successfully
- `204 No Content` - Successful request with no response body
- `400 Bad Request` - Invalid request format or parameters
- `401 Unauthorized` - Authentication required or invalid token
- `403 Forbidden` - Access denied for the requested resource
- `404 Not Found` - Resource not found
- `409 Conflict` - An attachment named in `attachmentIds` was already sent on another message
- `413 Content Too Large` - Upload exceeds the configured multipart limit
- `415 Unsupported Media Type` - Attachment content type is not an accepted image type
- `429 Too Many Requests` - Too many image generations already in flight
- `500 Internal Server Error` - Server error
- `503 Service Unavailable` - The image generation backend is unreachable or failed
- `504 Gateway Timeout` - Image generation did not finish within the image server's deadline

---

## Related Documentation

- **Getting Started**: [docs/getting-started.md](getting-started.md)
- **Configuration**: [docs/configuration.md](configuration.md)
- **Security**: [docs/security.md](security.md)
- **MCP Integration**: [docs/mcp-integration.md](mcp-integration.md)
- **Elicitation**: [docs/elicitation.md](elicitation.md)
- **Troubleshooting**: [docs/troubleshooting.md](troubleshooting.md)
