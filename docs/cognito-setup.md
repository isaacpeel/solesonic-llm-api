# AWS Cognito User Pool Setup Guide

This guide explains how to create and configure an AWS Cognito User Pool for the Solesonic LLM API application, based on the exported configuration from the existing `solesonic-llm-api` user pool.

For information about how this integrates with MCP servers, see [docs/mcp-integration.md](mcp-integration.md). For environment variable configuration, see [docs/configuration.md](configuration.md).

## Overview

The Solesonic LLM API uses AWS Cognito for authentication and authorization with two distinct client applications:

1. **solesonic-llm-ui** - Frontend application client (authorization code flow)
2. **solesonic-llm-api-token-broker** - API token broker client (client credentials flow)

## User Pool Configuration

### Basic User Pool Settings

```bash
# Create the user pool
aws cognito-idp create-user-pool \
  --pool-name "solesonic-llm-api" \
  --deletion-protection ACTIVE \
  --user-pool-tier LITE
```

### Password Policy

Configure a strong password policy:

```json
{
  "PasswordPolicy": {
    "MinimumLength": 8,
    "RequireUppercase": true,
    "RequireLowercase": true,
    "RequireNumbers": true,
    "RequireSymbols": true,
    "TemporaryPasswordValidityDays": 7
  }
}
```

### User Attributes Schema

The user pool supports standard OpenID Connect attributes with the following required fields:
- `email` (required, auto-verified)
- `name` (required)
- `sub` (required, immutable)

Optional attributes include:
- `given_name`, `family_name`, `middle_name`
- `preferred_username`, `nickname`
- `profile`, `picture`, `website`
- `phone_number`, `phone_number_verified`
- `address`, `birthdate`, `gender`, `locale`, `zoneinfo`
- `updated_at`, `identities`

### User Pool Domain

Set up a hosted UI domain:

```bash
aws cognito-idp create-user-pool-domain \
  --user-pool-id us-east-1_YOUR_POOL_ID \
  --domain "solesonic-llm-api"
```

### Email Configuration

Configure SES for email sending:

```json
{
  "EmailConfiguration": {
    "SourceArn": "arn:aws:ses:us-east-1:YOUR_ACCOUNT:identity/your-email@domain.com",
    "ReplyToEmailAddress": "your-email@domain.com",
    "EmailSendingAccount": "DEVELOPER"
  }
}
```

### Admin User Creation

Configure admin-only user creation:

```json
{
  "AdminCreateUserConfig": {
    "AllowAdminCreateUserOnly": true,
    "UnusedAccountValidityDays": 7
  }
}
```

## Client Applications Setup

### 1. UI Client (solesonic-llm-ui)

This client handles user authentication for the frontend application.

```bash
aws cognito-idp create-user-pool-client \
  --user-pool-id us-east-1_YOUR_POOL_ID \
  --client-name "solesonic-llm-ui" \
  --explicit-auth-flows "ALLOW_REFRESH_TOKEN_AUTH" "ALLOW_USER_PASSWORD_AUTH" "ALLOW_USER_SRP_AUTH" \
  --supported-identity-providers "COGNITO" \
  --callback-urls "http://localhost:3000" "https://domain.com" \
  --logout-urls "https://domain.com.com" \
  --allowed-o-auth-flows "code" \
  --allowed-o-auth-scopes "aws.cognito.signin.user.admin" "email" "solesonic-llm-api/llm" "openid" \
  --allowed-o-auth-flows-user-pool-client \
  --prevent-user-existence-errors ENABLED \
  --enable-token-revocation \
  --access-token-validity 30 \
  --id-token-validity 30 \
  --refresh-token-validity 30 \
  --token-validity-units AccessToken=minutes,IdToken=minutes,RefreshToken=days
```

**Key Features:**
- Authorization code flow for web applications
- Standard OpenID Connect scopes
- User profile read/write permissions
- Callback URLs for localhost development and production

### 2. API Token Broker Client (solesonic-llm-api-token-broker)

This client handles API-to-API authentication for the token broker service.

```bash
aws cognito-idp create-user-pool-client \
  --user-pool-id us-east-1_YOUR_POOL_ID \
  --client-name "solesonic-llm-api-token-broker" \
  --generate-secret \
  --explicit-auth-flows "ALLOW_REFRESH_TOKEN_AUTH" \
  --supported-identity-providers "COGNITO" \
  --allowed-o-auth-flows "client_credentials" \
  --allowed-o-auth-scopes "solesonic-llm-api-token-broker/atlassian.read" "solesonic-llm-api-token-broker/atlassian.write" "solesonic-llm-api-token-broker/token:mint:jira" \
  --allowed-o-auth-flows-user-pool-client \
  --enable-token-revocation \
  --access-token-validity 60 \
  --id-token-validity 60 \
  --refresh-token-validity 5 \
  --token-validity-units AccessToken=minutes,IdToken=minutes,RefreshToken=days
```

