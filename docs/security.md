# Security Guide

This document outlines the security architecture, authentication mechanisms, and security considerations for the Solesonic LLM API.

## Overview

The Solesonic LLM API implements a comprehensive security model based on OAuth2 and JWT tokens, with support for multiple authentication flows and secure token management.

### Security Architecture

```
┌─────────────────┐    ┌──────────────────┐    ┌─────────────────┐
│   Frontend      │    │  Solesonic API   │    │  OAuth2         │
│   Application   │◄──►│                  │◄──►│  Provider       │
│                 │    │  - JWT Validation│    │  (Keycloak)  │
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

While not implemented at the application level, rate limiting should be configured at the infrastructure level:

- **Load Balancer**: Implement rate limiting per IP/user
- **API Gateway**: Use AWS API Gateway or similar for rate limiting
- **Monitoring**: Track usage patterns and detect anomalies

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