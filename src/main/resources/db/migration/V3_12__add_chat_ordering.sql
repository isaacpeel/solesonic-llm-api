-- Manual ordering for conversations. Two columns rather than one, because a chat appears in two
-- independently ordered lists: the user's whole conversation list, and — when it is filed under a
-- group — that group's list. A single column would make a move in the sidebar reshuffle the group,
-- and a move inside a group reshuffle the sidebar.
--
-- Both are nullable, and null is the state of every chat until a client places one by hand:
-- ordering falls back to timestamp desc, which is what keeps a newly created conversation at the
-- top of the list without anything having to assign it a number.
alter table public.chat
    add column sort_order       integer,
    add column group_sort_order integer;

-- The user's whole list, ordered by hand-placed position first. Partial, because the placed chats
-- are the prefix of that list and the rest is read by timestamp.
create index chat_sort_order_idx on public.chat (user_id, sort_order)
    where sort_order is not null;

-- The same, within one group.
create index chat_group_sort_order_idx on public.chat (user_id, chat_group_id, group_sort_order)
    where group_sort_order is not null;