## Resource Server Setup

You'll need to create a resource server to define the custom OAuth scopes used by the API clients:

```bash
aws cognito-idp create-resource-server \
  --user-pool-id us-east-1_YOUR_POOL_ID \
  --identifier "solesonic-llm-api-token-broker" \
  --name "Solesonic LLM API Token Broker" \
  --scopes ScopeName=atlassian.read,ScopeDescription="Read Atlassian tokens" \
          ScopeName=atlassian.write,ScopeDescription="Write Atlassian tokens" \
          ScopeName=token:mint:jira,ScopeDescription="Mint Jira tokens"

aws cognito-idp create-resource-server \
  --user-pool-id us-east-1_YOUR_POOL_ID \
  --identifier "solesonic-llm-api" \
  --name "Solesonic LLM API" \
  --scopes ScopeName=llm,ScopeDescription="Access LLM services"
```

## Spring Boot Application Configuration

Update your `application.properties` with the Cognito configuration:

```properties
# OAuth2 Provider (AWS Cognito)
ISSUER_URI=https://cognito-idp.us-east-1.amazonaws.com/us-east-1_YOUR_POOL_ID
JWK_SET_URI=https://cognito-idp.us-east-1.amazonaws.com/us-east-1_YOUR_POOL_ID/.well-known/jwks.json

# Token Broker OAuth2 Client
TOKEN_BROKER_CLIENT_ID=your-token-broker-client-id
TOKEN_BROKER_CLIENT_SECRET=your-token-broker-client-secret
```

## Environment Variables

Set up the following environment variables for your deployment:

```bash
# AWS Region where your Cognito User Pool is deployed
AWS_DEFAULT_REGION=us-east-1

# Cognito User Pool details
COGNITO_USER_POOL_ID=us-east-1_YOUR_POOL_ID
COGNITO_REGION=us-east-1

# OAuth2 Configuration
ISSUER_URI=https://cognito-idp.us-east-1.amazonaws.com/us-east-1_YOUR_POOL_ID
JWK_SET_URI=https://cognito-idp.us-east-1.amazonaws.com/us-east-1_YOUR_POOL_ID/.well-known/jwks.json

# Client Credentials (store securely, preferably in AWS Secrets Manager)
TOKEN_BROKER_CLIENT_ID=your-token-broker-client-id
TOKEN_BROKER_CLIENT_SECRET=your-token-broker-client-secret
```

## Security Considerations

1. **Client Secrets**: Store client secrets in AWS Secrets Manager rather than environment variables in production.

2. **Token Validity**: The configuration uses short-lived access tokens (30-60 minutes) with longer refresh tokens (5-30 days) for security.

3. **Admin User Creation**: Users can only be created by administrators, not through self-registration.

4. **Email Verification**: Email addresses are auto-verified and required for account recovery.

5. **MFA**: Currently disabled but can be enabled by setting `MfaConfiguration` to `OPTIONAL` or `ON`.

## Testing the Setup

1. **UI Client**: Test the authorization code flow with your frontend application.

2. **Client Credentials Flow**: Test the API clients using:
```bash
curl -X POST https://solesonic-llm-api.auth.us-east-1.amazoncognito.com/oauth2/token \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "grant_type=client_credentials&client_id=YOUR_CLIENT_ID&client_secret=YOUR_CLIENT_SECRET&scope=solesonic-llm-api-token-broker/atlassian.read"
```

3. **Token Validation**: Verify JWT tokens using the JWK endpoint.

## Troubleshooting

### Common Issues

1. **Invalid Scope Errors**: Ensure resource servers are created before configuring client scopes.

2. **Domain Conflicts**: Cognito domain names must be globally unique.

3. **SES Email Issues**: Verify your SES identity and ensure it's in the same region as your Cognito User Pool.

4. **Client Secret Errors**: Ensure you're using the correct client secret and that the client is configured with `generate-secret`.

### Useful AWS CLI Commands

```bash
# List user pools
aws cognito-idp list-user-pools --max-results 60

# Describe user pool
aws cognito-idp describe-user-pool --user-pool-id us-east-1_YOUR_POOL_ID

# List user pool clients
aws cognito-idp list-user-pool-clients --user-pool-id us-east-1_YOUR_POOL_ID

# Test client credentials
aws cognito-idp admin-initiate-auth \
  --user-pool-id us-east-1_YOUR_POOL_ID \
  --client-id YOUR_CLIENT_ID \
  --auth-flow ADMIN_USER_PASSWORD_AUTH \
  --auth-parameters USERNAME=testuser,PASSWORD=TempPassword123!
```

This setup provides a secure, scalable authentication system for the Solesonic LLM API with proper separation of concerns between different client applications.