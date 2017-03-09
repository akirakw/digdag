package io.digdag.core.database;

import com.google.inject.Inject;
import org.skife.jdbi.v2.DBI;
import org.skife.jdbi.v2.Handle;
import org.skife.jdbi.v2.exceptions.TransactionFailedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import javax.sql.DataSource;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.Locale.ENGLISH;

public class ThreadLocalTransactionManager
        implements TransactionManager
{
    private static final Logger logger = LoggerFactory.getLogger(TransactionManager.class);

    private final ThreadLocal<Transaction> threadLocalTransaction = new ThreadLocal<>();
    private final DataSource ds;

    private static class LazyTransaction
            implements Transaction, AutoCloseable
    {
        private static enum State
        {
            ACTIVE,
            ABORTED,
            COMMITTED;
        }

        private final DataSource ds;
        private final boolean autoAutoCommit;
        private Handle handle;
        private State state = State.ACTIVE;

        LazyTransaction(DataSource ds)
        {
            this(ds, false);
        }

        //
        // DO NOT USE autoAutoCommit IN main CODE.
        //
        LazyTransaction(DataSource ds, boolean autoAutoCommit)
        {
            this.ds = checkNotNull(ds);
            this.autoAutoCommit = autoAutoCommit;
        }

        @Override
        public Handle getHandle(ConfigMapper configMapper)
        {
            if (state != State.ACTIVE) {
                throw new IllegalStateException("Transaction is already " + state.name().toLowerCase(ENGLISH));
            }

            if (handle == null) {
                DBI dbi = new DBI(ds);
                ConfigKeyListMapper cklm = new ConfigKeyListMapper();
                dbi.registerMapper(new DatabaseProjectStoreManager.StoredProjectMapper(configMapper));
                dbi.registerMapper(new DatabaseProjectStoreManager.StoredRevisionMapper(configMapper));
                dbi.registerMapper(new DatabaseProjectStoreManager.StoredWorkflowDefinitionMapper(configMapper));
                dbi.registerMapper(new DatabaseProjectStoreManager.StoredWorkflowDefinitionWithProjectMapper(configMapper));
                dbi.registerMapper(new DatabaseProjectStoreManager.WorkflowConfigMapper());
                dbi.registerMapper(new DatabaseProjectStoreManager.IdNameMapper());
                dbi.registerMapper(new DatabaseProjectStoreManager.ScheduleStatusMapper());
                dbi.registerMapper(new DatabaseQueueSettingStoreManager.StoredQueueSettingMapper(configMapper));
                dbi.registerMapper(new DatabaseScheduleStoreManager.StoredScheduleMapper(configMapper));
                dbi.registerMapper(new DatabaseSessionStoreManager.StoredTaskMapper(configMapper));
                dbi.registerMapper(new DatabaseSessionStoreManager.ArchivedTaskMapper(cklm, configMapper));
                dbi.registerMapper(new DatabaseSessionStoreManager.ResumingTaskMapper(cklm, configMapper));
                dbi.registerMapper(new DatabaseSessionStoreManager.StoredSessionMapper(configMapper));
                dbi.registerMapper(new DatabaseSessionStoreManager.StoredSessionWithLastAttemptMapper(configMapper));
                dbi.registerMapper(new DatabaseSessionStoreManager.StoredSessionAttemptMapper(configMapper));
                dbi.registerMapper(new DatabaseSessionStoreManager.StoredSessionAttemptWithSessionMapper(configMapper));
                dbi.registerMapper(new DatabaseSessionStoreManager.TaskStateSummaryMapper());
                dbi.registerMapper(new DatabaseSessionStoreManager.TaskAttemptSummaryMapper());
                dbi.registerMapper(new DatabaseSessionStoreManager.SessionAttemptSummaryMapper());
                dbi.registerMapper(new DatabaseSessionStoreManager.StoredSessionMonitorMapper(configMapper));
                dbi.registerMapper(new DatabaseSessionStoreManager.StoredDelayedSessionAttemptMapper());
                dbi.registerMapper(new DatabaseSessionStoreManager.TaskRelationMapper());
                dbi.registerMapper(new DatabaseSessionStoreManager.InstantMapper());
                dbi.registerMapper(new DatabaseSecretStore.ScopedSecretMapper());
                dbi.registerMapper(new DatabaseTaskQueueServer.ImmutableTaskQueueLockMapper());

                dbi.registerArgumentFactory(configMapper.getArgumentFactory());
                handle = dbi.open();

                try {
                    handle.getConnection().setAutoCommit(autoAutoCommit);
                }
                catch (SQLException ex) {
                    throw new TransactionFailedException("Failed to set auto commit: " + autoAutoCommit, ex);
                }
                if (!autoAutoCommit) {
                    handle.begin();
                }
            }
            return handle;
        }

        @Override
        public void commit()
        {
            if (handle == null) {
                return;
            }
            if (state != State.ACTIVE) {
                throw new IllegalStateException("Committing " + state.name().toLowerCase(ENGLISH) + " is not allowed");
            }

            // Validate connection before COMMIT. This is necessary because PostgreSQL actually runs
            // ROLLBACK silently when COMMIT is issued if a statement failed during the transaction.
            // It means that BasicDatabaseStoreManager.transaction method doesn't throw exceptions
            // but the actual transaction is rolled back. Here checks state of the transaction by
            // calling org.postgresql.jdbc.PgConnection.isValid method.
            //
            // Here assumes that HikariDB doesn't override isValid.
            boolean isValid;
            try {
                isValid = handle.getConnection().isValid(30);
            }
            catch (SQLException ex) {
                throw new TransactionFailedException(
                        "Can't validate a transaction before commit", ex);
            }
            if (!isValid) {
                throw new TransactionFailedException(
                        "Trying to commit a transaction that is already aborted. "  +
                        "Because current transaction is aborted, commands including " +
                        "commit are ignored until end of transaction block.");
            }
            else {
                handle.commit();
            }

            state = State.COMMITTED;
        }

        @Override
        public void abort()
        {
            if (handle == null) {
                return;
            }
            if (state == State.COMMITTED) {
                throw new IllegalStateException("Aborting committed transaction is not allowed");
            }
            if (!autoAutoCommit) {
                handle.rollback();
            }
            state = State.ABORTED;
            // TODO Should call close()?
        }

        @Override
        public void reset()
        {
            abort();
            state = State.ACTIVE;
        }

        @Override
        public void close()
                throws Exception
        {
            if (handle != null) {
                handle.close();
            }
        }
    }

    @Inject
    public ThreadLocalTransactionManager(DataSource ds)
    {
        this(ds, false);
    }

    ThreadLocalTransactionManager(DataSource ds, boolean autoAutoCommit)
    {
        this.ds = checkNotNull(ds);
        if (autoAutoCommit) {
            LazyTransaction transaction = new LazyTransaction(ds, true);
            threadLocalTransaction.set(transaction);
        }
    }

    @Override
    public Handle getHandle(ConfigMapper configMapper)
    {
        Transaction transaction = threadLocalTransaction.get();
        if (transaction == null) {
            throw new IllegalStateException("Not in transaction");
        }
        return transaction.getHandle(configMapper);
    }

    @Override
    public <T> T begin(ThrowableSupplier<T> func)
            throws Exception
    {
        if (threadLocalTransaction.get() != null) {
            throw new IllegalStateException("Nested transaction is not allowed");
        }

        try (LazyTransaction transaction = new LazyTransaction(ds)) {
            threadLocalTransaction.set(transaction);
            boolean committed = false;
            try {
                T result = func.get();
                transaction.commit();
                committed = true;
                return result;
            }
            finally {
                threadLocalTransaction.set(null);
                if (!committed) {
                    transaction.abort();
                }
            }
        }
    }

    @Override
    public void reset()
    {
        Transaction transaction = threadLocalTransaction.get();
        if (transaction == null) {
            throw new IllegalStateException("Not in transaction");
        }
        transaction.reset();
    }

    @Override
    public <T> T autoCommit(ThrowableSupplier<T> func)
            throws Exception
    {
        if (threadLocalTransaction.get() != null) {
            throw new IllegalStateException("Nested transaction is not allowed");
        }

        LazyTransaction transaction = new LazyTransaction(ds, true);
        threadLocalTransaction.set(transaction);
        try {
            return func.get();
        }
        finally {
            threadLocalTransaction.set(null);
        }
    }
}
