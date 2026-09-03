-- V3_16 backfilled scope to GLOBAL for every row that existed when the column was added, but left
-- the column nullable and never made the writers set it. IngestedDocumentService.queue was the only
-- one that ever did; URI ingestion and Confluence ingestion have written scope = null ever since,
-- and DocumentService.scope(...) masked it by treating null as GLOBAL at embedding time.
--
-- The scoped document collections filter on this column, and a filter never matches a null. Without
-- this backfill every URI- and Confluence-ingested document would drop out of GET /documents/global
-- the moment those endpoints go live -- rows that GET /documents/ingested lists today.
--
-- NOT NULL is the point of the migration, not a tidy-up: with every writer now supplying an explicit
-- scope, a row that reaches the table without one is a bug that should fail loudly at the insert
-- rather than resurface as an invisible document.

update public.ingested_document set scope = 'GLOBAL' where scope is null;

alter table public.ingested_document alter column scope set not null;
