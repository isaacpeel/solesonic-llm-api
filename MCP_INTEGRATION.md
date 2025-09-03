# MCP Server Integration Guide for Atlassian Token Broker

This document explains how to integrate with the Solesonic LLM API's Atlassian Token Broker from an MCP (Model Context Protocol) server.

## Overview

The Atlassian Token Broker is designed for 3-legged OAuth2 authentication scenarios where a refresh token needs to be kept secure. In these situations, the broker acts as a secure intermediary that stores long-lived refresh tokens and provides short-lived access tokens to MCP servers, allowing them to act on behalf of users without exposing sensitive credentials.

**Why This Architecture?**
- **Security**: Refresh tokens are stored securely in AWS Secrets Manager, never exposed to MCP servers
- **Isolation**: MCP servers only receive short-lived access tokens (typically 1 hour), limiting exposure window
- **User Agency**: Users maintain control over their Atlassian access through the central token broker
- **Automatic Rotation**: The broker handles refresh token rotation transparently

The Atlassian Token Broker provides secure OAuth2 token management for Atlassian services (Jira/Confluence). It handles:

- Token minting and caching
- Refresh token rotation and secure storage
- Access token expiration management
- Per-user concurrency control
- Short-lived token provisioning to MCP servers

## API Endpoints

### Base URL
```
{API_BASE_URL}/broker/atlassian
```

### 1. Mint Token
**Endpoint:** `POST /broker/atlassian/token`

**Description:** Mints a new access token for Atlassian services. Returns cached token if valid, otherwise refreshes using stored refresh token.

**Request:**
```json
{
  "subject_token": "user-uuid-here",
  "audience": "site-id-optional"
}
```

**Response:**
```json
{
  "accessToken": "eyJ0eXAiOiJKV1QiLCJhbGciOiJSUzI1NiIs...",
  "expiresInSeconds": 3600,
  "issuedAt": "2025-09-03T16:55:00Z",
  "userId": "user-uuid-here",
  "siteId": "site-id-optional"
}
```

### 2. Token Exchange
**Endpoint:** `POST /broker/atlassian/token-exchange`

**Description:** Alternative endpoint that performs the same operation as `/token`. Useful for OAuth2 token exchange patterns.

**Request/Response:** Same as `/token` endpoint.

## Request Parameters

### TokenExchange Object
| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `subject_token` | UUID | Yes | The user ID for whom to mint the token |
| `audience` | String | No | The Atlassian site ID (for site-specific tokens) |

### TokenResponse Object
| Field | Type | Description |
|-------|------|-------------|
| `accessToken` | String | The OAuth2 access token for Atlassian APIs |
| `expiresInSeconds` | Integer | Token expiration time in seconds |
| `issuedAt` | ZonedDateTime | When the token was issued (ISO 8601) |
| `userId` | UUID | The user ID the token belongs to |
| `siteId` | String | The Atlassian site ID (if provided) |

## Integration Examples

### Node.js/TypeScript Example

```typescript
interface TokenExchange {
  subject_token: string;
  audience?: string;
}

interface TokenResponse {
  accessToken: string;
  expiresInSeconds: number;
  issuedAt: string;
  userId: string;
  siteId?: string;
}

class AtlassianTokenBroker {
  constructor(private baseUrl: string, private authToken: string) {}

  async mintToken(userId: string, siteId?: string): Promise<TokenResponse> {
    const request: TokenExchange = {
      subject_token: userId,
      ...(siteId && { audience: siteId })
    };

    const response = await fetch(`${this.baseUrl}/broker/atlassian/token`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Authorization': `Bearer ${this.authToken}`
      },
      body: JSON.stringify(request)
    });

    if (!response.ok) {
      throw new Error(`Token mint failed: ${response.status} ${response.statusText}`);
    }

    return await response.json();
  }

  async refreshToken(userId: string, siteId?: string): Promise<TokenResponse> {
    return this.mintToken(userId, siteId); // Same operation
  }
}

// Usage in MCP server
const tokenBroker = new AtlassianTokenBroker(
  process.env.SOLESONIC_API_URL,
  process.env.SOLESONIC_AUTH_TOKEN
);

// Mint token for user
const token = await tokenBroker.mintToken('user-uuid-123', 'site-abc');
console.log('Access token:', token.accessToken);
```

### Python Example

