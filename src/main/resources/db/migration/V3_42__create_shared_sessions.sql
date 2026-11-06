-- Shared sessions: several users working with one A2A agent in one chat. A shared session's chat is a
-- chat of its own kind, owned by the inviter and reachable by its participants only through
-- /shared-sessions: every other chat path filters on kind, so a SHARED chat is not found there.

alter table public.chat add column kind varchar(32) not null default 'CONVERSATION';
alter table public.chat add constraint chat_kind_check check (kind in ('CONVERSATION', 'SHARED'));

-- Who sent a USER message in a shared chat. Null for every other row: a conversation has one owner.
alter table public.chat_message add column sender_user_id uuid;

create table public.shared_session
(
    id             uuid                        not null primary key,
    chat_id        uuid                        not null,
    agent_name     varchar(64)                 not null,
    status         varchar(32)                 not null,
    turn_state     jsonb,
    pending_prompt jsonb,
    last_run_id    uuid,
    created        timestamp(6) with time zone not null,
    updated        timestamp(6) with time zone not null,
    constraint shared_session_chat_unique unique (chat_id),
    constraint shared_session_chat_fkey foreign key (chat_id) references public.chat (id) on delete cascade,
    constraint shared_session_status_check check (status in ('ACTIVE', 'COMPLETED', 'CLOSED', 'EXPIRED'))
);

create index shared_session_status_updated_idx on public.shared_session (status, updated);

create table public.session_participant
(
    id         uuid                        not null primary key,
    session_id uuid                        not null,
    user_id    uuid                        not null,
    seat       smallint                    not null,
    name       varchar(64)                 not null,
    joined     timestamp(6) with time zone not null,
    left_at    timestamp(6) with time zone,
    constraint session_participant_session_fkey foreign key (session_id) references public.shared_session (id) on delete cascade,
    constraint session_participant_user_unique unique (session_id, user_id),
    constraint session_participant_seat_unique unique (session_id, seat),
    constraint session_participant_seat_check check (seat >= 0)
);

create unique index session_participant_name_idx on public.session_participant (session_id, lower(name));
create index session_participant_user_idx on public.session_participant (user_id);

create table public.session_invite
(
    id              uuid                        not null primary key,
    inviter_user_id uuid                        not null,
    invitee_user_id uuid                        not null,
    agent_name      varchar(64)                 not null,
    opening_message text,
    status          varchar(32)                 not null,
    session_id      uuid,
    created         timestamp(6) with time zone not null,
    expires         timestamp(6) with time zone not null,
    decided         timestamp(6) with time zone,
    constraint session_invite_session_fkey foreign key (session_id) references public.shared_session (id) on delete set null,
    constraint session_invite_users_check check (inviter_user_id <> invitee_user_id),
    constraint session_invite_status_check check (status in ('PENDING', 'ACCEPTED', 'DECLINED', 'CANCELLED', 'EXPIRED'))
);

-- One open invite between any two users, whichever of them sent it.
create unique index session_invite_one_pending_idx
    on public.session_invite (least(inviter_user_id, invitee_user_id), greatest(inviter_user_id, invitee_user_id))
    where status = 'PENDING';
create index session_invite_invitee_idx on public.session_invite (invitee_user_id) where status = 'PENDING';
create index session_invite_inviter_idx on public.session_invite (inviter_user_id) where status = 'PENDING';

alter table public.push_preference add column session_invites boolean not null default true;
alter table public.push_preference add column session_turns boolean not null default true;

alter table public.shared_session owner to "${DB_OWNER}";
alter table public.session_participant owner to "${DB_OWNER}";
alter table public.session_invite owner to "${DB_OWNER}";
