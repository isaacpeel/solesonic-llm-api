# Elicitation Guide

This document explains the interactive elicitation feature used to gather structured input from users during an LLM conversation. It covers architecture, flow, API endpoints, configuration, and implementation examples.

## Overview

Elicitation is an interactive request for structured input from the user while an LLM conversation is streaming. Instead of relying on free‑form replies, the backend emits an elicitation event via Server‑Sent Events (SSE), the frontend renders an appropriate form, and then submits the results back. This enables safer, more reliable tool invocation (e.g., confirmations, delete prompts, form fields) without breaking the streaming experience.

Key benefits:
- Real‑time prompts rendered by the frontend while streaming continues
- Clear accept/decline/cancel semantics
- Structured results for tool execution and auditing
- Resumable stream with `Last-Event-ID`

## Architecture

The elicitation flow is orchestrated by `ElicitationService` and driven by an MCP provider:

- `ElicitationService` (server)
  - Manages chat SSE streams and emits `elicitation` events
  - Tracks pending elicitations and submitted results
  - Converts frontend fields into `StructuredElicitResult` used downstream
  - Handles cancellation and timeout

- `ElicitationProvider` (MCP bridge)
  - Receives `McpSchema.ElicitRequest` from MCP servers (`@McpElicitation`)
  - Prepares the elicitation with `ElicitationService.prepareElicitation()`
  - Emits the request with `ElicitationService.emitElicitation()`
  - Awaits the result using `ElicitationService.awaitResultAsync()` and returns a `StructuredElicitResult`

SSE is used for real‑time updates to the frontend. The chat streaming service merges regular LLM chunks and elicitation events on the same stream.

## Core Concepts

### ElicitationHandle

`ElicitationService.prepareElicitation(UUID chatId, String name)` returns an `ElicitationHandle` containing:
- `elicitationId` — the unique ID for this elicitation
- `future` — completed when the frontend submits a result

Lifecycle:
1. Prepare a new elicitation, optionally with a `name`
2. Emit the elicitation request on the chat’s SSE stream
3. Frontend submits a response
4. Awaited future completes and result is propagated to the MCP tool caller

### Pending elicitations

`ElicitationService` maintains concurrent maps:
- `pendingById` — composite key per chat and elicitation ID → `CompletableFuture<ElicitResult>`
- `nameIndex` — composite key per chat and elicitation name → elicitation ID (supports named elicitations)
- `resultFieldsById` — raw submitted fields per elicitation used to construct structured results

### Frontend ↔ Backend communication

- Backend emits an `elicitation` SSE event with the request payload, plus `elicitationId` and `chatId`
- Frontend renders UI, collects user input, and submits response via REST (`POST /streaming/chats/{chatId}/{elicitationId}/elicitation-response`)
- On user cancellation, backend emits a `cancel` SSE event; the streaming chat composes a final `done` event

### Named vs ID‑based elicitations

When `name` is provided during `prepareElicitation`, the frontend may submit results using the `name` (as included in the request payload), even if the `elicitationId` isn’t available in the UI. The backend maintains a `nameIndex` to resolve the effective elicitation ID.

## API Endpoints

All paths are relative to the application context path (e.g., `/${BASE_URI}`) and secured per deployment profile.

### Streaming Chat (SSE)

- POST `/streaming/chats/users/{userId}`
  - Starts a streaming chat session for a user and returns an SSE stream of events
  - Request body: `ChatRequest`
  - Headers: optional `Last-Event-ID` to resume from a specific SSE id
  - Events on the stream:
    - `init` — initialization marker
    - `chunk` — assistant text chunks
    - `elicitation` — an elicitation request (see payload below)
    - `cancel` — emitted when user cancels an elicitation
    - `done` — final chat result for that turn

- PUT `/streaming/chats/{chatId}/users/{userId}`
  - Continues an existing streaming chat; same event types and headers as above

#### Elicitation event payload

Event name: `elicitation`

Example payload (subset of `McpSchema.ElicitRequest`):
```json
{
  "id": "...",           
  "type": "form",
  "name": "delete-confirmation",
  "message": "Delete this item?",
  "meta": { "chatId": "<uuid>" },
  "elicitationId": "<uuid>",
  "chatId": "<uuid>"
}
```

Event name: `cancel`

Payload: the string "cancel".

### Submit Elicitation Response

- POST `/streaming/chats/{chatId}/{elicitationId}/elicitation-response`
  - Submits the user’s form result or action
  - Request body:
    ```json
    {
      "elicitationResponse": {
        "name": "delete-confirmation",     
        "fields": {                         
          "confirmed": "accept"           
        },
        "action": "accept"                 
      }
    }
    ```
  - Notes:
    - `action` may be `accept`, `decline`, or `cancel`
    - If `action` is `decline`, `fields` are ignored by the backend
    - If `name` is provided, backend may resolve the elicitation by name (in addition to the path ID)
  - Responses:
    - `200 OK` — accepted and delivered to the waiting operation
    - `400 Bad Request` — invalid payload
    - `404 Not Found` — no matching pending elicitation

## User Flows