```python
import requests
from typing import Optional, Dict, Any
from datetime import datetime

class AtlassianTokenBroker:
    def __init__(self, base_url: str, auth_token: str):
        self.base_url = base_url
        self.auth_token = auth_token
    
    def mint_token(self, user_id: str, site_id: Optional[str] = None) -> Dict[str, Any]:
        """Mint an access token for Atlassian services."""
        request_data = {
            "subject_token": user_id
        }
        if site_id:
            request_data["audience"] = site_id
        
        response = requests.post(
            f"{self.base_url}/broker/atlassian/token",
            json=request_data,
            headers={
                "Content-Type": "application/json",
                "Authorization": f"Bearer {self.auth_token}"
            }
        )
        
        response.raise_for_status()
        return response.json()
    
    def is_token_expired(self, token_response: Dict[str, Any]) -> bool:
        """Check if the token is expired or will expire soon (within 5 minutes)."""
        issued_at = datetime.fromisoformat(token_response["issuedAt"].replace('Z', '+00:00'))
        expires_in = token_response["expiresInSeconds"]
        
        # Add 5-minute buffer for token refresh
        expiry_time = issued_at.timestamp() + expires_in - 300
        return datetime.now().timestamp() >= expiry_time

# Usage in MCP server
token_broker = AtlassianTokenBroker(
    base_url=os.getenv("SOLESONIC_API_URL"),
    auth_token=os.getenv("SOLESONIC_AUTH_TOKEN")
)

# Mint token for user
token_response = token_broker.mint_token("user-uuid-123", "site-abc")
access_token = token_response["accessToken"]
```

## Authentication & Security

### API Authentication
- The token broker requires authentication to the Solesonic LLM API
- Use OAuth2 JWT tokens in the `Authorization: Bearer {token}` header
- Ensure your MCP server has proper credentials configured

### Refresh Token Management
- The broker automatically handles refresh token rotation
- Refresh tokens are stored securely in AWS Secrets Manager
- Users must complete initial OAuth2 flow to establish refresh tokens

### Error Handling

Common error responses:

```json
{
  "error": "RECONNECT_REQUIRED",
  "message": "No refresh token found for user {userId}",
  "status": 400
}
```

```json
{
  "error": "ROTATION_TIMEOUT",
  "message": "Rotation timeout for user {userId}",
  "status": 503
}
```

## Best Practices

### Token Caching
- The broker caches access tokens automatically
- Cache duration is based on token expiration
- Multiple requests for the same user return cached tokens when valid

### Concurrency Control
- The broker handles concurrent token requests safely
- Per-user rotation guards prevent race conditions
- Requests may be queued briefly during token refresh

### Error Handling
- Always handle `RECONNECT_REQUIRED` errors by redirecting users to re-authenticate
- Implement retry logic for `503` errors with exponential backoff
- Check token expiration before making Atlassian API calls

### Performance Optimization
- Cache TokenResponse objects in your MCP server
- Check `expiresInSeconds` and `issuedAt` to avoid unnecessary API calls
- Use site-specific tokens when possible by providing `audience` parameter

## Example Integration Flow

1. **User Authentication**: User completes OAuth2 flow with Atlassian
2. **Token Storage**: Refresh tokens stored in Secrets Manager
3. **MCP Request**: MCP server requests token via broker
4. **Token Minting**: Broker returns cached or refreshed access token
5. **API Usage**: MCP server uses access token for Atlassian API calls
6. **Token Refresh**: Broker automatically handles token rotation

## Environment Configuration

Required environment variables for the Solesonic API:

```bash
# Atlassian OAuth2 Configuration
ATLASSIAN_OAUTH_CLIENT_ID=your-atlassian-client-id
ATLASSIAN_OAUTH_CLIENT_SECRET=your-atlassian-client-secret
ATLASSIAN_OAUTH_TOKEN_URI=https://auth.atlassian.com/oauth/token

# Database Configuration
DB_URL=postgresql://localhost:5432/solesonic
POSTGRES_USER=your-db-user
POSTGRES_PASSWORD=your-db-password

# Security Configuration
JWK_SET_URI=your-jwk-set-uri
ISSUER_URI=your-issuer-uri

# CORS Configuration
CORS_ALLOWED_ORIGINS=https://your-mcp-server.com
```

For detailed setup instructions, see the main [README.md](README.md).