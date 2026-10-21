-- Interest agents: offers discovered from a user's memories, the news agents a user accepts, and
-- what those agents deliver. Every row carries user_id, and every reference between these tables
-- carries it too, so a row can never point at another user's row. No foreign key to chat,
-- chat_group or user_memory, consistent with the rest of the schema: those cascades are written out
-- in code.

-- How far discovery has read one user's memories.
create table public.interest_scan
(
    user_id         uuid                        not null primary key,
    scanned_through timestamp(6) with time zone,
    last_offered    timestamp(6) with time zone,
    status          varchar(32)                 not null,
    attempts        int                         not null,
    updated         timestamp(6) with time zone not null
);

create table public.interest_offer
(
    id                uuid                        not null primary key,
    user_id           uuid                        not null,
    agent_type        varchar(32)                 not null,
    topic_key         varchar(128)                not null,
    label             varchar(120)                not null,
    query             varchar(400)                not null,
    rationale         varchar(500)                not null,
    source_memory_ids uuid[]                      not null,
    confidence        double precision            not null,
    status            varchar(32)                 not null,
    created           timestamp(6) with time zone not null,
    expires           timestamp(6) with time zone not null,
    decided           timestamp(6) with time zone,
    constraint interest_offer_id_user_id_unique unique (id, user_id)
);

-- One question at a time.
create unique index interest_offer_one_pending_idx on public.interest_offer (user_id) where status = 'PENDING';
create index interest_offer_user_topic_idx on public.interest_offer (user_id, topic_key, status);

create table public.interest_agent
(
    id              uuid                        not null primary key,
    user_id         uuid                        not null,
    offer_id        uuid,
    agent_type      varchar(32)                 not null,
    topic_key       varchar(128)                not null,
    label           varchar(120)                not null,
    query           varchar(400)                not null,
    chat_group_id   uuid,
    cadence         varchar(32)                 not null,
    status          varchar(32)                 not null,
    next_run_at     timestamp(6) with time zone not null,
    last_run_at     timestamp(6) with time zone,
    last_update_at  timestamp(6) with time zone,
    last_failure_at timestamp(6) with time zone,
    failures        int                         not null,
    created         timestamp(6) with time zone not null,
    updated         timestamp(6) with time zone not null,
    constraint interest_agent_id_user_id_unique unique (id, user_id),
    constraint interest_agent_user_topic_unique unique (user_id, topic_key),
    constraint interest_agent_offer_fkey
        foreign key (offer_id, user_id) references public.interest_offer (id, user_id)
            on delete set null (offer_id)
);

create index interest_agent_due_idx on public.interest_agent (next_run_at) where status = 'ACTIVE';

-- One row per run that delivered news: the chat it created. The id is the run id and the push
-- dedupe key. Deleting the agent keeps the row, so its chats stay recognisable as news; the label is
-- copied for the same reason.
create table public.interest_update
(
    id         uuid                        not null primary key,
    user_id    uuid                        not null,
    agent_id   uuid,
    label      varchar(120)                not null,
    chat_id    uuid                        not null,
    item_count int                         not null,
    created    timestamp(6) with time zone not null,
    constraint interest_update_chat_unique unique (chat_id),
    constraint interest_update_agent_fkey
        foreign key (agent_id, user_id) references public.interest_agent (id, user_id)
            on delete set null (agent_id)
);

create index interest_update_agent_idx on public.interest_update (user_id, agent_id, created desc);

-- A URL an agent has already considered. Only its hash is kept: dedupe needs nothing more.
create table public.interest_agent_item
(
    agent_id   uuid                        not null,
    user_id    uuid                        not null,
    url_hash   char(64)                    not null,
    first_seen timestamp(6) with time zone not null,
    primary key (agent_id, url_hash),
    constraint interest_agent_item_agent_fkey
        foreign key (agent_id, user_id) references public.interest_agent (id, user_id) on delete cascade
);

create index interest_agent_item_first_seen_idx on public.interest_agent_item (first_seen);

alter table public.push_preference add column interest_offers boolean not null default true;
alter table public.push_preference add column interest_updates boolean not null default true;

alter table public.interest_scan owner to "${DB_OWNER}";
alter table public.interest_offer owner to "${DB_OWNER}";
alter table public.interest_agent owner to "${DB_OWNER}";
alter table public.interest_update owner to "${DB_OWNER}";
alter table public.interest_agent_item owner to "${DB_OWNER}";
