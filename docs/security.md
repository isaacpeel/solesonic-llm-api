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