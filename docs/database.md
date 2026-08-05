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

The `chat_attachment` table (V3_4) stores images attached to chat messages. Image bytes are held in
a `bytea` column — deliberately not the `oid` large object used by `training_document`, because
attachments are routinely deleted and large objects would be orphaned in `pg_largeobject`. A row
with a null `chat_message_id` is *staged*: uploaded but not yet sent on a message.

`vision_description` and `vision_model` (V3_5) hold the description a vision model produced for the
image, and the name of the model that produced it. A null `vision_description` means the image has
not been described yet, which is what makes describing it retryable; the stored text is reused on
every later turn instead of calling the model again. To re-describe everything after changing models:
`update public.chat_attachment set vision_description = null where vision_model = '<old model>';`

`vision_failure_reason` (V3_6) records why an image was left undescribed — one of `VISION_TIMEOUT`,
`VISION_UNAVAILABLE`, `IMAGE_TOO_LARGE`, `IMAGE_UNREADABLE`, `EXCEEDED_IMAGE_LIMIT`. It is the
durable form of the `attachment` SSE event, so a reloaded conversation can still explain an image
the assistant never saw. A successful describe clears it and a failure clears the description, so
the two columns are mutually exclusive; both null means the image has not been attempted yet. To
find images worth retrying: `select id from public.chat_attachment where vision_failure_reason =
'VISION_TIMEOUT';`

The `generated_image` table (V3_7) stores images produced by the `generate_image` MCP tool. Same
`bytea` shape as `chat_attachment`, and for the same reason. The tool persists nothing and returns
~2MB of base64 per image; this table is where that base64 stops, so that everything downstream
carries a reference of a few hundred bytes instead.

`prompt` and `seed` together are the provenance record — without them a stored image is an orphan
and nobody can say which image a support ticket is about. `sha256` is the digest of `image_data`,
served as the download endpoint's strong `ETag`; images are *not* deduplicated by it, because with a
fresh random seed per call, identical bytes from two generations is not a case worth losing
per-image provenance for. Every metadata column except the digest is nullable: the tool reports its
metadata as a human-readable text block, so a field the API failed to parse costs a null rather than
a failed generation.

`chat_id` and `chat_message_id` (V3_8) tie an image generated inside a conversation to the assistant
turn that produced it, the way `chat_attachment` ties an upload to a user turn. Both stay null for
explicit generation from `/images`, which has no chat. A row with a `chat_id` but no
`chat_message_id` is *unbound*: the tool runs mid-turn, so the image exists before the message does,
and `GeneratedImageRepository.bind` claims it when that message is written.

Rows are never swept. Images are large and cheap to regenerate, so a retention policy is worth
setting deliberately — `delete from public.generated_image where created < now() - interval '90 days';`
is the shape of it, but nothing runs it today. Note that deleting an image a conversation references
leaves that turn rendering a broken reference, so a retention policy and chat retention want to
agree with each other.
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