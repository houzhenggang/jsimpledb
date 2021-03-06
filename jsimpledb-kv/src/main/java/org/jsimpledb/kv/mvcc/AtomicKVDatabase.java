
/*
 * Copyright (C) 2015 Archie L. Cobbs. All rights reserved.
 */

package org.jsimpledb.kv.mvcc;

import com.google.common.base.Preconditions;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;

import org.jsimpledb.kv.AbstractKVStore;
import org.jsimpledb.kv.CloseableKVStore;
import org.jsimpledb.kv.KVDatabase;
import org.jsimpledb.kv.KVPair;
import org.jsimpledb.kv.KVPairIterator;
import org.jsimpledb.kv.KVStore;
import org.jsimpledb.kv.KVTransaction;
import org.jsimpledb.kv.KeyRange;
import org.jsimpledb.kv.RetryTransactionException;
import org.jsimpledb.kv.util.ForwardingKVStore;
import org.jsimpledb.kv.util.UnmodifiableKVStore;
import org.jsimpledb.util.ByteUtil;
import org.slf4j.LoggerFactory;

/**
 * Wrapper class that presents an {@link AtomicKVStore} view of a {@link KVDatabase},
 * using individual transactions for each operation.
 *
 * <p>
 * Warning: this class is only appropriate for use with {@link KVDatabase}s that implement some form of MVCC;
 * {@link KVDatabase}s that use locking will likely generate conflicts if {@link #snapshot} is used concurrently
 * with other methods.
 *
 * @see SnapshotKVDatabase
 */
public class AtomicKVDatabase extends AbstractKVStore implements AtomicKVStore {

    protected final KVDatabase kvdb;

    /**
     * Constructor.
     *
     * @param kvdb underlying database
     * @throws IllegalArgumentException if {@code kvdb} is null
     */
    public AtomicKVDatabase(KVDatabase kvdb) {
        Preconditions.checkArgument(kvdb != null, "null kvdb");
        this.kvdb = kvdb;
    }

// KVStore

    @Override
    public byte[] get(final byte[] key) {
        return this.doInTransaction(kv -> kv.get(key));
    }

    @Override
    public KVPair getAtLeast(final byte[] minKey) {
        return this.doInTransaction(kv -> kv.getAtLeast(minKey));
    }

    @Override
    public KVPair getAtMost(final byte[] maxKey) {
        return this.doInTransaction(kv -> kv.getAtMost(maxKey));
    }

    @Override
    public KVPairIterator getRange(byte[] minKey, byte[] maxKey, boolean reverse) {
        return new KVPairIterator(this, new KeyRange(minKey != null ? minKey : ByteUtil.EMPTY, maxKey), null, reverse);
    }

    @Override
    public void put(final byte[] key, final byte[] value) {
        this.doInTransaction(new Action<Void>() {
            @Override
            public Void apply(KVStore kv) {
                kv.put(key, value);
                return null;
            }
        });
    }

    @Override
    public void remove(final byte[] key) {
        this.doInTransaction(new Action<Void>() {
            @Override
            public Void apply(KVStore kv) {
                kv.remove(key);
                return null;
            }
        });
    }

    @Override
    public void removeRange(final byte[] minKey, final byte[] maxKey) {
        this.doInTransaction(new Action<Void>() {
            @Override
            public Void apply(KVStore kv) {
                kv.removeRange(minKey, maxKey);
                return null;
            }
        });
    }

    @Override
    public void adjustCounter(final byte[] key, final long amount) {
        this.doInTransaction(new Action<Void>() {
            @Override
            public Void apply(KVStore kv) {
                kv.adjustCounter(key, amount);
                return null;
            }
        });
    }

    @Override
    public byte[] encodeCounter(final long value) {
        return this.doInTransaction(kv -> kv.encodeCounter(value));
    }

    @Override
    public long decodeCounter(final byte[] bytes) {
        return this.doInTransaction(kv -> kv.decodeCounter(bytes));
    }

// AtomicKVStore

    @Override
    @PostConstruct
    public void start() {
        this.kvdb.start();
    }

    @Override
    @PreDestroy
    public void stop() {
        this.kvdb.stop();
    }

    @Override
    public CloseableKVStore snapshot() {
        final KVTransaction kvtx = this.kvdb.createTransaction();
        boolean success = false;
        try {
            try {
                kvtx.setTimeout(Integer.MAX_VALUE);
            } catch (UnsupportedOperationException e) {
                // ignore
            }
            final SnapshotKVStore kvstore = new SnapshotKVStore(kvtx);
            success = true;
            return kvstore;
        } finally {
            if (!success)
                kvtx.rollback();
        }
    }

    @Override
    public void mutate(final Mutations mutations, boolean sync) {
        Preconditions.checkArgument(mutations != null, "null mutations");
        this.doInTransaction(new Action<Void>() {
            @Override
            public Void apply(KVStore kv) {
                Writes.apply(mutations, kv);
                return null;
            }
        });
    }

// Object

    @Override
    public String toString() {
        return this.getClass().getSimpleName() + "[kvdb=" + this.kvdb + "]";
    }

// Internal methods

    private <R> R doInTransaction(Action<R> action) {
        int count = 0;
        while (true) {
            try {
                final KVTransaction kvtx = this.kvdb.createTransaction();
                boolean success = false;
                try {
                    final R result = action.apply(kvtx);
                    success = true;
                    return result;
                } finally {
                    if (success)
                        kvtx.commit();
                    else
                        kvtx.rollback();
                }
            } catch (RetryTransactionException e) {
                if (count++ < 3) {
                    Thread.yield();
                    continue;
                }
                throw e;
            }
        }
    }

// Action

    private interface Action<R> {
        R apply(KVStore kv);
    }

// Snapshot

    private static class SnapshotKVStore extends ForwardingKVStore implements CloseableKVStore {

        private final KVTransaction kvtx;
        private final UnmodifiableKVStore delegate;

        private volatile boolean closed;

        SnapshotKVStore(KVTransaction kvtx) {
            this.kvtx = kvtx;
            this.delegate = new UnmodifiableKVStore(this.kvtx);
        }

        @Override
        protected UnmodifiableKVStore delegate() {
            return this.delegate;
        }

    // Object

        @Override
        protected void finalize() throws Throwable {
            try {
                if (!this.closed)
                    LoggerFactory.getLogger(this.getClass()).warn(this + " leaked without invoking close()");
                this.close();
            } finally {
                super.finalize();
            }
        }

    // Closeable

        @Override
        public void close() {
            this.closed = true;
            this.kvtx.rollback();
        }
    }
}

