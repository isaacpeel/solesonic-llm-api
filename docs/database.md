# Database Schema Management

This document describes how the database schema is managed in the Solesonic LLM API project.

## Overview

The Solesonic LLM API uses PostgresSQL with the pgvector extension for efficient vector embeddings storage. The database schema is managed through a combination of:

1. Initial setup using Docker and initialization scripts
2. Schema evolution using Flyway migrations

## Initial Database Setup

The initial database setup is handled through Docker Compose, which creates a PostgreSQL container with the pgvector extension. The setup is defined in the following files:

- `docker/docker-compose-db.yml`: Defines the PostgresSQL container with pgvector
- `docker/init_schema.sh`: Initializes the database with required extensions
- `docker/postgresql.conf`: Custom PostgresSQL configuration

### Docker Compose Configuration

The `docker-compose-db.yml` file sets up a PostgresSQL container with the pgvector extension:

```yaml
services:
  postgres:
    image: pgvector/pgvector:0.8.0-pg17
    # ... other configuration ...
    volumes:
      - postgres_data:/var/lib/postgresql/data
      - ./init_schema.sh:/docker-entrypoint-initdb.d/init_schema.sh
      - ./postgresql.conf:/etc/postgresql/postgresql.conf
```

### Initial Schema

The `init_schema.sql` script performs the following operations:

1. Creates the public schema
2. Enables required extensions:
   - `uuid-ossp`: For UUID generation
   - `vector`: The pgvector extension for vector operations
   - `hstore`: For key-value pair storage
3. Creates the database user and grants necessary permissions

## Schema Evolution with Flyway

After the initial setup, the database schema is managed through Flyway migrations. Flyway is a database migration tool that allows for versioned evolution of the database schema.

### Migration Files

Migration files are located in the `src/main/resources/db/migration` directory and follow the naming convention:

```
V{version_number}__{description}.sql
```

For example:
- `V1_1__initialize_jira_access_token.sql`
- `V1_2__initialize_ollama_model.sql`
- `V2_1__rename_jira_access_token.sql`

### Migration Versions

The migrations are organized in major versions:

1. **V1_x**: Initial schema setup with tables for:
   - Jira access tokens
   - Ollama models
   - Training documents
   - User preferences
   - Vector store
   - Chats and chat messages
   - Status history

2. **V2_x**: Schema updates including:
   - Renaming columns
   - Adding new columns
   - Dropping constraints
   - Adding new models

## Vector Storage with pgvector

The project uses the pgvector extension for PostgreSQL to store and query vector embeddings efficiently. This is crucial for the Retrieval Augmented Generation (RAG) functionality.

The pgvector extension enables:

1. Storage of embedding vectors in the database
2. Efficient similarity search using vector operations
3. Integration with the application's RAG pipeline

## How to Update the Schema

To make changes to the database schema:

1. Create a new migration file in `src/main/resources/db/migration` following the naming convention
2. Write the SQL statements to modify the schema
3. When the application starts, Flyway will automatically apply any new migrations

## Database Configuration

The database connection is configured through environment variables in the `.env` file. For a complete list of all environment variables and their configuration, see [docs/configuration.md](configuration.md).

**Important Notes:**
- The database password **must** be defined in the `.env` file
- The database runs on port 5445 as configured in the docker/docker-compose-db.yml file

These variables are used by both the Docker Compose setup and the Spring Boot application.