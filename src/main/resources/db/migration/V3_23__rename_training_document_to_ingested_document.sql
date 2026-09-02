-- Part of the "training" -> "ingestion" terminology rename: nothing here trains a model, these rows
-- track documents queued, chunked, embedded and indexed for retrieval-augmented generation. Renaming
-- the table alongside the entity/repository rename (Story 1) so code and schema never disagree.

alter table public.training_document rename to ingested_document;
alter table public.ingested_document rename constraint training_document_pkey to ingested_document_pkey;
