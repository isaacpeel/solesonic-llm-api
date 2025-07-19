create table public.chat
(
    id          uuid not null primary key,
    timestamp   timestamp(6) with time zone,
    user_id     uuid
);

alter table public.chat owner to "solesonic-llm-api";