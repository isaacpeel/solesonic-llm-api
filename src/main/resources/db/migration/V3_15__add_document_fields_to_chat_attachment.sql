-- Non-image attachments are extracted to text, split, embedded and retrieved through the vector
-- store rather than described by the vision model. chunk_count is the document-side counterpart of
-- vision_description: null means the extraction pass has not run, which is what keeps it retryable
-- on a later turn.

alter table public.chat_attachment add column chunk_count integer;
alter table public.chat_attachment add column extraction_failure_reason varchar(255);
