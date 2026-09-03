-- The entitlement model. ingested_document stops encoding ownership as a nullable-column
-- discriminated union -- scope / user_id / chat_id, where "exactly one owning column is set and it
-- agrees with scope" was enforced nowhere -- and records it as explicit grant rows instead. The
-- uploaded bytes move off the row at the same time.
--
-- This is a RESET, not a migration. Every ingested document and every chunk is discarded and the
-- corpus is re-ingested from scratch, so there is no backfill, no dual-write phase and no staged
-- column drop: the tables are dropped and recreated in their target shape. That is only safe
-- because nothing of the old data survives to disagree with the new columns.
--
-- The large objects referenced by the old file_data column were unlinked in the previous migration
-- (V3_27), in bounded batches -- doing that here, in this transaction, would hold a lock per object
-- for the life of the transaction and overflow the shared lock table at the scale this corpus had
-- grown to. Ordering across the two migrations is load-bearing for the same reason the original
-- single-script ordering was: the large objects must be gone before ingested_document is dropped,
-- because dropping the table destroys the only record of which objects were ever referenced.

-- 1. Discard the corpus.
--
-- vector_store and status_history are emptied rather than dropped: both are shaped correctly
-- already and are recreated by nothing here. ingested_document is dropped outright because its
-- shape is what this migration exists to change. No foreign key references either table, so there
-- is nothing to cascade and nothing that a cascade would silently take with it.
truncate table public.vector_store;
truncate table public.status_history;

drop table if exists public.ingested_document;

-- 2. The descriptor.
--
-- What is NOT here is the point: no scope, no user_id, no chat_id, no file_data. Ownership is
-- document_entitlement's job and bytes are ingested_document_content's, so no future ownership
-- shape -- a team, an org, a role -- adds a column to this table.
--
-- document_source is NOT NULL because every writer sets it and because the scheduler now keys off
-- it (StatusHistoryRepository.findInProgress): a row arriving without one must fail at the insert
-- rather than resurface as a document the ingest slot mysteriously never waits for. That is the
-- V3_25 lesson applied ahead of the bug rather than after it.
--
-- metadata stays jsonb and stays provenance only -- ORIGINAL_FILE_NAME, FILE_SIZE_BYTES, SOURCE_URI,
-- CONFLUENCE_PAGE_ID, CONFLUENCE_PAGE_VERSION, CHAT_ID, CHAT_ATTACHMENT_ID, REPLACED_BY_ID. Where a
-- document may be retrieved is now a different fact from where it came from, and only the second
-- lives here.
create table public.ingested_document
(
    id              uuid         not null primary key,
    file_name       varchar(255),
    content_type    varchar(255),
    document_source varchar(255) not null,
    created         timestamp(6) with time zone,
    updated         timestamp(6) with time zone,
    metadata        jsonb
);

-- 3. The bytes.
--
-- Separate table, 1:1, absent for rows whose bytes live elsewhere -- a URI document before its
-- fetch, and a chat attachment whose only copy is on chat_attachment. That absence is the same
-- "bytes live elsewhere" state an empty fileData used to represent, made explicit.
--
-- bytea, NOT oid, and the entity field must never carry @Lob: that annotation is what produced the
-- large object leak reclaimed in the previous migration. A row delete reclaims bytea inline, and
-- ON DELETE CASCADE means dropping a document takes its bytes with it without anything remembering
-- to.
--
-- Splitting the bytes off is also what retires SUMMARY_PROJECTION: a listing can select whole
-- entities now without reading megabytes, so listings stop needing a JPQL constructor expression
-- and can filter on anything -- including a join to document_entitlement.
create table public.ingested_document_content
(
    ingested_document_id uuid   not null primary key
        references public.ingested_document (id) on delete cascade,
    data                 bytea  not null,
    size_bytes           bigint not null
);

-- 4. The grants.
--
-- One row per (document, principal, kind). grant_kind separates RETRIEVE -- who may have this
-- document come back from a search -- from MANAGE -- whose library it appears in. They were the same
-- fact before, which is why "every document I have ever uploaded to any conversation" had no query.
--
-- principal_id is text, not uuid: a GLOBAL grant has no owner and uses the '-' sentinel, and a ROLE
-- grant holds a role name. granted_by is what records which admin added a global document, since
-- under decision 9 they keep no personal grant.
--
-- The unique constraint is what makes a double grant a failed insert rather than a duplicated row in
-- a listing -- the guarantee that lets the single-principal listing join without fanning out.
create table public.document_entitlement
(
    id                   uuid         not null primary key,
    ingested_document_id uuid         not null
        references public.ingested_document (id) on delete cascade,
    principal_type       varchar(32)  not null,
    principal_id         varchar(255) not null,
    grant_kind           varchar(16)  not null,
    granted_at           timestamp(6) with time zone not null,
    granted_by           uuid,
    constraint document_entitlement_unique
        unique (ingested_document_id, principal_type, principal_id, grant_kind)
);

-- Serves the single-principal listing (§6.1) as a btree seek that merges with the ordered scan on
-- created, which is the whole reason entitlements are a table rather than a JSON array on the row.
create index document_entitlement_principal_idx
    on public.document_entitlement (principal_type, principal_id, grant_kind);

-- Serves the reverse direction: every grant of one document, which is what replacing a document's
-- RETRIEVE set reads first.
create index document_entitlement_document_idx
    on public.document_entitlement (ingested_document_id);

-- The teardown queries match on provenance, not on ownership, and they are native queries reading
-- the json column directly. These are the two hot keys: deleting one attachment, and deleting a
-- whole conversation.
create index ingested_document_chat_id_meta_idx
    on public.ingested_document ((metadata ->> 'CHAT_ID'));

create index ingested_document_chat_attachment_id_meta_idx
    on public.ingested_document ((metadata ->> 'CHAT_ATTACHMENT_ID'));

-- Retrieval filters reach chunk entitlements as a jsonpath predicate --
-- metadata::jsonb @@ '$."entitlements" == "user:..."'::jsonpath -- which Postgres evaluates in lax
-- mode, auto-unwrapping the array so a single eq() matches any member. jsonb_path_ops is the
-- operator class that supports @@; the expression matches the cast the converter already emits.
create index vector_store_metadata_path_idx
    on public.vector_store using gin ((metadata::jsonb) jsonb_path_ops);

alter table public.ingested_document owner to "${DB_OWNER}";
alter table public.ingested_document_content owner to "${DB_OWNER}";
alter table public.document_entitlement owner to "${DB_OWNER}";
