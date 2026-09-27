-- Optional, user-owned folders for conversations. A chat belongs to at most one group, which is
-- what makes the membership a nullable column on chat rather than a join table: the group is a
-- property of the conversation, and a chat can be read without a second query to find its section.
create table public.chat_group
(
    id        uuid                        not null primary key,
    user_id   uuid                        not null,
    name      varchar(255)                not null,
    timestamp timestamp(6) with time zone not null
);

-- Groups are always listed for one user, ordered by name.
create index chat_group_user_id_idx on public.chat_group (user_id, name);

alter table public.chat_group owner to "${DB_OWNER}";

alter table public.chat
    add column chat_group_id uuid;

-- on delete set null, so deleting a group ungroups its conversations rather than deleting them.
-- Losing a section of the sidebar must never lose the chats that were filed under it.
alter table public.chat
    add constraint chat_chat_group_id_fkey
        foreign key (chat_group_id) references public.chat_group (id) on delete set null;

-- Paging the chats of one group reads (user_id, chat_group_id) and orders by timestamp desc, the
-- same deterministic ordering the ungrouped chat list uses.
create index chat_chat_group_id_idx on public.chat (user_id, chat_group_id, timestamp desc);
