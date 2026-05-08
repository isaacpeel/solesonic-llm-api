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
| `init` | Initialization marker sent at stream start |
| `chunk` | Incremental assistant response text |
| `elicitation` | Interactive form request from an MCP tool |
| `cancel` | Emitted when a user cancels an elicitation |
| `done` | Final event containing the structured chat response |

### ChatRequest Body

```json
{
  "chatMessage": "Your message here",
  "model": "qwen2.5:7b"
}
```

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

## Error Handling

### Standard HTTP Status Codes

- `200 OK` - Successful request
- `201 Created` - Resource created successfully
- `204 No Content` - Successful request with no response body
- `400 Bad Request` - Invalid request format or parameters
- `401 Unauthorized` - Authentication required or invalid token
- `403 Forbidden` - Access denied for the requested resource
- `404 Not Found` - Resource not found
- `500 Internal Server Error` - Server error

---

## Related Documentation

- **Getting Started**: [docs/getting-started.md](getting-started.md)
- **Configuration**: [docs/configuration.md](configuration.md)
- **Security**: [docs/security.md](security.md)
- **MCP Integration**: [docs/mcp-integration.md](mcp-integration.md)
- **Elicitation**: [docs/elicitation.md](elicitation.md)
- **Troubleshooting**: [docs/troubleshooting.md](troubleshooting.md)
