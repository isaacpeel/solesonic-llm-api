-- What a memory is about, so a plain chat turn carries the memories relevant to it rather than all of
-- them. Kept on the row rather than in vector_store: retrieval there serves documents by entitlement,
-- and a memory placed there could come back as one. Here supersession, forgetting and deletion need
-- no second store kept in step.
--
-- The vector is untyped so a change of embedding model needs no migration; embedding_model says which
-- model wrote it and only vectors of the configured model are compared. embedding_hash is md5 of the
-- content that was embedded, so an edited memory is found stale without anything having to clear it.
--
-- No vector index: retrieval is always scoped to one user's few hundred rows at most, which
-- user_memory_user_status_idx narrows to exactly, while an approximate index scanned before that
-- filter would return fewer than asked.
alter table public.user_memory
    add column embedding       vector,
    add column embedding_model varchar(128),
    add column embedding_hash  varchar(32);
