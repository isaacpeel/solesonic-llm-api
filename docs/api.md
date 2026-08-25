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
| `done` | Final event containing the structured chat response — see below |

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

### done Event Payload

```json
{
  "id": "0a4b...",
  "message": {
    "id": "7f3c...",
    "chatId": "0a4b...",
    "messageType": "ASSISTANT",
    "message": "...",
    "model": "qwen2.5:7b",
    "generatedImages": []
  },
  "responseMetadata": {
    "promptTokens": 412,
    "completionTokens": 128,
    "totalTokens": 540,
    "tokensPerSecond": 34.7,
    "durationMillis": 3690
  }
}
```

`responseMetadata` covers the whole turn — from just after the user message is persisted to the
last byte of the assistant response — not only model inference time. `durationMillis` is wall-clock
and always present. `promptTokens`/`completionTokens`/`totalTokens`/`tokensPerSecond` are `null`
whenever the turn never called a token-reporting chat model at all: an A2A agent delegation has
nothing to report. `tokensPerSecond` is `completionTokens` divided by `durationMillis`, so it is the
turn's effective throughput, not the model's raw generation speed — time spent on retrieval, tool
calls, or vision description before the model starts writing lowers it the same as slow generation
would.

A cancelled turn's `done` carries no `responseMetadata` (`null`): the assistant message it
accompanies is replaced with a fixed "Chat canceled." notice, and a token count against that
discarded content would be misleading.

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
  "reason": null,
  "indexed": false,
  "extractionReason": null,
  "chunkCount": null
}
```

One event shape covers both kinds of attachment, because a client renders one attachment chip either
way. An **image** moves `described`/`reason` and leaves the document fields empty. A **document**
moves `indexed`/`extractionReason`/`chunkCount` and leaves `described` at `false` with a `null`
`reason`.

The attachment pass opens with a `progress` event per attachment and closes with an `attachment`
event per attachment. Guarantees a client can rely on:

- **Exactly one `attachment` event per id in `ChatRequest.attachmentIds`** — including attachments
  skipped before any work started, and ones the server could not resolve at all. A client never
  has to interpret a missing event.
- **Always before `done`**, so the event lands while the assistant message is still streaming.

Nothing else in the turn distinguishes a handled attachment from a skipped one: a skipped attachment
still produces a normal answer, just one written as though nothing were attached.

When `described` is `false` on an image, `reason` is one of a closed set:

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

When `indexed` is `false` on a document, `extractionReason` is one of its own closed set:

| Reason | Meaning |
|--------|---------|
| `DOCUMENT_TOO_LARGE` | The document exceeds `solesonic.llm.attachment.document.max-size-bytes` |
| `DOCUMENT_UNREADABLE` | The file could not be parsed, or parsed to no text — an encrypted PDF, or a scan carrying images rather than text |
| `EMBEDDING_UNAVAILABLE` | The embedding model could not be reached, so the extracted text could not be indexed |
| `EXCEEDED_DOCUMENT_LIMIT` | More documents were attached to one message than the extraction pass indexes |

`chunkCount` says how many retrievable chunks the document became, and is `null` whenever `indexed`
is `false`. The durable form of this signal is `indexed` on the attachment summary in chat history.

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
  - `ungrouped` (boolean, default `false`): Return only the conversations that are not filed under a
    [group](#conversation-groups)
- **Response**: A page of chat objects — hand-placed conversations first, in the order the user
  arranged them, then everything else newest first (`timestamp` descending, `id` as a tiebreaker).
  See [Move a Chat](#move-a-chat)

`ungrouped` is opt-in, and omitting it returns every chat the user owns, grouped ones included — what
every existing client already receives. It exists for a client that renders group sections above this
list: without it, every grouped conversation appears twice, and filtering them out client-side leaves
`totalElements` and `totalPages` describing more rows than the client will render, so an infinite
scroll stalls whenever a whole page filters away to nothing. With `ungrouped=true` the counters cover
only the ungrouped chats, and the page shape, message hydration, and ordering rule are otherwise
identical.

Parameters are bounded rather than rejected: a negative `page` is treated as `0`, a `size` above the
`spring.data.web.pageable.max-page-size` limit is capped at it, and scrolling past the last page
returns an empty `content` array instead of an error. A `sort` parameter is accepted by the resolver
but ignored: the ordering is fixed by the repository query, so that pages cannot overlap or skip a chat.

```json
{
  "content": [
    {
      "id": "...",
      "userId": "...",
      "timestamp": "...",
      "name": "Trip planning",
      "chatGroupId": null,
      "sortOrder": null,
      "groupSortOrder": null,
      "chatMessages": []
    }
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

### Move a Chat

- **Endpoint**: `PUT /chats/{chatId}/order`
- **Path Parameters**:
  - `chatId` (UUID): The conversation to move
- **Request Body**: `ChatOrderRequest` — `{ "position": 0 }`
- **Response**: The updated chat object, carrying its new `sortOrder`

Moves a conversation within the caller's whole list, independently of any group it is filed under —
a move here never disturbs a group's ordering, and a move inside a group never disturbs this one.

`position` is a **zero-based index among the conversations that have already been placed by hand**,
which are the prefix of the list. Everything else follows in `timestamp` order. This is what keeps
manual ordering additive: a conversation nobody has moved sorts exactly as it did before, and a
newly created one still appears at the top of the timestamp-ordered part rather than at the bottom
of the list.

| Body | Effect |
|---|---|
| `{"position": 0}` | Move to the head of the list |
| `{"position": n}` | Move to index `n`; a position past the end of the placed conversations appends, since a drag into the timestamp-ordered part means "last" |
| `{"position": null}` | Unplace the conversation — it returns to `timestamp` ordering |

A move renumbers the placed conversations densely from zero. **Do not treat `sortOrder` as an index
into the rendered list**: deleting a placed conversation, or moving one out of a group, leaves a gap
that stays until the next move closes it, so the values can read `0, 1, 3`. Only their relative
order is meaningful. To place something *after* the chat currently showing `3`, send the target's
index in the list you are rendering, not `4`.

A negative position is `400`. A chat that does not exist, or is not owned by the caller, is `404`.

A `PUT` because it is idempotent: sending the same position twice leaves the list in the same
arrangement.

### Delete a Chat

- **Endpoint**: `DELETE /chats/{chatId}`
- **Path Parameters**:
  - `chatId` (UUID): The conversation to delete
- **Response**: `204 No Content`

Deletes the conversation and everything stored under it — every message, the attachments bound to
those messages, and the images generated inside it — as one transaction. Nothing is recoverable
afterwards, and there is no soft-delete or trash. Attachments the caller uploaded but never sent are
untouched; they belong to no conversation and are swept on their own schedule.

A chat that does not exist, or is not owned by the caller, is `404`, so a repeated delete is `404`
rather than `204`. Groups are unaffected: deleting the last conversation in a group leaves an empty
group, not a deleted one.

Deleting a conversation does not cancel a turn that is already streaming. Generation is deliberately
independent of any listener, so a turn in flight runs to completion and writes a message that lands
on a conversation that no longer exists — unreachable from every read path, but written. Wait for
`done` before deleting.

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
  "sortOrder": null,
  "timestamp": "2026-08-23T16:40:14Z"
}
```

The name is trimmed. A blank name or one over 255 characters is `400`. Names are not required to be
unique — two groups may share one, and are ordered against each other by id so a listing never
reshuffles.

A new group starts unplaced — `sortOrder` is `null` — so it is listed by name until a client places
it with [Update a Group](#update-a-group).

### Update a Group

- **Endpoint**: `PUT /chatgroups/{chatGroupId}`
- **Request Body**: `ChatGroup` — the group itself, not a request record:
  `{ "name": "...", "sortOrder": 0 }`
- **Response**: The updated group

A pure update of the two fields a group's owner controls: its `name` and its `sortOrder`, the rank it
holds among the caller's sections. The body is the same object the group is returned as, so `id`,
`userId` and `timestamp` may be sent and are ignored — all three are read-only on the wire, and
ownership comes from the bearer token while the id comes from the path.

**A full update, not a patch.** Both writable fields are taken as sent, so a body that omits
`sortOrder` unplaces the group and returns it to name ordering. Send the group as you want it to end
up, not the part of it you changed.

`sortOrder` is a **rank, not an index** — it is stored exactly as sent, and unlike a chat's position
no other row is renumbered. Gaps and duplicates are both legal; a listing breaks a tie by name and
then by id, so two groups sharing a rank never swap places between requests. A client rearranging
several sections states each one with its own `PUT`. `null` unplaces the group. A negative rank is
`400`.

The same name validation `create` applies: the name is trimmed, and a blank one or one over 255
characters is `400`. Names stay non-unique — giving a group a name another group already carries is a
success.

Ownership is resolved before the body is validated, so a bad name or a negative rank sent for a group
the caller does not own is `404` rather than `400`: the endpoint does not tell a caller the difference
between "your body was wrong" and "that group is not yours". Nothing else about the group changes —
its membership, the sort orders of the chats filed under it, and its `timestamp` are all untouched.

### List Groups

- **Endpoint**: `GET /chatgroups`
- **Response**: Every group the caller owns — hand-placed sections first by `sortOrder`, then the
  rest by name, with the id as a final tiebreaker

A group nobody has placed sorts by name exactly as it did before ordering existed, which is what
keeps a newly created one among those rather than at the top of the arrangement.

### Get a Group

- **Endpoint**: `GET /chatgroups/{chatGroupId}`
- **Response**: The group

### Get the Conversations in a Group

- **Endpoint**: `GET /chatgroups/{chatGroupId}/chats`
- **Query Parameters**:
  - `page` (int, default `0`): Zero-based page index
  - `size` (int, default `20`, max `100`): Page size
- **Response**: A page of chat objects, in the same shape and ordered on the same rule as
  [`GET /chats/users/{userId}`](#get-all-chats-for-a-user) — hand-placed conversations first, then
  the rest newest first, with messages hydrated. The position read here is `groupSortOrder`, the
  group's own ordering, not the `sortOrder` the whole list uses. A `sort` parameter is accepted by
  the resolver but ignored, for the same reason it is there.

### Add a Conversation to a Group

- **Endpoint**: `PUT /chatgroups/{chatGroupId}/chats/{chatId}`
- **Response**: `204 No Content`

A `PUT` because it is idempotent: filing a conversation that is already in this group is a success,
and leaves the position it holds there alone. Filing one that is in another group moves it, since a
chat carries at most one group, and clears its `groupSortOrder` — a position in the group it just
left describes nothing.

### Move a Conversation within a Group

- **Endpoint**: `PUT /chatgroups/{chatGroupId}/chats/{chatId}/order`
- **Request Body**: `ChatOrderRequest` — `{ "position": 0 }`
- **Response**: The updated chat object, carrying its new `groupSortOrder`

The same rules as [Move a Chat](#move-a-chat) — zero-based index among the placed conversations,
`null` to unplace, `400` on a negative position, and the same warning against reading
`groupSortOrder` as an index — applied to this group's own ordering. The conversation's place in the
caller's whole list is untouched.

A chat that is not in this group is `404`: a position in a group the conversation is not filed under
describes nothing, and accepting one would leave the client's picture of the sidebar wrong.

### Remove a Conversation from a Group

- **Endpoint**: `DELETE /chatgroups/{chatGroupId}/chats/{chatId}`
- **Response**: `204 No Content`

Ungroups the conversation; the chat and its messages are untouched, and its `groupSortOrder` is
cleared along with the membership. A chat that is not in this group is `404` rather than a silent
success — the client's picture of where the conversation lives is wrong, and reporting the removal
as done would leave it wrong.

To delete the conversation itself rather than unfile it, use
[`DELETE /chats/{chatId}`](#delete-a-chat). To delete the group rather than one of its members, use
[`DELETE /chatgroups/{chatGroupId}`](#delete-a-group) — the two live on adjacent paths and are the
pair most easily confused.

### Delete a Group

- **Endpoint**: `DELETE /chatgroups/{chatGroupId}`
- **Path Parameters**:
  - `chatGroupId` (UUID): The group to delete
- **Response**: `204 No Content`

**Deletes the section, never the conversations filed under it.** Every chat in the group survives and
becomes ungrouped: `chatGroupId` and `groupSortOrder` are both cleared, since a position inside a
group that no longer exists describes nothing. Each chat's `sortOrder` — its place in the caller's
*whole* list — is left alone; the two orderings are independent, and a deleted group says nothing
about the sidebar.

All of it is one transaction, so the group is gone and its chats are ungrouped, or nothing happened.

A group that does not exist, or is not owned by the caller, is `404`, so a repeated delete is `404`
rather than `204` — the same rule [`DELETE /chats/{chatId}`](#delete-a-chat) follows. Other groups are
unaffected.

Not to be confused with [`DELETE /chatgroups/{chatGroupId}/chats/{chatId}`](#remove-a-conversation-from-a-group),
which unfiles a single conversation and leaves the group standing.

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

### Retrieval scope

Everything in the vector store carries a `scope` in its metadata, and a chat turn searches the three
scopes in order of precedence — **conversation, then user, then global** — over a shared result
budget. The most specific scope takes what it can, the next takes what is left. That means a
document attached to the conversation is both preferentially included and ranked ahead of the shared
knowledge base, rather than competing with it on similarity alone.

| Scope | Retrievable by | Written by |
|-------|----------------|------------|
| `GLOBAL` | every user, in every conversation | `POST /documents/data/upload` (the default), URI ingestion, Confluence ingestion |
| `USER` | one user, in all of their conversations | `POST /documents/data/upload?scope=USER` |
| `CHAT` | one conversation | attaching a document to a chat message |

Documents ingested before scoping existed are `GLOBAL`, which is what they already effectively were.

### Upload a Document

- **Endpoint**: `POST /documents/data/upload`
- **Request**: `multipart/form-data` with a `file` field
- **Query Parameters**:
  - `scope` (optional, default `GLOBAL`): `GLOBAL` to share the document with every user, or `USER`
    to keep it to the caller. `CHAT` is rejected with `400` — attach the document to a message
    instead.
- **Description**: Queues a document for processing and ingestion into the vector store. Supported formats include PDF and plain text.
- **Response**: `201 Created` with a `Location` header pointing to the queued training document record

De-duplication by file name applies only between `GLOBAL` documents. Two users uploading `notes.pdf`
at `USER` scope get two separate documents.

### Vector Search

- **Endpoint**: `POST /documents/data/search`
- **Request Body**: `VectorSearch` containing the query text
- **Response**: Array of matching document text excerpts ranked by similarity

### List Training Documents

- **Endpoint**: `GET /trainingdocuments`
- **Response**: Array of `TrainingDocument` objects, including processing status (`QUEUED`, `PROCESSING`, `COMPLETED`, `FAILED`)

---

## User Preferences

User preferences control per-user settings such as which Ollama model to use for chat and the
similarity thresholds for RAG retrieval — one per retrieval tier (`chatSimilarityThreshold`,
`userSimilarityThreshold`, `globalSimilarityThreshold`), each optional: a null value falls back to
that tier's system default.

Stored OAuth tokens are **never serialized** on any of these endpoints. Connection state travels as
two booleans instead — `atlassianAuthentication` and `googleAuthentication` — which is what a client
should read to decide whether to show "connected" or a "connect" link:

```json
{
  "userId": "user-uuid-here",
  "model": "qwen3.5:9b",
  "chatSimilarityThreshold": 0.5,
  "userSimilarityThreshold": 0.75,
  "globalSimilarityThreshold": 0.75,
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

Images and documents attached to a chat message. An attachment is uploaded **before** the message
exists — it is staged against the uploading user, then claimed by a message when the client names
its id in `ChatRequest.attachmentIds`. Staged attachments that are never sent are swept after
`solesonic.llm.attachment.staged-ttl`.

Upload size is bounded by `spring.servlet.multipart.max-file-size`. Accepted content types fall into
two groups, and which group a file lands in decides how the assistant reads it:

**Images** — `image/png`, `image/jpeg`, `image/gif`, `image/webp` — are described by a vision model,
and the description is put into the prompt as prose.

**Documents** — `application/pdf`, `text/plain`, `text/markdown`, `text/html`, `text/csv`,
`text/xml`, `application/xml`, `application/json`, `application/rtf`, the Microsoft Office types
(`application/msword`, `application/vnd.openxmlformats-officedocument.*`,
`application/vnd.ms-excel`, `application/vnd.ms-powerpoint`) and OpenDocument text/spreadsheet — are
extracted to text, split, and embedded into the vector store at **conversation scope**. Their
contents reach the model through retrieval rather than being pasted into the prompt, so a long
document costs context only for the passages that bear on the question. The model is told which
documents were attached; it is not shown them in full.

A document is indexed once, on the turn it is first sent, and stays retrievable for the rest of the
conversation. Deleting the attachment, or the chat, deletes its chunks with it.

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
  "descriptionFailureReason": null,
  "indexed": false,
  "extractionFailureReason": null
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
`indexed` is the same signal for a document — whether its text is actually retrievable, or whether
the assistant has been answering without it.

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
