create table public.status_history
(
    id              uuid not null primary key,
    document_id     uuid,
    document_status varchar(255)
        constraint status_history_document_status_check
            check ((document_status)::text = ANY
                   ((ARRAY ['IN_PROGRESS'::character varying,
                       'PREPARING'::character varying,
                       'KEYWORD_ENRICHING'::character varying,
                       'METADATA_ENRICHING'::character varying,
                       'TOKEN_SPLITTING'::character varying,
                       'QUEUED'::character varying,
                       'COMPLETED'::character varying,
                       'FAILED'::character varying])::text[])),
    timestamp       timestamp(6) with time zone
);

alter table public.status_history owner to "${DB_OWNER}";