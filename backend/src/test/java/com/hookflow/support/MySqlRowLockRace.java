package com.hookflow.support;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.springframework.http.HttpStatusCode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

public final class MySqlRowLockRace {
    private final JdbcTemplate jdbc;
    private final JdbcTemplate observer;
    private final Supplier<TransactionTemplate> transactions;

    public MySqlRowLockRace(JdbcTemplate jdbc, JdbcTemplate observer, Supplier<TransactionTemplate> transactions) {
        this.jdbc = jdbc;
        this.observer = observer;
        this.transactions = transactions;
        observer.setQueryTimeout(3);
    }

    public Object ordered(long endpoint, Supplier<?> winner, Supplier<?> loser, Runnable beforeCommit) throws Exception {
        CountDownLatch changed = new CountDownLatch(1), release = new CountDownLatch(1);
        ExecutorService workers = new ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(2));
        AtomicReference<Long> blocker = new AtomicReference<>(), waiter = new AtomicReference<>();
        Future<?> first = workers.submit(() -> transactions.get().execute(tx -> {
            blocker.set(jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class));
            jdbc.queryForObject("SELECT id FROM endpoint WHERE id=? FOR UPDATE", Long.class, endpoint);
            Object result = winner.get();
            if (result instanceof RuntimeException || result instanceof HttpStatusCode) tx.setRollbackOnly();
            changed.countDown();
            try { assertTrue(release.await(12, TimeUnit.SECONDS), "获胜事务释放必须有界"); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
            return result;
        }));
        try {
            assertTrue(changed.await(10, TimeUnit.SECONDS), "获胜服务必须在竞争开始前执行完毕");
            Future<?> second = workers.submit(() -> transactions.get().execute(tx -> {
                waiter.set(jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class));
                Object result = loser.get();
                if (result instanceof RuntimeException || result instanceof HttpStatusCode) tx.setRollbackOnly();
                return result;
            }));
            awaitWait(waiter, blocker, "endpoint", endpoint, second);
            beforeCommit.run();
            release.countDown();
            first.get(10, TimeUnit.SECONDS);
            return second.get(10, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(15, TimeUnit.SECONDS), "竞争线程必须回收");
        }
    }

    public void awaitWait(AtomicReference<Long> waiter, AtomicReference<Long> blocker,
            String table, long rowId, Future<?> future) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
        while (System.nanoTime() < deadline) {
            if (waiter.get() != null && blocker.get() != null && observer.queryForObject(
                    "SELECT COUNT(*) FROM performance_schema.data_lock_waits w "
                            + "JOIN performance_schema.data_locks r ON r.ENGINE_LOCK_ID=w.REQUESTING_ENGINE_LOCK_ID AND r.ENGINE=w.ENGINE "
                            + "JOIN performance_schema.data_locks b ON b.ENGINE_LOCK_ID=w.BLOCKING_ENGINE_LOCK_ID AND b.ENGINE=w.ENGINE "
                            + "JOIN performance_schema.threads rt ON rt.THREAD_ID=w.REQUESTING_THREAD_ID "
                            + "JOIN performance_schema.threads bt ON bt.THREAD_ID=w.BLOCKING_THREAD_ID "
                            + "WHERE rt.PROCESSLIST_ID=? AND bt.PROCESSLIST_ID=? AND r.OBJECT_NAME=? "
                            + "AND r.INDEX_NAME='PRIMARY' AND r.LOCK_TYPE='RECORD' AND r.LOCK_DATA=? "
                            + "AND b.OBJECT_NAME=r.OBJECT_NAME AND b.INDEX_NAME=r.INDEX_NAME AND b.LOCK_DATA=r.LOCK_DATA",
                    Long.class, waiter.get(), blocker.get(), table, Long.toString(rowId)) > 0) {
                assertFalse(future.isDone(), "确认指定连接和指定主键的真实行锁等待后才能提交");
                return;
            }
            if (future.isDone()) future.get(1, TimeUnit.SECONDS);
            Thread.sleep(10);
        }
        fail("未确认指定连接之间的 " + table + " 主键行锁等待");
    }

    public boolean holds(long connection, String table, long rowId) {
        return observer.queryForObject("SELECT COUNT(*) FROM performance_schema.data_locks l "
                        + "JOIN performance_schema.threads t ON t.THREAD_ID=l.THREAD_ID "
                        + "WHERE t.PROCESSLIST_ID=? AND l.OBJECT_NAME=? AND l.INDEX_NAME='PRIMARY' "
                        + "AND l.LOCK_TYPE='RECORD' AND l.LOCK_STATUS='GRANTED' AND l.LOCK_DATA=?",
                Long.class, connection, table, Long.toString(rowId)) > 0;
    }
}
