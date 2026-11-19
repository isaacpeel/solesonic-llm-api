-- Chats an A2A agent created for a user through POST /agent-updates (the jobs agent's alerts). One row
-- per delivered update: (agent, idempotency_key) is what makes a retried delivery return the first
-- chat instead of writing and pushing a second one, and the per-user rows are what the daily rate
-- limit counts. No foreign key to chat, consistent with the rest of the schema.
create table public.agent_update
(
    id              uuid                        not null primary key,
    user_id         uuid                        not null,
    agent           varchar(64)                 not null,
    idempotency_key varchar(128)                not null,
    chat_id         uuid                        not null,
    created         timestamp(6) with time zone not null,
    constraint agent_update_agent_key_unique unique (agent, idempotency_key)
);

create index agent_update_user_created_idx on public.agent_update (user_id, created);

alter table public.agent_update owner to "${DB_OWNER}";

alter table public.push_preference add column job_updates boolean not null default true;
