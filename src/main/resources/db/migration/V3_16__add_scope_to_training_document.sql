-- Training documents can now be ingested as private to the uploading user rather than shared with
-- everyone. Existing rows have no owner and are shared, so they backfill to GLOBAL with a null
-- user_id -- exactly what they were before the column existed.

alter table public.training_document add column user_id uuid;
alter table public.training_document add column scope varchar(255);

update public.training_document set scope = 'GLOBAL' where scope is null;
