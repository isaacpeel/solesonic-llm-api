# Troubleshooting Guide

This document provides solutions for common issues you may encounter when setting up, configuring, or running the Solesonic LLM API.

## Database Issues

### Database Connection Problems

#### Connection Refused
```
Could not connect to database: Connection refused
```

**Causes and Solutions:**

1. **Database not running**
   ```bash
   # Check if database container is running
   docker compose -f docker/docker-compose-db.yml ps
   
   # Start database if not running
   docker compose -f docker/docker-compose-db.yml up -d
   ```

2. **Wrong connection details**
   - Verify `SPRING_DATASOURCE_URI` in your `.env` file
   - Default: `jdbc:postgresql://localhost:5445/solesonic-llm-api`
   - Check port 5445 is not blocked by firewall

3. **Port conflict**
   ```bash
   # Check what's using port 5445
   lsof -i :5445
   
   # If needed, change port in docker-compose-db.yml and update SPRING_DATASOURCE_URI
   ```

#### Authentication Failed
```
FATAL: password authentication failed for user "solesonic-llm-api"
```

**Solutions:**
1. Verify `SPRING_DATASOURCE_PASSWORD` matches `DB_PASSWORD` in `.env`
2. Recreate database container if password was changed:
   ```bash
   docker compose -f docker/docker-compose-db.yml down -v
   docker compose -f docker/docker-compose-db.yml up -d
   ```

#### Migration Failures
```
Flyway migration failed: Validation failed
```

**Common Causes:**
1. **Checksum mismatch**: Migration file was modified after being applied
   ```bash
   # Check migration history
   docker exec -it postgres_container psql -U solesonic-llm-api -d solesonic-llm-api -c "SELECT * FROM flyway_schema_history ORDER BY installed_on DESC;"
   ```

2. **Manual database changes**: Schema modified outside of migrations
   - Restore from backup or recreate database
   - Create new migration to align schema

### pgvector Extension Issues

#### Extension Not Available
```
ERROR: extension "vector" is not available
```

**Solutions:**
1. Ensure using pgvector-enabled image:
   ```yaml
   image: pgvector/pgvector:0.8.0-pg17
   ```

2. Verify extension installation in database:
   ```sql
   SELECT * FROM pg_available_extensions WHERE name = 'vector';
   CREATE EXTENSION IF NOT EXISTS vector;
   ```

## Authentication and OAuth Issues

### JWT Token Validation Failures

#### Invalid Token Signature
```
JWT signature validation failed
```

**Solutions:**
1. **Check JWK Set URI**:
   ```bash
   # Test JWK Set accessibility
   curl -v "https://cognito-idp.us-east-1.amazonaws.com/us-east-1_XXXXXXXXX/.well-known/jwks.json"
   ```

2. **Verify issuer URI**:
   - Ensure `ISSUER_URI` matches the `iss` claim in your JWT token
   - Check for trailing slashes or typos

3. **Token expiration**:
   - JWT tokens have short lifespans (typically 1 hour)
   - Refresh token if expired

#### Missing or Invalid Claims
```
JWT token missing required claims
```

**Check token payload:**
```bash
# Decode JWT token (without verification)
echo "YOUR_JWT_TOKEN" | cut -d. -f2 | base64 -d | jq .
```

**Required claims:**
- `iss`: Must match `ISSUER_URI`
- `sub`: User identifier
- `exp`: Expiration time (must be in future)
- `aud`: Audience (client ID)

### AWS Cognito Issues

#### User Pool Not Found
```
ResourceNotFoundException: User pool not found
```

**Solutions:**
1. Verify User Pool ID in `ISSUER_URI`
2. Check AWS region in URI matches your user pool region
3. Confirm user pool exists and is active

#### Client ID Mismatch
```
Invalid client credentials
```

**Solutions:**
1. Verify client ID in token matches your application
2. Check client is enabled in Cognito User Pool
3. Ensure client has required OAuth flows enabled

## MCP Integration Issues

### MCP Server Connection Problems

#### Token Broker Authentication
```
Unauthorized: Invalid client credentials for MCP
```

