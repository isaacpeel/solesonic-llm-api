-- Durable facts, preferences and standing instructions learned about one user. Every row carries
-- user_id, and every reference between these tables carries it too, so a row can never point at
-- another user's row even by mistake. No table here has a foreign key to chat or chat_message,
-- consistent with the rest of the schema: forgetting a conversation is written out in
-- UserMemoryService.forgetChat.
create table public.user_memory
(
    id             uuid                        not null primary key,
    user_id        uuid                        not null,
    kind           varchar(32)                 not null,
    origin         varchar(32)                 not null,
    status         varchar(32)                 not null,
    memory_key     varchar(128),
    content        text                        not null,
    confidence     double precision            not null,
    created        timestamp(6) with time zone not null,
    updated        timestamp(6) with time zone not null,
    last_confirmed timestamp(6) with time zone not null,
    supersedes     uuid,
    constraint user_memory_id_user_id_unique unique (id, user_id),
    constraint user_memory_supersedes_fkey
        foreign key (supersedes, user_id) references public.user_memory (id, user_id)
            on delete set null (supersedes)
);

create index user_memory_user_status_idx on public.user_memory (user_id, status);
create index user_memory_supersedes_idx on public.user_memory (user_id, supersedes);

-- Two active values for one key cannot coexist: a newer value supersedes the older one.
create unique index user_memory_active_key_idx on public.user_memory (user_id, memory_key)
    where status = 'ACTIVE' and memory_key is not null;

-- The USER messages a memory was learned from. A memory whose last evidence row goes is deleted
-- with it, which is what makes deleting a conversation forget what was learned from it.
create table public.user_memory_evidence
(
    memory_id              uuid                        not null,
    user_id                uuid                        not null,
    chat_id                uuid                        not null,
    chat_message_id        uuid                        not null,
    chat_message_timestamp timestamp(6) with time zone not null,
    primary key (memory_id, chat_message_id),
    constraint user_memory_evidence_memory_fkey
        foreign key (memory_id, user_id) references public.user_memory (id, user_id) on delete cascade
);

create index user_memory_evidence_chat_idx on public.user_memory_evidence (user_id, chat_id);
create index user_memory_evidence_message_idx on public.user_memory_evidence (user_id, chat_message_id);

-- How far reflection has read into each conversation.
create table public.chat_memory_reflection
(
    chat_id           uuid                        not null primary key,
    user_id           uuid                        not null,
    reflected_through timestamp(6) with time zone not null,
    status            varchar(32)                 not null,
    attempts          int                         not null,
    updated           timestamp(6) with time zone not null
);

create index chat_memory_reflection_user_idx on public.chat_memory_reflection (user_id);

alter table public.user_memory owner to "${DB_OWNER}";
alter table public.user_memory_evidence owner to "${DB_OWNER}";
alter table public.chat_memory_reflection owner to "${DB_OWNER}";
