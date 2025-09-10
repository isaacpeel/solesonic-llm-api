# Deployment Guide

This document covers production deployment strategies for the Solesonic LLM API.

## Production Profile

The application supports a `prod` profile optimized for production environments with enhanced security, SSL/TLS, and monitoring capabilities.

### Key Production Features

- **HTTPS/TLS**: Runs on port 8443 with SSL certificates
- **Enhanced Security**: Full OAuth2/JWT validation required
- **Environment-based Configuration**: All settings via environment variables
- **Health Checks**: Comprehensive monitoring endpoints
- **Logging**: Production-ready logging configuration

## Environment Requirements

### Java Runtime
- **Java 24** or later
- Minimum 2GB heap space recommended
- Consider G1GC for better performance: `-XX:+UseG1GC`

### External Dependencies
- **PostgreSQL 12+** with pgvector extension
- **Ollama service** (can be external)
- **OAuth2 Provider** (AWS Cognito recommended)
- **Load Balancer** (optional, recommended for HA)

## SSL/TLS Configuration

### Certificate Management

The production profile requires SSL certificates for HTTPS operation.

#### Using Java Keystore

1. **Prepare your SSL certificate and private key**
2. **Create a Java keystore:**
   ```bash
   keytool -importkeystore \
     -srckeystore your-cert.p12 \
     -srcstoretype PKCS12 \
     -destkeystore solesonic.jks \
     -deststoretype JKS
   ```

3. **Set environment variables:**
   ```bash
   SSL_CERT_LOCATION=/path/to/solesonic.jks
   KEYSTORE_PASSWORD=your-keystore-password
   ```

#### Certificate Sources

**Let's Encrypt (Recommended for development/staging):**
```bash
# Generate certificate using certbot
certbot certonly --standalone -d your-domain.com

# Convert to PKCS12 format
openssl pkcs12 -export \
  -in /etc/letsencrypt/live/your-domain.com/fullchain.pem \
  -inkey /etc/letsencrypt/live/your-domain.com/privkey.pem \
  -out solesonic.p12 \
  -name solesonic
```

**Commercial Certificate:**
- Obtain certificate from your preferred CA
- Ensure certificate includes full chain
- Convert to Java keystore format

### SSL Configuration Properties

The production profile automatically configures:
- Port 8443 for HTTPS
- SSL keystore location and password from environment variables
- Strong SSL protocols and cipher suites

## Secrets Management

### Environment Variables

All sensitive configuration should be provided via environment variables. Never embed secrets in configuration files.

#### AWS Secrets Manager (Recommended)

```bash
# Example script to load secrets from AWS
#!/bin/bash
SECRET_VALUE=$(aws secretsmanager get-secret-value \
  --secret-id solesonic/prod/database \
  --query SecretString --output text)

export SPRING_DATASOURCE_PASSWORD=$(echo $SECRET_VALUE | jq -r .password)
export SPRING_DATASOURCE_USERNAME=$(echo $SECRET_VALUE | jq -r .username)

# Start application
java -jar solesonic-llm-api.jar --spring.profiles.active=prod
```

#### Kubernetes Secrets

```yaml
apiVersion: v1
kind: Secret
metadata:
  name: solesonic-secrets
type: Opaque
stringData:
  spring-datasource-password: "your-db-password"
  atlassian-oauth-client-secret: "your-atlassian-secret"
  mcp-client-secret: "your-mcp-secret"
  keystore-password: "your-keystore-password"
---
apiVersion: apps/v1
kind: Deployment
metadata:
  name: solesonic-api
spec:
  template:
    spec:
      containers:
      - name: solesonic-api
        image: solesonic/llm-api:latest
        env:
        - name: SPRING_DATASOURCE_PASSWORD
          valueFrom:
            secretKeyRef:
              name: solesonic-secrets
              key: spring-datasource-password
        - name: KEYSTORE_PASSWORD
          valueFrom:
            secretKeyRef:
              name: solesonic-secrets
              key: keystore-password
```

### Docker Secrets

```bash
# Create Docker secrets
echo "your-db-password" | docker secret create db_password -
echo "your-keystore-password" | docker secret create keystore_password -

# Use in Docker Compose
version: '3.8'
services:
  solesonic-api:
    image: solesonic/llm-api:latest
    environment:
      - SPRING_PROFILES_ACTIVE=prod
      - SPRING_DATASOURCE_PASSWORD_FILE=/run/secrets/db_password
      - KEYSTORE_PASSWORD_FILE=/run/secrets/keystore_password
    secrets:
      - db_password
      - keystore_password

secrets:
  db_password:
    external: true
  keystore_password:
    external: true
```

## Deployment Strategies

### Docker Deployment

#### Single Container

```dockerfile
FROM eclipse-temurin:24-jre-alpine

COPY target/solesonic-llm-api.jar app.jar
COPY certs/solesonic.jks /app/certs/solesonic.jks

ENV SPRING_PROFILES_ACTIVE=prod
ENV SSL_CERT_LOCATION=/app/certs/solesonic.jks

EXPOSE 8443

ENTRYPOINT ["java", "-jar", "/app.jar"]
```

