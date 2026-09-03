-- One-time maintenance script, NOT a Flyway migration. Run manually with psql against any database
-- that still carries orphaned Postgres large objects from ingested_document.file_data, back when
-- that column was `@Lob byte[]` (replaced by a plain `bytea` column on ingested_document_content in
-- migration V3_28). Hibernate's @Lob mapped file_data to a large object oid REFERENCE rather than
-- inline bytes; deleting or replacing a row dropped the reference but nothing in the application
-- ever called lo_unlink, so every ingest cycle orphaned one object. By the time this was noticed
-- there were 351,476 orphans totalling roughly 5.8 GB.
--
-- Run this before deploying the app version containing V3_28 on any environment that still has
-- ingested_document in its pre-V3_28 shape: V3_28 drops that table, destroying the last record of
-- which large objects were ever referenced. That said, the sweep below is global (see next
-- paragraph), so it works identically whether ingested_document still exists or not -- running it
-- late only leaves the orphaned bytes sitting unreclaimed on disk in the meantime, it does not make
-- them unreachable.
--
-- The sweep is global and unscoped: it walks every row of pg_largeobject_metadata, not just rows
-- reachable from ingested_document. That is safe only because this application does not use Postgres
-- large objects anywhere else -- chat_attachment and generated_image both store bytes as plain
-- `bytea` columns, never `@Lob`. Confirm that is still true before running this against a database
-- you are not certain about.
--
-- A single `select lo_unlink(oid) from pg_largeobject_metadata` holds a lock on every large object it
-- touches for the life of its transaction, which overflows Postgres's shared lock table at this scale
-- ("out of shared memory", hint: max_locks_per_transaction). The block below batches the sweep and
-- commits after each batch, which keeps the held-lock count constant regardless of orphan count --
-- that requires a real mid-script COMMIT, which is why this was never a Flyway SQL migration: Flyway
-- wraps a plain .sql script in one transaction, and the Community edition (flyway-core, what this
-- project runs) has no equivalent of the Teams-only `-- flyway:executeInTransaction=false` directive.
--
-- Usage:
--   psql "$SPRING_DATASOURCE_URI" -f scripts/reclaim-orphaned-large-objects.sql

do $$
    declare
        batch_size        constant integer := 1000;
        processed_batch            integer;
        processed_total             bigint := 0;
        large_object_oid                oid;
    begin
        loop
            processed_batch := 0;

            for large_object_oid in
                select oid from pg_largeobject_metadata limit batch_size
                loop
                    perform lo_unlink(large_object_oid);
                    processed_batch := processed_batch + 1;
                end loop;

            commit;
            processed_total := processed_total + processed_batch;

            raise notice 'Reclaimed % orphaned large objects (% total)', processed_batch, processed_total;

            exit when processed_batch < batch_size;
        end loop;
    end $$;
