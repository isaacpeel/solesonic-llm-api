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
   - The model catalog (`llm_model`, entity `LlmModel`; the table was renamed from its original
     name in `V3_21`)
   - Ingested documents
   - User preferences
   - Vector store
   - Chats and chat messages

### Ingested documents — three tables (V3_28)

An ingested document is a descriptor, its bytes, and its grants:

| Table | Holds |
|---|---|
| `ingested_document` | `id`, `file_name`, `content_type`, `document_source` (NOT NULL), `created`/`updated`, `metadata jsonb` |
| `ingested_document_content` | `ingested_document_id` PK/FK, `data bytea`, `size_bytes`. Absent where the bytes live elsewhere |
| `document_entitlement` | one row per `(document, principal_type, principal_id, grant_kind)` |

**`ingested_document` carries no ownership columns.** `scope`, `user_id` and `chat_id` were a
nullable-column discriminated union whose invariant — exactly one owning column set, agreeing with
the scope — was enforced by nothing but every call site remembering, and the table's migration
history is a record of call sites forgetting. Ownership is now rows in `document_entitlement`, so a
new ownership shape is a new row rather than a new column and another unenforced rule.

`grant_kind` separates `RETRIEVE` (who may have this come back from a search) from `MANAGE` (whose
library it appears in). A chat document is retrievable by the conversation and managed by whoever
uploaded it — two different principals, which is what makes "every document I uploaded to any chat"
a single indexed query. `principal_id` is text because `GLOBAL` has no owner (the `'-'` sentinel, not
null, so the unique constraint still bites) and `ROLE` is named. Both foreign keys cascade, so
deleting a document takes its bytes and its grants without any code remembering to.

`metadata` is **provenance only** — `CHAT_ID`, `CHAT_ATTACHMENT_ID`, `SOURCE_URI`,
`CONFLUENCE_PAGE_ID`, `ORIGINAL_FILE_NAME`, `FILE_SIZE_BYTES`, `REPLACED_BY_ID`. Where a document
came from and where it may be retrieved are now different facts: teardown reads provenance,
retrieval reads entitlement, and promotion touches only the latter.

> **`data` is `bytea`, and the entity field must never carry `@Lob`.** That annotation is what made
> the old `ingested_document.file_data` a Postgres large object — an `oid` *reference* whose target
> outlives the row pointing at it unless `lo_unlink` is called, which nothing ever did. By the time
> this was noticed there were 351,476 orphaned objects totalling roughly 6 GB, accumulated one per
> document per re-ingestion cycle — reclaimed by `scripts/reclaim-orphaned-large-objects.sql`, a
> standalone maintenance script run manually rather than a Flyway migration, because the batched
> `lo_unlink` sweep needs a real mid-script `COMMIT` between batches to avoid overflowing Postgres's
> shared lock table, and OSS `flyway-core` has no way to disable a SQL migration's enclosing
> transaction. A row delete reclaims `bytea` by itself going forward.

The `chat_attachment` table (V3_4) stores images attached to chat messages. Image bytes are held in
a `bytea` column — the same choice, made earlier and for the same reason. A row with a null
`chat_message_id` is *staged*: uploaded but not yet sent on a message.

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

The `chat_group` table (V3_11) holds the optional sections a user files conversations under, and
`chat.chat_group_id` is the membership. It is a nullable column rather than a join table because a
chat belongs to at most one group: the group is a property of the conversation, so a chat can be
read without a second query to find its section. Every chat starts ungrouped and stays that way
until a client files it — nothing in the chat pipeline writes this column.

The foreign key is `on delete set null`, so deleting a group ungroups its conversations rather than
deleting them; losing a section of the sidebar must never lose the chats filed under it. To find
what a group holds, or what is unfiled:

```sql
select id, name from public.chat where chat_group_id = '<group uuid>' order by timestamp desc;
select id, name from public.chat where user_id = '<user uuid>' and chat_group_id is null;
```

