-- Lifecycle state for chat document attachments, and a one-time repair of the attachments V3_27 orphaned.
--
-- V3_27 truncated vector_store and recreated ingested_document but left chat_attachment.chunk_count
-- set, and ingestion skipped any attachment with a chunk count, so those documents were never
-- re-indexed. A rowless attachment with a chunk count is ambiguous, though: it is equally what a
-- promoted document (promotion strips CHAT_ATTACHMENT_ID) or a document removed from the
-- conversation (the attachment row is deliberately kept) looks like. Only the wipe has a timestamp,
-- and V3_27 dropped every ingested_document row, so an attachment sent before it cannot have been
-- promoted or removed since. Everything else is marked detached and is never touched again.
--
-- index_detached      the document left the conversation on purpose (or is ambiguous legacy); never re-indexed
-- index_claimed_at    lease held by the worker indexing this attachment; stale after a cutoff
-- index_attempted_at  last time a worker claimed it, kept after release, used for retry cooldown

alter table public.chat_attachment
    add column if not exists index_detached     boolean not null default false,
    add column if not exists index_claimed_at   timestamp(6) with time zone,
    add column if not exists index_attempted_at timestamp(6) with time zone;

do $$
declare
    -- Absent history (or no 3.27 row) means nothing counts as pre-wipe, so every ambiguous row is
    -- detached: the safe direction, which can only leave a document unindexed, never resurrect one.
    wipe timestamptz := '-infinity';
begin
    if to_regclass('public.flyway_schema_history') is not null then
        -- installed_on is a timestamp without zone in the server's zone; the one-day margin below
        -- absorbs any skew from that conversion.
        select coalesce(max(installed_on)::timestamptz, '-infinity')
          into wipe
          from public.flyway_schema_history
         where version = '3.27';
    end if;

    -- 1. Detach the ambiguous: rowless, still claiming chunks, and not provably sent before the wipe.
    --    Must run before step 2, which relies on these having been taken out.
    update public.chat_attachment ca
       set index_detached = true
     where ca.chunk_count is not null
       and not ca.index_detached
       and not exists (select 1
                         from public.ingested_document d
                        where d.metadata ->> 'CHAT_ATTACHMENT_ID' = ca.id::text)
       and coalesce((select cm."timestamp" from public.chat_message cm where cm.id = ca.chat_message_id),
                    ca.created) >= wipe - interval '1 day';

    -- 2. What is left rowless is a pre-wipe orphan: stop claiming it is indexed, so the healer
    --    picks it up and the UI stops reporting it as retrievable.
    update public.chat_attachment ca
       set chunk_count = null,
           extraction_failure_reason = null
     where ca.chunk_count is not null
       and not ca.index_detached
       and not exists (select 1
                         from public.ingested_document d
                        where d.metadata ->> 'CHAT_ATTACHMENT_ID' = ca.id::text);
end
$$;