```bash
# Build and run
docker build -t solesonic/llm-api:latest .
docker run -d \
  -p 8443:8443 \
  -e SPRING_DATASOURCE_URI="jdbc:postgresql://db:5432/solesonic" \
  -e KEYSTORE_PASSWORD="your-password" \
  --name solesonic-api \
  solesonic/llm-api:latest
```

#### Docker Compose Production

```yaml
version: '3.8'
services:
  solesonic-api:
    image: solesonic/llm-api:latest
    environment:
      - SPRING_PROFILES_ACTIVE=prod
      - SPRING_DATASOURCE_URI=jdbc:postgresql://postgres:5432/solesonic
      - SSL_CERT_LOCATION=/app/certs/solesonic.jks
      - KEYSTORE_PASSWORD=${KEYSTORE_PASSWORD}
      - ISSUER_URI=${ISSUER_URI}
      - JWK_SET_URI=${JWK_SET_URI}
    ports:
      - "8443:8443"
    volumes:
      - ./certs:/app/certs:ro
    depends_on:
      - postgres
    restart: unless-stopped

  postgres:
    image: pgvector/pgvector:0.8.0-pg17
    environment:
      - POSTGRES_DB=solesonic
      - POSTGRES_USER=solesonic
      - POSTGRES_PASSWORD=${DB_PASSWORD}
    volumes:
      - postgres_data:/var/lib/postgresql/data
    restart: unless-stopped

volumes:
  postgres_data:
```

### Kubernetes Deployment

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: solesonic-api
  labels:
    app: solesonic-api
spec:
  replicas: 3
  selector:
    matchLabels:
      app: solesonic-api
  template:
    metadata:
      labels:
        app: solesonic-api
    spec:
      containers:
      - name: solesonic-api
        image: solesonic/llm-api:latest
        ports:
        - containerPort: 8443
        env:
        - name: SPRING_PROFILES_ACTIVE
          value: "prod"
        - name: SPRING_DATASOURCE_URI
          value: "jdbc:postgresql://postgres-service:5432/solesonic"
        envFrom:
        - secretRef:
            name: solesonic-secrets
        volumeMounts:
        - name: certs
          mountPath: /app/certs
          readOnly: true
        livenessProbe:
          httpGet:
            path: /actuator/health
            port: 8443
            scheme: HTTPS
          initialDelaySeconds: 60
          periodSeconds: 30
        readinessProbe:
          httpGet:
            path: /actuator/health
            port: 8443
            scheme: HTTPS
          initialDelaySeconds: 30
          periodSeconds: 10
        resources:
          requests:
            memory: "2Gi"
            cpu: "500m"
          limits:
            memory: "4Gi"
            cpu: "2000m"
      volumes:
      - name: certs
        secret:
          secretName: ssl-certs
---
apiVersion: v1
kind: Service
metadata:
  name: solesonic-api-service
spec:
  selector:
    app: solesonic-api
  ports:
  - port: 443
    targetPort: 8443
  type: LoadBalancer
```

### Cloud-Specific Deployments

#### AWS ECS

```json
{
  "family": "solesonic-api",
  "taskRoleArn": "arn:aws:iam::account:role/SolesonicTaskRole",
  "executionRoleArn": "arn:aws:iam::account:role/SolesonicExecutionRole",
  "networkMode": "awsvpc",
  "requiresCompatibilities": ["FARGATE"],
  "cpu": "1024",
  "memory": "2048",
  "containerDefinitions": [
    {
      "name": "solesonic-api",
      "image": "your-account.dkr.ecr.region.amazonaws.com/solesonic:latest",
      "portMappings": [
        {
          "containerPort": 8443,
          "protocol": "tcp"
        }
      ],
      "environment": [
        {
          "name": "SPRING_PROFILES_ACTIVE",
          "value": "prod"
        }
      ],
      "secrets": [
        {
          "name": "KEYSTORE_PASSWORD",
          "valueFrom": "arn:aws:secretsmanager:region:account:secret:solesonic/keystore-password"
        }
      ],
      "logConfiguration": {
        "logDriver": "awslogs",
        "options": {
          "awslogs-group": "/ecs/solesonic-api",
          "awslogs-region": "us-east-1",
          "awslogs-stream-prefix": "ecs"
        }
      }
    }
  ]
}
```

#### Google Cloud Run

```yaml
apiVersion: serving.knative.dev/v1
kind: Service
metadata:
  name: solesonic-api
  annotations:
    run.googleapis.com/ingress: all
spec:
  template:
    metadata:
      annotations:
        run.googleapis.com/cpu-throttling: "false"
        run.googleapis.com/memory: "2Gi"
    spec:
      containerConcurrency: 100
      containers:
      - image: gcr.io/your-project/solesonic-api:latest
        ports:
        - containerPort: 8443
        env:
        - name: SPRING_PROFILES_ACTIVE
          value: "prod"
        - name: KEYSTORE_PASSWORD
          valueFrom:
            secretKeyRef:
              name: solesonic-secrets
              key: keystore-password
        resources:
          limits:
            cpu: 2000m
            memory: 2Gi
