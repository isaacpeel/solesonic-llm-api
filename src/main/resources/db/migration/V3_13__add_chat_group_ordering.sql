-- Manual ordering for the sections themselves, alongside the two orderings V3_12 gave conversations.
-- One column, not two: a group appears in exactly one list — the caller's list of sections — where a
-- chat appears both in the sidebar and, when filed, inside a group.
--
-- Nullable, and null is the state of every group until a client places one by hand: ordering falls
-- back to name, which is what keeps a group nobody has arranged exactly where it was before this
-- column existed, and a newly created one among the unplaced rather than at the top.
alter table public.chat_group
    add column sort_order integer;

-- The caller's sections, ordered by hand-placed rank first. Partial, because the placed groups are
-- the prefix of that list and the rest is read by name off chat_group_user_id_idx.
--
-- Named for the table and both its columns rather than the shorter chat_group_sort_order_idx, which
-- V3_12 already took for chat (user_id, chat_group_id, group_sort_order). Index names are unique per
-- schema, not per table, and "chat_group" reads as either the table or the chat column it indexes.
create index chat_group_user_id_sort_order_idx on public.chat_group (user_id, sort_order)
    where sort_order is not null;
