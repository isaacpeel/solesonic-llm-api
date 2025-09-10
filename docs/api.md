# API Documentation

This document provides an overview of the Solesonic LLM API endpoints and how to interact with them.

## API Base URL

The API is available at:
- **Local Development**: `http://localhost:8080`
- **With Context Path**: `http://localhost:8080/izzybot` (when BASE_URI environment variable is set)
- **Production**: Varies based on deployment (typically HTTPS on port 8443)

## Authentication

The API uses OAuth2 with JWT for authentication. All requests in production require a valid JWT token in the Authorization header:

```
Authorization: Bearer <your-jwt-token>
```

For local development, authentication may be more relaxed depending on configuration.

## Core API Endpoints

### Chat API

The chat functionality is the primary feature of the Solesonic LLM API, providing intelligent conversations with intent-based tool selection.

#### Create New Chat
- **Endpoint**: `POST /izzybot/chats/users/{userId}`
- **Description**: Creates a new chat session for a specific user
- **Path Parameters**:
  - `userId` (string): Unique identifier for the user
- **Request Body**: JSON object with initial message
- **Response**: Chat object with generated ID and initial response
- **Intent Detection**: Automatically classifies the message intent (GENERAL, CREATING_JIRA_ISSUE, CREATING_CONFLUENCE_PAGE)

#### Continue Chat Conversation
- **Endpoint**: `PUT /izzybot/chats/{chatId}`
- **Description**: Continues an existing chat conversation
- **Path Parameters**:
  - `chatId` (string): Unique identifier for the chat session
- **Request Body**: JSON object with the new message
- **Response**: Updated chat object with LLM response
- **Features**: Maintains conversation context and applies appropriate tools based on intent

#### Retrieve User Chats
- **Endpoint**: `GET /izzybot/chats/users/{userId}`
- **Description**: Retrieves all chat sessions for a specific user
- **Path Parameters**:
  - `userId` (string): Unique identifier for the user
- **Response**: Array of chat objects belonging to the user
- **Use Case**: Chat history, session management

#### Retrieve Specific Chat
- **Endpoint**: `GET /izzybot/chats/{chatId}`
- **Description**: Retrieves a specific chat session by ID
- **Path Parameters**:
  - `chatId` (string): Unique identifier for the chat session
- **Response**: Complete chat object with message history
- **Use Case**: Loading existing conversations

## Request/Response Format

### Chat Request Body
```json
{
  "message": "Your message here",
  "metadata": {
    "timestamp": "2025-09-10T00:27:00Z",
    "source": "web-ui"
  }
}
```

### Chat Response Format
```json
{
  "chatId": "uuid-chat-id",
  "userId": "uuid-user-id",
  "messages": [
    {
      "id": "uuid-message-id",
      "content": "Message content",
      "sender": "USER|ASSISTANT",
      "timestamp": "2025-09-10T00:27:00Z",
      "intent": "GENERAL|CREATING_JIRA_ISSUE|CREATING_CONFLUENCE_PAGE"
    }
  ],
  "createdAt": "2025-09-10T00:27:00Z",
  "updatedAt": "2025-09-10T00:27:00Z"
}
```

## Intent-Based Tool Selection

The API automatically selects appropriate tools based on message intent:

### Intent Types
- **GENERAL**: Regular conversation, no specialized tools
- **CREATING_JIRA_ISSUE**: Jira creation and assignee tools enabled
- **CREATING_CONFLUENCE_PAGE**: Confluence page creation tools enabled

### Tool Integration
When specific intents are detected, the API provides:
- **Jira Tools**: Issue creation, user assignment, project management
- **Confluence Tools**: Page creation, content editing, space management
- **Context Enhancement**: RAG integration with existing documentation

## Error Handling

### Standard HTTP Status Codes
- `200 OK`: Successful request
- `201 Created`: Resource created successfully
- `400 Bad Request`: Invalid request format or parameters
- `401 Unauthorized`: Authentication required or invalid token
- `403 Forbidden`: Access denied for the requested resource
- `404 Not Found`: Chat or user not found
- `429 Too Many Requests`: Rate limiting applied
- `500 Internal Server Error`: Server error

