-- Each user's own model-server API key, which every model call made for them carries in place of the
-- shared service key. Registered by an administrator and never returned. The key is encrypted with
-- the same BytesEncryptor as the stored Atlassian and Google tokens; key_hint is its last characters,
-- for telling keys apart in the admin listing.
create table public.user_llm_key
(
    user_id    uuid                        not null primary key,
    api_key    bytea                       not null,
    key_hint   varchar(8)                  not null,
    created    timestamp(6) with time zone not null,
    updated    timestamp(6) with time zone not null,
    updated_by uuid                        not null
);

alter table public.user_llm_key owner to "${DB_OWNER}";