```

## Load Balancing and Ingress

### NGINX Configuration

```nginx
upstream solesonic_api {
    server 127.0.0.1:8443;
    # Add more backend servers for HA
    # server 127.0.0.2:8443;
}

server {
    listen 443 ssl http2;
    server_name api.yourdomain.com;

    ssl_certificate /etc/ssl/certs/yourdomain.pem;
    ssl_certificate_key /etc/ssl/private/yourdomain.key;
    ssl_protocols TLSv1.2 TLSv1.3;
    ssl_ciphers HIGH:!aNULL:!MD5;

    location / {
        proxy_pass https://solesonic_api;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        
        # WebSocket support (if needed)
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
    }

    location /actuator/health {
        proxy_pass https://solesonic_api;
        access_log off;
    }
}
```

### Kubernetes Ingress

```yaml
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: solesonic-api-ingress
  annotations:
    kubernetes.io/ingress.class: "nginx"
    cert-manager.io/cluster-issuer: "letsencrypt-prod"
    nginx.ingress.kubernetes.io/backend-protocol: "HTTPS"
spec:
  tls:
  - hosts:
    - api.yourdomain.com
    secretName: solesonic-api-tls
  rules:
  - host: api.yourdomain.com
    http:
      paths:
      - path: /
        pathType: Prefix
        backend:
          service:
            name: solesonic-api-service
            port:
              number: 443
```

## Monitoring and Logging

### Health Checks

The application provides several health check endpoints:

- `/actuator/health` - Overall application health
- `/actuator/health/db` - Database connectivity
- `/actuator/health/diskSpace` - Disk space status
- `/actuator/info` - Application information

### Logging Configuration

Production logging should be configured for your log aggregation system:

```bash
# Environment variables for logging
LOGGING_LEVEL_ROOT=WARN
LOGGING_LEVEL_COM_SOLESONIC=INFO
LOGGING_FILE_NAME=/var/log/solesonic/application.log
LOGGING_PATTERN_FILE="%d{yyyy-MM-dd HH:mm:ss} [%thread] %-5level %logger{36} - %msg%n"
```

### Metrics and Monitoring

Consider integrating with monitoring solutions:

- **Prometheus**: Application metrics via `/actuator/prometheus`
- **Grafana**: Visualization dashboards
- **ELK Stack**: Log aggregation and analysis
- **CloudWatch**: AWS-native monitoring

## Security Considerations

### Network Security
- Use HTTPS everywhere (TLS 1.2+)
- Restrict database access to application servers only
- Implement proper firewall rules
- Use VPCs or private networks when possible

### Application Security
- All JWT tokens are validated against the configured JWK Set
- OAuth2 scopes are enforced for API access
- Rate limiting should be implemented at the load balancer level
- Regular security updates for all dependencies

### Data Protection
- Database connections are encrypted
- Sensitive environment variables are masked in logs
- Personal data handling follows applicable regulations
- Regular backups with encryption at rest

## Backup and Recovery

### Database Backups

```bash
# Automated daily backup script
#!/bin/bash
BACKUP_DIR="/backups/postgresql"
DATE=$(date +%Y%m%d_%H%M%S)
BACKUP_FILE="solesonic_backup_$DATE.sql"

pg_dump -h $POSTGRES_HOST -U $POSTGRES_USER -d solesonic > $BACKUP_DIR/$BACKUP_FILE
gzip $BACKUP_DIR/$BACKUP_FILE

# Cleanup old backups (keep 30 days)
find $BACKUP_DIR -name "*.sql.gz" -mtime +30 -delete
```

### Disaster Recovery

- Maintain regular database backups
- Document recovery procedures
- Test recovery processes regularly
- Maintain infrastructure as code for quick reconstruction

## Performance Tuning

### JVM Tuning

```bash
# Recommended JVM options for production
JAVA_OPTS="-Xmx4g -Xms2g -XX:+UseG1GC -XX:G1HeapRegionSize=16m -XX:+UseStringDeduplication -XX:+OptimizeStringConcat"
```

### Database Optimization

- Configure connection pooling appropriately
- Regular VACUUM and ANALYZE operations
- Monitor query performance
- Optimize pgvector index settings for your use case

## Troubleshooting Production Issues

For detailed troubleshooting guidance, see [docs/troubleshooting.md](troubleshooting.md).

## Related Documentation

- **Configuration**: [docs/configuration.md](configuration.md) - Environment variables
- **Security**: [docs/security.md](security.md) - OAuth2 and JWT setup
- **Getting Started**: [docs/getting-started.md](getting-started.md) - Local development setup
- **Troubleshooting**: [docs/troubleshooting.md](troubleshooting.md) - Common issues and solutions