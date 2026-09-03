package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

// ingested_document.file_data was @Lob byte[], which Hibernate maps to a Postgres large object:
// the column held an oid REFERENCE, not inline bytes. Deleting or replacing a row dropped the
// reference and left the object behind, and nothing in the application ever called lo_unlink -- so
// every ingest cycle since the feature shipped orphaned one object per document. At the time this
// migration was written that was 351,476 orphans: 5.8 GB of data.
//
// A single "select lo_unlink(oid) from pg_largeobject_metadata" holds a lock on every large object
// it touches for the life of the transaction, which overflows Postgres's shared lock table at this
// scale ("out of shared memory", hint: max_locks_per_transaction). Reclaiming in bounded batches
// with a commit after each one keeps the held-lock count constant regardless of orphan count.
//
// This is a Java migration, not a SQL script, because that batching needs a real mid-migration
// commit, and Postgres only allows one from a connection Flyway is not already wrapping in a
// transaction block -- the SQL-script-level "-- flyway:executeInTransaction=false" directive that
// would otherwise disable that wrapping is a Flyway Teams feature, and this project runs OSS
// flyway-core. canExecuteInTransaction() below is the Community-edition equivalent, available per
// Java migration.
public class V3_27__ReclaimIngestedDocumentLargeObjects extends BaseJavaMigration {

    private static final Logger logger = LoggerFactory.getLogger(V3_27__ReclaimIngestedDocumentLargeObjects.class);

    private static final int BATCH_SIZE = 1000;

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        boolean originalAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);

        try (PreparedStatement selectStatement = connection.prepareStatement("select oid from pg_largeobject_metadata limit ?");
             PreparedStatement unlinkStatement = connection.prepareStatement("select lo_unlink(?)")) {

            selectStatement.setInt(1, BATCH_SIZE);

            long processedTotal = 0;
            int processedBatch;

            do {
                processedBatch = 0;

                try (ResultSet largeObjectRows = selectStatement.executeQuery()) {
                    while (largeObjectRows.next()) {
                        long largeObjectOid = largeObjectRows.getLong("oid");

                        unlinkStatement.setLong(1, largeObjectOid);
                        unlinkStatement.execute();

                        processedBatch++;
                    }
                }

                connection.commit();
                processedTotal += processedBatch;

                logger.info("Reclaimed {} orphaned large objects ({} total)", processedBatch, processedTotal);
            } while (processedBatch == BATCH_SIZE);
        } finally {
            connection.setAutoCommit(originalAutoCommit);
        }
    }

    @Override
    public boolean canExecuteInTransaction() {
        return false;
    }
}
