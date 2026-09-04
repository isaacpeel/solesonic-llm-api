
truncate table public.vector_store;
truncate table public.status_history;

drop table if exists public.ingested_document cascade;

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

create table if not exists public.ingested_document_content
(
    ingested_document_id uuid   not null primary key
        references public.ingested_document (id) on delete cascade,
    data                 bytea  not null,
    size_bytes           bigint not null
);

create table if not exists public.document_entitlement
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

create index if not exists document_entitlement_principal_idx
    on public.document_entitlement (principal_type, principal_id, grant_kind);

create index if not exists document_entitlement_document_idx
    on public.document_entitlement (ingested_document_id);

create index if not exists ingested_document_chat_id_meta_idx
    on public.ingested_document ((metadata ->> 'CHAT_ID'));

create index if not exists ingested_document_chat_attachment_id_meta_idx
    on public.ingested_document ((metadata ->> 'CHAT_ATTACHMENT_ID'));

create index if not exists vector_store_metadata_path_idx
    on public.vector_store using gin ((metadata::jsonb) jsonb_path_ops);

alter table public.ingested_document owner to "${DB_OWNER}";
alter table public.ingested_document_content owner to "${DB_OWNER}";
alter table public.document_entitlement owner to "${DB_OWNER}";
