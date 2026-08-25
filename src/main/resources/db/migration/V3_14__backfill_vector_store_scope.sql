-- Scoped retrieval filters every similarity search on metadata->>'scope'. A JSON path filter never
-- matches a key that is absent, so every row written before scoping existed would drop out of
-- retrieval the moment the filters go live. Backfill them to GLOBAL, which is what they have always
-- effectively been: shared training material with no owner.
--
-- No schema change. The metadata column stays 'json' as Spring AI's own pgvector schema defines it;
-- filter expressions are converted to Postgres JSON path predicates against that column as-is.

update public.vector_store
   set metadata = coalesce(metadata, '{}'::json)::jsonb || '{"scope": "GLOBAL"}'::jsonb
 where metadata is null
    or not (metadata::jsonb ? 'scope');
