create table public.jira_access_token
(
    user_id       uuid not null primary key,
    access_token  text,
    created       timestamp(6) with time zone,
    expires_in    integer,
    refresh_token text,
    scope         text,
    updated       timestamp(6) with time zone
);

alter table public.jira_access_token owner to "solesonic-llm-api";