### 1) Initiated by MCP server
1. MCP tool calls `ElicitationProvider.handleElicitationRequest()` with an `ElicitRequest` containing `meta.chatId`
2. Provider prepares and emits the elicitation via `ElicitationService`
3. Frontend receives `elicitation` SSE event and shows a form

### 2) Frontend form submission
1. User completes the form and chooses an action: `accept`, `decline`, or `cancel`
2. Frontend POSTs to `/streaming/chats/{chatId}/{elicitationId}/elicitation-response`
3. Backend completes the pending future; on `cancel`, a `cancel` SSE event is also emitted for the active chat

### 3) Result handling and completion
1. `ElicitationService.awaitResultAsync()` resolves to a `StructuredElicitResult`
2. The MCP tool resumes with the structured outcome
3. The streaming chat either continues or completes with `done`

### 4) Cancellation and timeout
- Cancellation: Frontend indicates `cancel`; backend emits `cancel` SSE and completes with `CANCEL` action
- Timeout: Controlled by `solesonic.elicitation.timeout-seconds` (default 600). On timeout, backend returns `DECLINE` and cleans up maps

## Configuration

Property (application*.properties):
- `solesonic.elicitation.timeout-seconds` — Max seconds to wait for a frontend response (default: 600)

Environment variable mapping (Spring relaxed binding examples):
- `SOLESONIC_ELICITATION_TIMEOUT_SECONDS=600`

## Best Practices for Clients

- Always listen for both `elicitation` and `cancel` events on the SSE stream
- If the page reloads or network blips, resume with the `Last-Event-ID` header
- Use `name` from the elicitation payload if you don’t have `elicitationId` bound to the UI controls
- Submit only the fields requested plus a clear `action`: `accept`, `decline`, or `cancel`
- Treat `decline` as a neutral outcome; the operation will not proceed
- Show clear UI affordances for cancel/decline to avoid ambiguous submissions

## Examples

### TypeScript (Browser) — listen and submit

```typescript
type ElicitationEvent = {
  name?: string;
  message?: string;
  meta?: Record<string, unknown> & { chatId?: string };
  elicitationId: string;
  chatId: string;
};

function openChatStream(baseUrl: string, userId: string, jwt?: string) {
  const url = `${baseUrl}/streaming/chats/users/${userId}`;
  const eventSource = new EventSource(url, { withCredentials: false });

  eventSource.addEventListener('elicitation', (raw) => {
    const event = raw as MessageEvent<string>;
    const payload = JSON.parse(event.data) as ElicitationEvent;
    // Render your form using payload.name/message/fields (if present)
    console.log('Elicitation received', payload);
  });

  eventSource.addEventListener('cancel', () => {
    console.log('Elicitation canceled by user');
  });

  return eventSource;
}

async function submitElicitation(
  baseUrl: string,
  chatId: string,
  elicitationId: string,
  name: string,
  action: 'accept' | 'decline' | 'cancel',
  fields?: Record<string, unknown>
) {
  const body = {
    elicitationResponse: {
      name,
      fields,
      action
    }
  };

  const response = await fetch(
    `${baseUrl}/streaming/chats/${chatId}/${elicitationId}/elicitation-response`,
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body)
    }
  );

  if (!response.ok) {
    throw new Error(`Submit failed: ${response.status}`);
  }
}
```

### Python — submit form result

```python
import requests

def submit_elicitation(base_url: str, chat_id: str, elicitation_id: str, name: str, action: str, fields: dict | None = None):
    payload = {
        "elicitationResponse": {
            "name": name,
            "fields": fields,
            "action": action
        }
    }

    resp = requests.post(
        f"{base_url}/streaming/chats/{chat_id}/{elicitation_id}/elicitation-response",
        json=payload,
        headers={"Content-Type": "application/json"}
    )
    resp.raise_for_status()
    return True
```

## Integration with MCP Servers

- Provider: `com.solesonic.mcp.client.elicitation.ElicitationProvider`
  - Annotation: `@McpElicitation(clients = {"solesonic","mcp-client - solesonic"})`
  - Bridges MCP `ElicitRequest` to the application’s elicitation flow
  - Returns `StructuredElicitResult<DeleteConfirmation>` where:
    - `action` ∈ { `ACCEPT`, `DECLINE`, `CANCEL` }
    - `DeleteConfirmation` fields include `confirmed` (boolean) and `chatId` (string)

## Error Handling

Common cases:
- 400 invalid body for submit endpoint (missing `elicitationResponse` or invalid types)
- 404 unknown or completed elicitation
- Timeout → treated as `DECLINE` with cleanup

## Performance & Security

- SSE streams are per chat; the streaming service merges LLM chunks, elicitation events, and cancellation
- Use CORS settings appropriate for your frontend origin(s)
- Do not expose internal IDs beyond what is necessary for the UI to submit results
- Avoid storing sensitive input in logs; application logs record high‑level actions

## Related Documentation

- [API](api.md)
- [Configuration](configuration.md)
- [MCP Integration](mcp-integration.md)
- [Getting Started](getting-started.md)
