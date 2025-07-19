create table public.user_preferences
(
    user_id uuid not null primary key,
    created timestamp(6) with time zone,
    model   varchar(255),
    updated timestamp(6) with time zone
);

alter table public.user_preferences owner to "solesonic-llm-api";