`chat.sort_order` and `chat.group_sort_order` (V3_12) are where a conversation has been placed by
hand. Two columns rather than one, because a chat appears in two independently ordered lists — the
user's whole list and, when it is filed, its group's — and a single column would make a move in one
reshuffle the other. Both are null until a client moves the conversation, and null is what makes the
ordering fall back to `timestamp desc`: a chat nobody has arranged sorts exactly as it did before
these columns existed, and a new one still arrives at the top rather than the bottom. A move
renumbers a list densely from zero, but the values are not an index: deleting a placed chat, or
moving one out of a group, leaves a gap that stays until the next move closes it. Only the relative
order is meaningful. `group_sort_order` is cleared whenever the chat changes group.

`chat_group.sort_order` (V3_13) is the same idea one level up: where a *section* has been placed in
the caller's list of sections. One column, not two, because a group appears in exactly one list. It
is null until a client places the group, and null falls back to name ordering, so a section nobody
has arranged sits exactly where it did before the column existed.

Unlike the two columns on `chat`, this one is written exactly as the client sends it — the update is
a pure update of one row and renumbers nothing — so gaps and duplicates are both normal here rather
than a transient state. The listing breaks a tie by `name` and then by `id`, which is what keeps two
groups sharing a rank from swapping places between requests:

```sql
select id, name, sort_order from public.chat_group where user_id = '<user uuid>'
order by sort_order asc nulls last, name asc, id asc;
```

Deleting a conversation is **not** cascaded by the database. Nothing in `chat_message`,
`chat_attachment`, or `generated_image` carries a foreign key to `chat`, so `DELETE /chats/{chatId}`
clears all three by hand inside one transaction (`ChatService.delete`). Deleting a `chat` row
directly in SQL leaves the transcript and the image bytes behind, unreachable but still stored:

```sql
delete from public.chat_attachment where chat_id = '<chat uuid>';
delete from public.generated_image where chat_id = '<chat uuid>';
delete from public.chat_message where chat_id = '<chat uuid>';
delete from public.chat where id = '<chat uuid>';
```
   - Status history

2. **V2_x**: Schema updates including:
   - Renaming columns
   - Adding new columns
   - Dropping constraints
   - Adding new models

### User memory — three tables (V3_34)

| Table | Holds |
|---|---|
| `user_memory` | One memory: `user_id`, `kind`, `origin`, `status`, `memory_key`, `content`, `confidence`, `created`/`updated`/`last_confirmed`, and `supersedes` — the memory it replaced |
| `user_memory_evidence` | One row per `USER` message a memory was learned from: `memory_id`, `user_id`, `chat_id`, `chat_message_id`, `chat_message_timestamp` |
| `chat_memory_reflection` | Per conversation, how far reflection has read (`reflected_through`), its `status` and `attempts` |

Load-bearing details:

- **Every row carries `user_id NOT NULL`, and every reference between these tables carries it too.**
  Evidence references its memory through `(memory_id, user_id) → user_memory (id, user_id)`, and
  `supersedes` through `(supersedes, user_id)`, so a row can never point at another user's row even
  by mistake. Both need the `unique (id, user_id)` constraint on `user_memory`.
- **`user_memory_active_key_idx` is a partial unique index** on `(user_id, memory_key)` where
  `status = 'ACTIVE'`: two active values for one key cannot coexist. Code superseding a memory must
  flush the old row as `SUPERSEDED` before inserting its replacement, or the insert fails.
- **`on delete set null (supersedes)`** — a column-list `SET NULL`, Postgres 15 or later.
- **No foreign key to `chat` or `chat_message`**, as elsewhere in the schema. Forgetting a
  conversation is written out in `UserMemoryService.forgetChat`; a memory whose last evidence row
  goes is deleted with it.
- **`chat_message_timestamp` on evidence** is what lets reflection refuse to let an older statement
  replace a newer one during backfill.

### User profile (V3_38, V3_39, V3_40)

