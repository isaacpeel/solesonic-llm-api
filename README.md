# Solesonic LLM API

A Spring Boot application that provides an API for interacting with Large Language Models (LLMs) using Ollama, with document processing capabilities, vector storage, and Retrieval Augmented Generation (RAG).

## Features

- **LLM Integration**: Uses Ollama for chat and embedding models
- **Document Processing**: Supports PDF and other document formats
- **Vector Storage**: Uses pgvector for efficient vector embeddings storage
- **RAG (Retrieval Augmented Generation)**: Enhances LLM responses with relevant context
- **User Management**: Supports user-specific chat history and preferences
- **Atlassian Integration**: Connects with Jira and Confluence for product management (see [PRODUCT_MANAGEMENT.md](PRODUCT_MANAGEMENT.md))
- **Security**: OAuth2 with JWT authentication

## Prerequisites

- Java 21
- Docker and Docker Compose
- Ollama
- PostgreSQL with pgvector extension (provided via Docker)

## Configuration

### Environment Variables

Create a `.env` file in the root directory with the following variables:

```
# Application Configuration
APPLICATION_NAME=solesonic-llm-api

# Database Configuration
SPRING_DATASOURCE_URI=jdbc:postgresql://localhost:5445/solesonic-llm-api
SPRING_DATASOURCE_USERNAME=solesonic-llm-api
SPRING_DATASOURCE_PASSWORD=docker_pw

# Training Configuration
TRAINING_ENABLED=false
CONFLUENCE_TRAINING_ENABLED=false

# Security Configuration
JWK_SET_URI=https://your-jwk-set-uri
ISSUER_URI=https://your-issuer-uri

# Jira API Configuration
JIRA_CLIENT_ID=your_jira_client_id
JIRA_CLIENT_SECRET=your_jira_client_secret

# CORS Configuration
CORS_ALLOWED_ORIGINS=http://localhost:3000

# Docker Database Password
DB_PASSWORD=docker_pw
```

**Important Note:** The database password **must** be defined in the `.env` file as shown above.

Replace placeholder values with your actual configuration.

### Application Properties

The application uses different property files for different environments:

- `application.properties`: Common properties
- `application-local.properties`: Local development properties
- `application-prod.properties`: Production properties
- `application-test.properties`: Test properties

## Getting Started

### 1. Start the Database

```bash
cd docker-db
docker-compose -f docker-compose-db.yml up -d
```

This will start a PostgreSQL database with the pgvector extension on port 5445.

For more information about how the database schema is managed, see the [DATABASE.md](DATABASE.md) file.

### 2. Start Ollama

Ensure Ollama is installed and running on your machine. The application expects Ollama to be available at `http://localhost:11434`.

You can install Ollama from [https://ollama.ai/](https://ollama.ai/).

### 3. Build and Run the Application

#### Using Maven

```bash
./mvnw clean install
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

#### Using an IDE

Import the project into your IDE (IntelliJ IDEA, Eclipse, etc.) and run the `SolesonicLlmAPI` class with the `local` profile.

### 4. Verify the Application

The application should be running at `http://localhost:8080`.

## API Endpoints

### Chat API

- `POST /izzybot/chats/users/{userId}` - Create a new chat for a user
- `PUT /izzybot/chats/{chatId}` - Update an existing chat (continue conversation)
- `GET /izzybot/chats/users/{userId}` - Get all chats for a user
- `GET /izzybot/chats/{chatId}` - Get a specific chat by ID

### Authentication

The application uses OAuth2 with JWT for authentication. In production, all requests require authentication with a valid JWT token.

For local development, authentication is more relaxed but still uses the JWT mechanism.

## Development

### Required Models

The application uses the following Ollama models:

- Chat model: `qwen2.5:7b`
- Embedding model: `twine/mxbai-embed-xsmall-v1:latest`

These models will be pulled automatically when missing.

### File Upload Limits

- Maximum file size: 20MB
- Maximum request size: 20MB

## License

See the [LICENSE](LICENSE) file for details.