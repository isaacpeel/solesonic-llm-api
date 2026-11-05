-- Shared conversations: several users in one ordinary chat, no A2A agent. A session now has a mode;
-- only AGENT sessions have an agent. A conversation session exists as soon as its owner shares, and
-- its invites carry the session id from the start.

alter table public.shared_session add column mode varchar(32) not null default 'AGENT';
alter table public.shared_session add constraint shared_session_mode_check check (mode in ('AGENT', 'CONVERSATION'));
alter table public.shared_session alter column agent_name drop not null;
alter table public.shared_session add constraint shared_session_agent_check check ((mode = 'AGENT') = (agent_name is not null));
-- Informational only: no foreign key, the source chat may be deleted later.
alter table public.shared_session add column source_chat_id uuid;

alter table public.session_invite alter column agent_name drop not null;

-- The old index allowed one pending invite between two users across ALL sessions. Keep that for
-- legacy agent invites (no session yet) and add a per-session rule for invites into an existing session.
drop index public.session_invite_one_pending_idx;
create unique index session_invite_one_pending_idx
    on public.session_invite (least(inviter_user_id, invitee_user_id), greatest(inviter_user_id, invitee_user_id))
    where status = 'PENDING' and session_id is null;
create unique index session_invite_one_pending_per_session_idx
    on public.session_invite (session_id, invitee_user_id)
    where status = 'PENDING' and session_id is not null;

-- "on delete set null" would turn a pending conversation invite back into a legacy one when its
-- session is deleted. An invite into a session has no meaning without it.
alter table public.session_invite drop constraint session_invite_session_fkey;
alter table public.session_invite
    add constraint session_invite_session_fkey foreign key (session_id) references public.shared_session (id) on delete cascade;

-- Backs the user directory search. Indexes belong to the table owner, so no owner statement is needed.
create index if not exists user_profile_username_lower_idx on public.user_profile (lower(username)) where removed is null;