**Solutions:**
1. **Verify MCP credentials**:
   ```bash
   # Check environment variables
   echo $MCP_CLIENT_ID
   echo $MCP_CLIENT_SECRET  # Should be set but not echo the value
   ```

2. **Check MCP enabled**:
   ```bash
   echo $MCP_ENABLED  # Should be 'true'
   ```

3. **Test token endpoint**:
   ```bash
   curl -X POST https://your-cognito-domain/oauth2/token \
     -H "Content-Type: application/x-www-form-urlencoded" \
     -d "grant_type=client_credentials&client_id=YOUR_CLIENT_ID" \
     --user "YOUR_CLIENT_ID:YOUR_CLIENT_SECRET"
   ```

#### MCP SSE Connection Issues
```
Failed to establish SSE connection to MCP server
```

**Solutions:**
1. **Check MCP server URL configuration**:
   - Verify `spring.ai.mcp.client.sse.connections.solesonic.url` property
   - Ensure MCP server is running and accessible

2. **Network connectivity**:
   ```bash
   # Test basic connectivity
   curl -v https://your-mcp-server-url/health
   ```

3. **SSL/TLS issues**:
   - Verify SSL certificates if using HTTPS
   - Check for self-signed certificate issues

### Atlassian Token Issues

#### Refresh Token Expired
```
OAuth refresh token has expired
```

**Solutions:**
1. User needs to re-authenticate with Atlassian
2. Check refresh token storage in AWS Secrets Manager
3. Verify token rotation is working properly

#### Atlassian API Rate Limiting
```
Rate limit exceeded for Atlassian API
```

**Solutions:**
1. Implement exponential backoff in client code
2. Monitor API usage patterns
3. Consider using multiple API credentials if available

## Ollama Integration Issues

### Ollama Connection Problems

#### Service Not Available
```
ConnectException: Connection refused (Connection refused)
```

**Solutions:**
1. **Check Ollama service**:
   ```bash
   # Verify Ollama is running
   ollama list
   
   # Start Ollama if not running
   ollama serve
   ```

2. **Check base URL configuration**:
   - Default: `http://localhost:11434`
   - Verify `spring.ai.ollama.base-url` property

3. **Network issues**:
   ```bash
   # Test connectivity
   curl -v http://localhost:11434/api/tags
   ```

#### Model Not Found
```
Model 'qwen2.5:7b' not found
```

**Solutions:**
1. **Pull required models**:
   ```bash
   # Chat model
   ollama pull qwen2.5:7b
   
   # Embedding model
   ollama pull twine/mxbai-embed-xsmall-v1:latest
   ```

2. **Check available models**:
   ```bash
   ollama list
   ```

3. **Verify model configuration**:
   - Check `solesonic.llm.chat.model` property
   - Check `solesonic.llm.embedding.model` property

## Application Startup Issues

### Port Already in Use
```
Port 8080 was already in use
```

**Solutions:**
1. **Find process using port**:
   ```bash
   lsof -i :8080
   kill -9 <PID>
   ```

2. **Use different port**:
   ```bash
   ./mvnw spring-boot:run -Dserver.port=8081
   ```

3. **Check for other instances**:
   - Ensure no other instances of the application are running
   - Check for zombie processes

### Memory Issues

#### Out of Memory Errors
```
java.lang.OutOfMemoryError: Java heap space
```

**Solutions:**
1. **Increase heap size**:
   ```bash
   export JAVA_OPTS="-Xmx4g -Xms2g"
   ./mvnw spring-boot:run
   ```

2. **Check system memory**:
   ```bash
   free -h  # Linux/Mac
   # Ensure sufficient system memory available
   ```

3. **Monitor memory usage**:
   - Use JVM monitoring tools
   - Check for memory leaks in long-running processes

### Configuration Issues

#### Missing Environment Variables
```
Environment variable 'SPRING_DATASOURCE_URI' is not set
```

**Solutions:**
1. **Check .env file**:
   ```bash
   # Verify .env file exists in project root
   ls -la .env
   
   # Check file contents (be careful with secrets)
   grep SPRING_DATASOURCE_URI .env
   ```

