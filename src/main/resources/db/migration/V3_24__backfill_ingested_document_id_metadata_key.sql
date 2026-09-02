-- Part of the "training" -> "ingestion" terminology rename (see V3_23). VectorStoreRepository and
-- RetrievalLoggingPostProcessor now read the 'INGESTED_DOCUMENT_ID' key; a filter never matches a key
-- that is absent or under its old name, so every existing row still carrying 'TRAINING_DOCUMENT_ID'
-- would silently drop out of findByIngestedDocumentId and lose its retrieval-log tie back to the
-- source document the moment the new key name goes live. Same hazard V3_14 backfilled for 'scope'.
--
-- No schema change. The metadata column stays 'json' as Spring AI's own pgvector schema defines it.

update public.vector_store
   set metadata = ((metadata::jsonb - 'TRAINING_DOCUMENT_ID')
                       || jsonb_build_object('INGESTED_DOCUMENT_ID', metadata::jsonb -> 'TRAINING_DOCUMENT_ID'))::json
 where metadata::jsonb ? 'TRAINING_DOCUMENT_ID';
