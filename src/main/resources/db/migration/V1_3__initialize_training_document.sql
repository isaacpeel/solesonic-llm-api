create table public.training_document
(
    id           uuid not null primary key,
    content_type varchar(255),
    created      timestamp(6) with time zone,
    file_data    oid,
    file_name    varchar(255),
    updated      timestamp(6) with time zone
);

alter table public.training_document owner to "solesonic-llm-api";