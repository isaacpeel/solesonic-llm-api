package com.solesonic.service.session;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Work that must only happen if the surrounding transaction commits, such as a push to someone who
 * is about to be told about rows that might yet roll back. Outside a transaction it runs at once.
 */
final class AfterCommit {

    private AfterCommit() {
    }

    static void run(Runnable work) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            work.run();
            return;
        }

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                work.run();
            }
        });
    }
}
