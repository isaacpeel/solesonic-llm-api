ALTER TABLE public.training_document ADD COLUMN document_source VARCHAR(255);
ALTER TABLE public.training_document ADD COLUMN metadata JSONB;
CREATE INDEX idx_metadata ON public.training_document USING GIN (metadata);