### Error Response Format
```json
{
  "error": {
    "code": "ERROR_CODE",
    "message": "Human-readable error description",
    "details": "Additional technical details",
    "timestamp": "2025-09-10T00:27:00Z"
  }
}
```

## Rate Limiting

The API implements rate limiting to ensure fair usage:
- **Development**: Generally unrestricted
- **Production**: Limits based on user and endpoint type
- **Headers**: Rate limit information provided in response headers

## Health and Monitoring

### Health Check
- **Endpoint**: `GET /actuator/health`
- **Description**: Application health status
- **Response**: Service health information
- **Use Case**: Monitoring, load balancer health checks

## API Discovery

### Interactive API Documentation

For comprehensive API documentation and testing:

**Swagger UI** (if configured):
- Local: `http://localhost:8080/swagger-ui`
- Production: `https://your-domain/swagger-ui`

**OpenAPI Specification**:
- Local: `http://localhost:8080/v3/api-docs`
- Production: `https://your-domain/v3/api-docs`

> **Note**: Swagger UI availability depends on application configuration. If not available, refer to controller annotations in the source code for detailed endpoint specifications.

### Source Code Documentation

For the most up-to-date API specifications, consult the Spring Boot controller annotations in:
- `src/main/java/com/solesonic/llmapi/controller/`

The controllers use standard Spring annotations that provide comprehensive endpoint documentation:
- `@RestController`: Controller identification
- `@RequestMapping`: Base path configuration
- `@PostMapping`, `@GetMapping`, `@PutMapping`: HTTP method and path mapping
- `@PathVariable`, `@RequestBody`: Parameter specifications
- `@ApiOperation`, `@ApiResponse`: OpenAPI documentation (if configured)

## Integration Examples

### cURL Examples

#### Create a New Chat
```bash
curl -X POST http://localhost:8080/izzybot/chats/users/user-123 \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer your-jwt-token" \
  -d '{
    "message": "Help me create a Jira issue for implementing user authentication",
    "metadata": {
      "timestamp": "2025-09-10T00:27:00Z",
      "source": "curl"
    }
  }'
```

#### Continue a Chat
```bash
curl -X PUT http://localhost:8080/izzybot/chats/chat-456 \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer your-jwt-token" \
  -d '{
    "message": "Make it a high priority issue and assign to John Doe",
    "metadata": {
      "timestamp": "2025-09-10T00:27:00Z"
    }
  }'
```

#### Get User Chats
```bash
curl -X GET http://localhost:8080/izzybot/chats/users/user-123 \
  -H "Authorization: Bearer your-jwt-token"
```

### JavaScript/TypeScript Example

```typescript
interface ChatMessage {
  message: string;
  metadata?: {
    timestamp?: string;
    source?: string;
  };
}

interface ChatResponse {
  chatId: string;
  userId: string;
  messages: Message[];
  createdAt: string;
  updatedAt: string;
}

class SolesonicApiClient {
  constructor(private baseUrl: string, private authToken: string) {}

  async createChat(userId: string, message: ChatMessage): Promise<ChatResponse> {
    const response = await fetch(`${this.baseUrl}/izzybot/chats/users/${userId}`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Authorization': `Bearer ${this.authToken}`
      },
      body: JSON.stringify(message)
    });

    if (!response.ok) {
      throw new Error(`HTTP error! status: ${response.status}`);
    }

    return response.json();
  }

  async continueChat(chatId: string, message: ChatMessage): Promise<ChatResponse> {
    const response = await fetch(`${this.baseUrl}/izzybot/chats/${chatId}`, {
      method: 'PUT',
      headers: {
        'Content-Type': 'application/json',
        'Authorization': `Bearer ${this.authToken}`
      },
      body: JSON.stringify(message)
    });

    if (!response.ok) {
      throw new Error(`HTTP error! status: ${response.status}`);
    }

    return response.json();
  }
}
```

## Related Documentation

- **Getting Started**: [docs/getting-started.md](getting-started.md) - Setup and first API calls
- **Configuration**: [docs/configuration.md](configuration.md) - Environment variables and settings
- **Security**: [docs/security.md](security.md) - Authentication and authorization details
- **MCP Integration**: [docs/mcp-integration.md](mcp-integration.md) - Token broker for MCP servers
- **Troubleshooting**: [docs/troubleshooting.md](troubleshooting.md) - Common API issues and solutions