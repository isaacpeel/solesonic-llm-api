# Elicitation Guide

Elicitation lets an MCP tool pause mid-call and ask the user a structured question — a confirmation,
a choice, a small form — while the conversation is still streaming. This document covers how the
request travels to the client, how the answer travels back, and what the wire looks like.

## Overview

The stream speaks [AG-UI](https://docs.ag-ui.com) (see [Stream Event Types](api.md#stream-event-types)).
An elicitation is presented as an AG-UI **tool call** inside the running turn, and answered with the
AG-UI **`ToolMessage`** for that tool call:

1. The MCP tool sends an `ElicitRequest` and its thread parks, waiting for an answer.
2. The stream carries `TOOL_CALL_START` → `TOOL_CALL_ARGS` → `TOOL_CALL_END`, with
   `toolCallId` = the elicitation id and the request as the arguments.
3. The client renders the question and `POST`s a `ToolMessage` back.
4. The parked tool call resumes with the answer, and the turn carries on streaming on the same
   connection.

### Why not an AG-UI interrupt

AG-UI's native human-in-the-loop mechanism is an interrupt: the run ends with an interrupt outcome
and the client starts a new run carrying a `resume` entry. This API deliberately does not do that.
The turn is not driven by the HTTP response, the MCP tool call is a real thread parked mid-call
rather than a checkpoint that can be restarted, and the single long-lived stream is what gives
resume-by-cursor, keepalives and mid-turn image frames their guarantees. So an elicitation never
ends the run: no `RUN_FINISHED`, no new run, no reconnect. The answer is a side-channel `POST` that
wakes the parked call in place.

## Architecture

- **`ElicitationProvider`** (`mcp/client/elicitation/`, `@McpElicitation`) receives the MCP
  `ElicitRequest`. It requires `chatId` in the request's `_meta`, asks `ElicitationService` for an
  elicitation id, emits the request, and **blocks** on the answer.
- **`ElicitationService`** (`service/chat/events/`):
  - `prepareElicitation` mints the id and adds it to `elicitation:pending:{chatId}`.
  - `emitElicitation` writes a `SYSTEM` chat message carrying the id (for history replay), stores
    the requested schema's property names at `elicitation:schema:{chatId}:{elicitationId}`, and
    publishes the request on the Redis pub/sub channel `elicitation:events:{chatId}`. The names are
    stored before the request is published, so they are in place before any client can answer.
  - `actionResult` reads the action, and the form values sent alongside it, out of the client's
    `ToolMessage`.
  - `completeFromFrontend` narrows the values to the stored property names (on `accept` only),
    records `{action, content}` in history, stores it, and publishes the action on
    `elicitation:result:{chatId}:{elicitationId}`. On `cancel` it also publishes the cancel signal
    that stops the turn.
  - `awaitResultAsync` is what the provider is blocked on. It wakes on that result channel, or
    after the timeout, and resolves to the `ElicitResult` the provider returns to the MCP server.
- **`RedisStreamingChatService`** subscribes to `elicitation:events:{chatId}` once per turn, and
  `SideChannelEventTranslator` turns each event into AG-UI frames on the durable Redis stream. That
  is what gives an elicitation an SSE `id:` and makes it replayable through `Last-Event-ID`.

## Wire format

### The request — a tool call

```
event: TOOL_CALL_START
data: {"type":"TOOL_CALL_START","toolCallId":"9c41...","toolCallName":"elicitation"}

event: TOOL_CALL_ARGS
data: {"type":"TOOL_CALL_ARGS","toolCallId":"9c41...","delta":"{\"message\":\"Delete PROJ-12?\",\"requestedSchema\":{...},\"_meta\":{...},\"elicitationId\":\"9c41...\",\"chatId\":\"0a4b...\"}"}

event: TOOL_CALL_END
data: {"type":"TOOL_CALL_END","toolCallId":"9c41..."}
```

- `toolCallName` is always `elicitation`.
- `delta` is the whole `ElicitRequest` as **one** JSON string, never split — parse it once
  `TOOL_CALL_ARGS` arrives. It carries `message`, `requestedSchema`, `_meta`, and the
  `elicitationId`/`chatId` added by this API.

### The answer — a `ToolMessage`

```
POST /streaming/chats/{chatId}/{elicitationId}/elicitation-response
Content-Type: application/json

{
  "id": "b8e2...",
  "role": "tool",
  "toolCallId": "9c41...",
  "content": "{\"action\":\"accept\",\"assigneeAccountId\":\"70121:629f...\"}"
}
```

- `toolCallId` must equal `{elicitationId}` in the path.
- `content` is a JSON **string**, as AG-UI defines a tool message's content: a flat object holding
  an `action` — `accept`, `decline`, or `cancel` (case-insensitive) — and the form values, keyed by
  the `requestedSchema` property names.
- **What the MCP tool receives** is a standard MCP `ElicitResult`: the action, and on `accept` a
  `content` holding **only** the keys the elicitation's `requestedSchema.properties` named. Anything
  else the client sends is dropped. On `decline`/`cancel`, and on timeout, `content` is absent. If the
  stored property names have expired (they live `timeout-seconds + 60`), nothing is forwarded rather
  than the raw payload. Nothing of this API's is put in the result's `_meta`.

| Status | Meaning |
|--------|---------|
| `200` | Answer accepted; the parked tool call resumes |
| `400` | `toolCallId` does not match the path, or `content` names no known action |
| `404` | The chat does not exist or is not the caller's |

The chat must belong to the caller, and that is checked before anything else. Without it, any
authenticated user holding a chat id and an elicitation id could answer someone else's question.

### Cancelling

An `action` of `cancel` also stops the turn. The stream then ends the way every cancelled turn does:
`TEXT_MESSAGE_END` if a reply was open, a `CUSTOM` event named `cancel`, then `RUN_FINISHED` carrying
the `SYSTEM` "Chat canceled." message.

### Timeout

`solesonic.elicitation.timeout-seconds` (default `600`, env `SOLESONIC_ELICITATION_TIMEOUT_SECONDS`)
bounds how long the tool call stays parked. On timeout — or when the turn ends with the question
still open — the tool receives `DECLINE`.

## Client example (TypeScript)

```typescript
function openChatStream(baseUrl: string, userId: string) {
  const eventSource = new EventSource(`${baseUrl}/streaming/chats/users/${userId}`);

  eventSource.addEventListener('TOOL_CALL_ARGS', async (raw) => {
    const { toolCallId, delta } = JSON.parse((raw as MessageEvent<string>).data);
    const elicitation = JSON.parse(delta);

    const { action, values } = await askTheUser(elicitation.message, elicitation.requestedSchema);

    const response = await fetch(
      `${baseUrl}/streaming/chats/${elicitation.chatId}/${toolCallId}/elicitation-response`,
      {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          id: crypto.randomUUID(),
          role: 'tool',
          toolCallId,
          content: JSON.stringify({ ...values, action })
        })
      }
    );

    if (!response.ok) {
      throw new Error(`Submit failed: ${response.status}`);
    }
  });

  return eventSource;
}
```

## Best practices for clients

- Dispatch on `TOOL_CALL_*` with `toolCallName: "elicitation"`; ignore other tool names.
- If the page reloads or the network blips, resume with `Last-Event-ID` — an elicitation frame is
  replayed like any other, so an unanswered question reappears.
- Treat `decline` as a neutral outcome: the operation does not proceed, and the turn continues.

## Related documentation

- [API](api.md)
- [Configuration](configuration.md)
- [MCP Integration](mcp-integration.md)