`user_profile` holds who each user id belongs to, as their identity provider last described them:
`user_id` (PK), `username` (`preferred_username`), `email`, `display_name` (`given_name`, else
`name`), `updated`. The JWT filters write it on every authenticated request through
`UserProfileService.record`, and the lifecycle sweep writes it from Keycloak's admin API.

`checked` is when the sweep last looked the user up in Keycloak; `removed` is when Keycloak was found
not to have them, or to have disabled them. A row with `removed` set is never seeded for reflection
again, and a login clears it. To see who has been concluded removed:
`select user_id, username, removed from public.user_profile where removed is not null;`

- **Every column but the key is nullable**, because a token need not carry any of the claims.
- **A claim missing from a later token never clears the stored value**: the upsert `coalesce`s each
  column, and rewrites the row only when something changed.
- **No foreign key to anything**, as elsewhere in the schema. A user who has not signed in since a
  column was added has no value in it until their next request.

### Model access keys (V3_41)

`user_llm_key` holds each user's own model-server key, which every model call made for them carries
in place of the shared service key: `user_id` (PK), `api_key` (`bytea`, encrypted by the same
`BytesEncryptor` as the Atlassian and Google tokens), `key_hint` (the key's last four characters),
`created`, `updated`, `updated_by` (the administrator who last set it). Written only through
`/llm-keys`; read only through `UserLlmKeyRepository`, whose `findApiKey` is the one query that
selects the key column.

- **The claim queries depend on it.** `ChatMemoryReflectionRepository.findClaimable`,
  `InterestScanRepository.findClaimable`, `InterestAgentRepository.findDue` and
  `MemoryEmbeddingRepository.findStale` each require a `user_llm_key` row for the owner, so work for a
  user without a key waits rather than being claimed and failed every tick.
- **The user lifecycle purge leaves it alone**: a dormant user keeps their key. Only an administrator's
  `DELETE` removes it.

### Push notifications — two tables (V3_36)

| Table | Holds |
|---|---|
| `push_subscription` | One browser subscription: `user_id`, `endpoint` (unique), `p256dh`, `auth`, `expiration_time`, `label`, `user_agent`, `created`/`updated`, `last_delivered`, `failure_count` |
| `push_preference` | Per user, which events are pushed: `turn_complete`, `elicitation`, `interest_offers`, `interest_updates` (the last two added by `V3_37`, default `true`), `updated`. No row means all are on |

Load-bearing details:

- **`endpoint` is unique, not unique per user.** One browser subscription belongs to whichever user
  presented its endpoint and keys last; a `PUT` of a stored endpoint moves the row to the caller.
- **`endpoint`, `p256dh` and `auth` are a capability**: whoever holds them can push to that browser.
  They are never returned by the API and never logged.
- **`failure_count`** resets on every delivery and counts every other refusal, retried or not. A row is
  deleted once it reaches `PUSH_MAX_FAILURES`, and at once when the push service answers `403`, `404`
  or `410`. A user keeps at most 20 rows; the oldest are deleted past that.
- **`push_preference` is not part of `user_preferences`**: that endpoint is a full replace, and a client
  unaware of these fields would switch notifications off.
- **No foreign key to a user table**, as elsewhere in the schema.

### Interest agents — five tables (V3_37)

| Table | Holds |
|---|---|
| `interest_scan` | Per user, how far discovery has read their memories: `scanned_through` (newest `user_memory.updated` considered), `last_offered`, `status`, `attempts` |
| `interest_offer` | A topic offered to a user: `agent_type`, `topic_key`, `label`, `query`, `rationale`, `source_memory_ids` (`uuid[]`), `confidence`, `status`, `created`, `expires`, `decided` |
| `interest_agent` | An accepted agent: `offer_id`, `agent_type`, `topic_key`, `label`, `query`, `chat_group_id`, `cadence`, `status`, `next_run_at`, `last_run_at`, `last_update_at`, `last_failure_at`, `failures` |
| `interest_update` | One run that delivered news: `agent_id`, `label`, `chat_id` (unique), `item_count`. The id is the run id and the push dedupe key |
| `interest_agent_item` | A URL an agent has seen, as `url_hash` (SHA-256 hex of the normalized URL) and `first_seen` |

Load-bearing details:

- **Every table carries `user_id`, and every reference between them includes it** through composite
  `(id, user_id)` foreign keys, as `user_memory_evidence` does.
- **`interest_offer_one_pending_idx`** (partial unique on `user_id` where `PENDING`) makes "one question
  at a time" a database guarantee; **`unique (user_id, topic_key)`** on agents makes a duplicate agent
  impossible.
- **Deleting an agent** cascades its seen items and sets `interest_update.agent_id` and nothing else to
  null: the chats are the user's history and stay recognisable as news, which is why the update row
  copies the label.
- **Only a URL's hash is stored**, never titles or content.
- **No foreign key to `chat`, `chat_group` or `user_memory`**, consistent with the rest of the schema.
  `ChatService.delete` removes a news chat's update row; the forget paths in `UserMemoryService` withdraw
  pending offers whose memories are gone.

### Shared sessions — three tables (V3_42)

- **`chat.kind`** (`CONVERSATION` | `SHARED`, default `CONVERSATION`) separates shared chats from conversations;
  every chat path filters on it.
- **`chat_message.sender_user_id`** records who sent a `USER` row in a shared chat, and is null everywhere else.
- **`shared_session`:** one per shared chat (`chat_id` unique, `on delete cascade` from `chat`), naming its
  `agent_name`. The jsonb columns hold what the agent last reported:
  - `turn_state`: user ids, next and over;
  - `pending_prompt`: the open question, with its A2A task id.
- **`session_participant`:** the seats.
  - `seat >= 0` with `unique (session_id, seat)`; the schema sets no ceiling on participants. An invite creates
    two today.
  - Seat 0 is the inviter, who owns the chat and its stream key.
  - `name` is unique per session, ignoring case.
- **`session_invite`:** names the `agent_name` and an optional `opening_message`. `session_invite_one_pending_idx`
  allows one pending invite between any two users, whichever sent it.
- **`push_preference`** gains `session_invites` and `session_turns`, both default true.

The activity checks in memory reflection and the user lifecycle count a `USER` row for
`coalesce(sender_user_id, chat.user_id)`, so a guest's messages keep the guest active, not the owner.

V3_42 was rewritten in place before release. A local database that applied the earlier `V3_42__create_games`
must drop its `game`, `game_participant` and `game_invite` tables, the `chat.kind`, `chat_message.sender_user_id`
and `push_preference.game_*` columns, and the `flyway_schema_history` row for version `3.42` before startup.

### Chat search indexes (V3_43)

Backs the `q` search on `GET /chats/users/{userId}`, whose query is `lower(column) like '%q%'`.

- **`pg_trgm`** is created by the migration itself, unlike `vector`, `hstore` and `uuid-ossp`. It is a
  *trusted* extension (PG13+), so the database owner can create it without superuser, provided the
  Flyway role has `CREATE` on the database. `docker/init_schema.*` creates it too.
- **`chat_message_message_trgm_idx`** and **`chat_name_trgm_idx`**: `gin (lower(...) gin_trgm_ops)`,
  matching the expression the repository emits. Trigrams rather than full-text search, because a
  search box needs partial words (`unif` → "unifi"), which `tsvector` does not match.
- **`chat_message_chat_id_idx`** on `(chat_id, timestamp)`. `chat_message` had no `chat_id` index
  before, which every `findByChatId` and the search's `exists` need.
- **`chat_user_timestamp_idx`** on `(user_id, timestamp desc, id desc)`, the search's ordering.
- No partial index on `message_type`: the query passes the types as a bind parameter, and Postgres
  cannot use a partial index whose condition depends on one.

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