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

### Start Streaming Chat

- **Endpoint**: `POST /streaming/chats/users/{userId}`
- **Produces**: `text/event-stream`
- **Path Parameters**:
  - `userId` (UUID): The user starting the chat
- **Request Headers**:
  - `Last-Event-ID` (optional): Resume the stream from a specific SSE event ID
- **Request Body**: `ChatRequest`

### Continue Streaming Chat

- **Endpoint**: `PUT /streaming/chats/{chatId}/users/{userId}`
- **Produces**: `text/event-stream`
- **Path Parameters**:
  - `chatId` (UUID): The existing chat session to continue
  - `userId` (UUID): The user continuing the chat
- **Request Headers**:
  - `Last-Event-ID` (optional): Resume from a specific SSE event ID
- **Request Body**: `ChatRequest`

### Stream Event Types

Both streaming endpoints emit the following SSE event types:

| Event | Description |
|-------|-------------|
| `init` | Sent at stream start. Payload carries the persisted user message id — see below |
| `chunk` | Incremental assistant response text |
| `progress` | A long-running step started — an MCP tool, or the vision pass on one attached image |
| `attachment` | Terminal outcome for one attached image — see below |
| `elicitation` | Interactive form request from an MCP tool |
| `cancel` | Emitted when a user cancels an elicitation |
| `done` | Final event containing the structured chat response |

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
- **Response**: Array of chat objects

### Get a Specific Chat

- **Endpoint**: `GET /chats/{chatId}`
- **Path Parameters**:
  - `chatId` (UUID): The chat session to retrieve
- **Response**: Complete chat object with message history

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
- `500 Internal Server Error` - Server error

---

## Related Documentation

- **Getting Started**: [docs/getting-started.md](getting-started.md)
- **Configuration**: [docs/configuration.md](configuration.md)
- **Security**: [docs/security.md](security.md)
- **MCP Integration**: [docs/mcp-integration.md](mcp-integration.md)
- **Elicitation**: [docs/elicitation.md](elicitation.md)
- **Troubleshooting**: [docs/troubleshooting.md](troubleshooting.md)