2. **Environment loading**:
   - Ensure `.env` file is in the correct location (project root)
   - Verify file permissions allow reading
   - Check for typos in variable names (case-sensitive)

3. **Profile-specific issues**:
   ```bash
   # Specify profile explicitly
   ./mvnw spring-boot:run -Dspring.profiles.active=local
   ```

## Performance Issues

### Slow API Response Times

#### Database Performance
```
Long query execution times
```

**Solutions:**
1. **Check database connections**:
   ```sql
   SELECT * FROM pg_stat_activity WHERE state = 'active';
   ```

2. **Analyze slow queries**:
   ```sql
   SELECT query, mean_time, calls FROM pg_stat_statements ORDER BY mean_time DESC LIMIT 10;
   ```

3. **Vector search optimization**:
   - Ensure proper indexes on vector columns
   - Consider adjusting vector search parameters

#### LLM Response Times
```
Slow responses from Ollama
```

**Solutions:**
1. **Check system resources**:
   ```bash
   htop  # Monitor CPU/memory usage
   ```

2. **Model optimization**:
   - Use smaller models for faster responses
   - Consider GPU acceleration if available

3. **Concurrent requests**:
   - Monitor concurrent LLM requests
   - Implement request queuing if needed

## Logging and Debugging

### Enable Debug Logging

**Application logs**:
```bash
# Set environment variable
export LOGGING_LEVEL_COM_SOLESONIC=DEBUG

# Or in .env file
LOGGING_LEVEL_COM_SOLESONIC=DEBUG
```

**Database query logging**:
```bash
# Add to .env file
LOGGING_LEVEL_ORG_HIBERNATE_SQL=DEBUG
LOGGING_LEVEL_ORG_HIBERNATE_TYPE_DESCRIPTOR_SQL=TRACE
```

**HTTP request logging**:
```bash
LOGGING_LEVEL_ORG_SPRINGFRAMEWORK_WEB=DEBUG
```

### Log Locations

**Application logs**:
- Console output during development
- Check IDE console or terminal where application is running

**Database logs**:
```bash
# Docker container logs
docker compose -f docker/docker-compose-db.yml logs postgres

# Follow logs in real-time
docker compose -f docker/docker-compose-db.yml logs -f postgres
```

### Health Checks

**Application health**:
```bash
curl http://localhost:8080/actuator/health
```

**Database connectivity**:
```bash
curl http://localhost:8080/actuator/health/db
```

**Disk space**:
```bash
curl http://localhost:8080/actuator/health/diskSpace
```

## Environment-Specific Issues

### Local Development

**Common local issues**:
1. **Docker not running**: Start Docker Desktop
2. **Port conflicts**: Use different ports for development
3. **SSL certificate issues**: Use HTTP for local development
4. **CORS issues**: Configure `CORS_ALLOWED_ORIGINS` appropriately

### Production Deployment

**Common production issues**:
1. **SSL certificate problems**: Verify certificate validity and configuration
2. **Network connectivity**: Check security groups, firewalls
3. **Resource limits**: Monitor CPU, memory, disk usage
4. **Secret management**: Verify all secrets are properly configured

## Getting Additional Help

### Log Collection

When reporting issues, include:
1. **Application logs** (with relevant stack traces)
2. **Environment variables** (redact sensitive values)
3. **System information** (OS, Java version, Docker version)
4. **Steps to reproduce** the issue

### Useful Commands

**System information**:
```bash
# Java version
java -version

# Docker information
docker version
docker system info

# System resources
free -h  # Memory
df -h    # Disk space
```

**Application information**:
```bash
# Application info endpoint
curl http://localhost:8080/actuator/info

# Environment endpoint (if enabled)
curl http://localhost:8080/actuator/env
```

## Related Documentation

- **Getting Started**: [docs/getting-started.md](getting-started.md) - Initial setup and configuration
- **Configuration**: [docs/configuration.md](configuration.md) - Environment variables and settings
- **Security**: [docs/security.md](security.md) - Authentication and security troubleshooting
- **Deployment**: [docs/deployment.md](deployment.md) - Production deployment issues
- **Database**: [docs/database.md](database.md) - Database setup and schema management