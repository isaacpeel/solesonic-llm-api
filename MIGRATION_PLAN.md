# Atlassian Token Storage Migration Plan

## Overview
This document outlines the migration from database-based Atlassian access token storage to AWS Secrets Manager.

## Phase 1: Dual-Read, Write-Through (IMPLEMENTED ✅)

### What's Implemented
- **MigrationAwareAtlassianTokenStore**: Primary token store with dual-read/write-through functionality
- **AwsSecretsManagerAtlassianTokenStore**: Pure Secrets Manager implementation
- **Token Store Abstraction**: Interface-based design for easy switching between implementations
- **Configuration**: Environment-based configuration with migration controls
- **Bug Fix**: Fixed token expiry logic (was returning opposite result)

### Current Behavior
- **Read Operations**: 
  1. Try Secrets Manager first
  2. Fallback to database if not found
  3. Automatic migration from DB → Secrets Manager on read
- **Write Operations**: Write to both Secrets Manager (primary) and database (backup)
- **Error Handling**: Secrets Manager failures stop operation; database failures are logged but don't fail the operation

### Configuration
```properties
# Migration control (currently enabled)
atlassian.tokens.migration.enabled=true

# Secrets Manager configuration
atlassian.tokens.secrets.prefix=/solesonic/atlassian/tokens
atlassian.tokens.secrets.adminKey=admin
```

### IAM Permissions Required
```json
{
    "Version": "2012-10-17",
    "Statement": [
        {
            "Effect": "Allow",
            "Action": [
                "secretsmanager:GetSecretValue",
                "secretsmanager:CreateSecret",
                "secretsmanager:PutSecretValue",
                "secretsmanager:UpdateSecret"
            ],
            "Resource": "arn:aws:secretsmanager:*:*:secret:/solesonic/atlassian/tokens/*"
        }
    ]
}
```

## Phase 2: Cutover (PLANNED)

### Objective
Switch to Secrets Manager-only operation while keeping database as rollback option.

### Implementation Steps
1. **Monitor Phase 1**: Ensure all tokens are being migrated successfully
   - Check logs for migration success/failure rates
   - Verify Secrets Manager contains all active tokens

2. **Update Configuration**: Disable database fallback
   ```properties
   atlassian.tokens.migration.enabled=false
   ```

3. **Deploy**: The `AwsSecretsManagerAtlassianTokenStore` will become the primary bean
   - `MigrationAwareAtlassianTokenStore` will be disabled via `@ConditionalOnProperty`
   - All operations will go directly to Secrets Manager

4. **Testing**: Verify all token operations work correctly
   - OAuth callback flow
   - Token refresh for both user and admin tokens
   - API requests using stored tokens

5. **Rollback Plan**: If issues occur, re-enable migration mode
   ```properties
   atlassian.tokens.migration.enabled=true
   ```

## Phase 3: Cleanup (PLANNED)

### Objective
Remove database components and tables after successful cutover.

### Implementation Steps
1. **Database Migration**: Create Flyway migration to drop table
   ```sql
   -- V{next_version}__drop_atlassian_access_token_table.sql
   DROP TABLE IF EXISTS atlassian_access_token;
   ```

2. **Code Cleanup**:
   - Remove `AtlassianAccessTokenRepository` interface
   - Remove `MigrationAwareAtlassianTokenStore` class
   - Convert `AtlassianAccessToken` from JPA entity to plain DTO:
     ```java
     // Remove these annotations:
     // @Entity
     // @Id
     // @Column
     ```

3. **Configuration Cleanup**:
   - Remove migration-related properties
   - Keep only Secrets Manager configuration

4. **Test Cleanup**:
   - Remove repository-based tests
   - Update integration tests to use only Secrets Manager

## Secret Format

### User Token
- **Secret Name**: `/solesonic/atlassian/tokens/{userId}`
- **Content**:
```json
{
    "user_id": "uuid-string",
    "access_token": "...",
    "refresh_token": "...",
    "scope": "...",
    "expires_in": 3600,
    "administrator": false,
    "created": "2025-09-01T12:00:00Z",
    "updated": "2025-09-01T12:00:00Z"
}
```

### Admin Token
- **Secret Name**: `/solesonic/atlassian/tokens/admin`
- **Content**: Same format as user token but with `"administrator": true`

## Monitoring and Observability

### Log Messages to Monitor
- `"Token found in Secrets Manager for user: {}"` - Normal operation
- `"Token found in DB for user: {}, migrating to Secrets Manager"` - Migration in progress
- `"Successfully migrated token for user: {} to Secrets Manager"` - Migration success
- `"Failed to migrate token for user: {} to Secrets Manager"` - Migration failure (requires attention)

### Metrics to Track
- Secrets Manager read/write operations
- Database fallback operations (should decrease over time)
- Migration success/failure rates
- Token refresh operations

## Security Considerations

1. **No Secrets in Logs**: Implementation ensures no token values are logged
2. **Minimal IAM Permissions**: Only required permissions for the specific secret prefix
3. **Encryption**: Secrets Manager provides encryption at rest and in transit
4. **Access Control**: IAM policies control which services can access tokens

## Testing Strategy

### Unit Tests (Implemented)
- `AwsSecretsManagerAtlassianTokenStore`: CRUD operations, error handling
- `AtlassianAccessToken`: Token expiry logic with 10-second buffer

### Integration Tests (Recommended)
- End-to-end OAuth flow with Secrets Manager storage
- Token refresh scenarios for user and admin tokens
- Migration behavior testing

### Manual Testing
1. OAuth callback → token storage → accessible resources call
2. Token refresh on expiry
3. Admin token operations
4. Migration behavior during Phase 1

## Rollback Procedures

### From Phase 2 back to Phase 1
```properties
atlassian.tokens.migration.enabled=true
```
Redeploy application. Database will be used as fallback again.

### From Phase 1 to Pure Database (Emergency)
1. Comment out AWS SDK dependencies temporarily
2. Revert to original repository-based implementations
3. Redeploy

This should only be used in extreme emergencies as it will lose any tokens stored only in Secrets Manager.