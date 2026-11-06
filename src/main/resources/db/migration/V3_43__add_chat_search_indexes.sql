-- Trusted extension (PG13+): the database owner can create it without superuser.
create extension if not exists pg_trgm;

-- chat_message has never had a chat_id index; every findByChatId and the search's exists() need it.
create index chat_message_chat_id_idx on public.chat_message (chat_id, timestamp);

-- Substring search, matching the lower(...) like '%q%' ChatRepository emits.
create index chat_message_message_trgm_idx
    on public.chat_message using gin (lower(message) gin_trgm_ops);

create index chat_name_trgm_idx
    on public.chat using gin (lower(name) gin_trgm_ops);

-- Search ordering: one user's chats, newest first, id as tiebreaker.
create index chat_user_timestamp_idx on public.chat (user_id, timestamp desc, id desc);
