package com.hookflow.delivery.infrastructure;

import com.hookflow.delivery.application.DeliveryWakeup;
import com.hookflow.delivery.application.DueDeliveryScanner;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPubSub;

@Profile("!skeleton")
@Component
public final class RedisDeliveryWakeup implements DeliveryWakeup, AutoCloseable {
    public static final String CHANNEL = "hookflow:delivery:wakeup";
    public static final String SIGNAL = "scan";
    private static final Logger log = LoggerFactory.getLogger(RedisDeliveryWakeup.class);
    private final boolean enabled;
    private final HostAndPort address;
    private final DefaultJedisClientConfig config;
    private final DueDeliveryScanner scanner;
    private final ThreadPoolExecutor publisher = worker("hookflow-redis-publish");
    private final ThreadPoolExecutor scans = worker("hookflow-redis-scan");
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean publishPending = new AtomicBoolean();
    private final AtomicBoolean scanPending = new AtomicBoolean();
    private final AtomicLong nextPublishWarning = new AtomicLong(System.nanoTime());
    private volatile long publishRetryAt = System.nanoTime();
    private volatile Jedis subscription;
    private volatile JedisPubSub listener;
    private volatile Thread subscriber;
    private volatile Thread heartbeat;

    public RedisDeliveryWakeup(@Value("${hookflow.redis.enabled:false}") boolean enabled,
            @Value("${hookflow.redis.host:localhost}") String host,
            @Value("${hookflow.redis.port:6379}") int port,
            @Value("${hookflow.redis.username:}") String username,
            @Value("${hookflow.redis.password:}") String password, DueDeliveryScanner scanner) {
        this.enabled = enabled;
        this.address = new HostAndPort(host, port);
        this.config = DefaultJedisClientConfig.builder().connectionTimeoutMillis(500)
                .socketTimeoutMillis(500).blockingSocketTimeoutMillis(5000)
                .user(username.isBlank() ? null : username)
                .password(password.isEmpty() ? null : password).build();
        this.scanner = scanner;
    }

    @Override
    public void requestPublish() {
        if (!enabled || closed.get() || System.nanoTime() < publishRetryAt
                || !publishPending.compareAndSet(false, true)) {
            return;
        }
        try {
            publisher.execute(() -> {
                try (Jedis jedis = new Jedis(address, config)) {
                    jedis.publish(CHANNEL, SIGNAL);
                } catch (RuntimeException exception) {
                    publishRetryAt = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                    long now = System.nanoTime();
                    long next = nextPublishWarning.get();
                    if (now >= next && nextPublishWarning.compareAndSet(next,
                            now + TimeUnit.MINUTES.toNanos(1))) {
                        log.warn("redisWakeupPublishFailed exceptionType={}", exception.getClass().getSimpleName());
                    }
                } finally {
                    publishPending.set(false);
                }
            });
        } catch (RejectedExecutionException exception) {
            publishPending.set(false);
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (!enabled || closed.get() || !started.compareAndSet(false, true)) {
            return;
        }
        subscriber = new Thread(this::subscribe, "hookflow-redis-subscribe");
        subscriber.setDaemon(true);
        subscriber.start();
        heartbeat = new Thread(this::heartbeat, "hookflow-redis-heartbeat");
        heartbeat.setDaemon(true);
        heartbeat.start();
    }

    private void heartbeat() {
        while (!closed.get()) {
            try {
                TimeUnit.SECONDS.sleep(1);
                JedisPubSub active = listener;
                if (active != null && active.isSubscribed()) {
                    active.ping();
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException exception) {
                // The bounded subscription read timeout reconnects a broken connection.
            }
        }
    }

    private void subscribe() {
        boolean unavailable = false;
        while (!closed.get()) {
            try (Jedis jedis = new Jedis(address, config)) {
                subscription = jedis;
                if (closed.get()) {
                    return;
                }
                listener = new JedisPubSub() {
                    @Override
                    public void onSubscribe(String channel, int count) {
                        log.info("redisWakeupSubscribed");
                    }

                    @Override
                    public void onMessage(String channel, String message) {
                        if (CHANNEL.equals(channel) && SIGNAL.equals(message)) {
                            requestScan();
                        }
                    }
                };
                jedis.subscribe(listener, CHANNEL);
                unavailable = false;
            } catch (RuntimeException exception) {
                if (!closed.get() && !unavailable) {
                    log.warn("redisWakeupSubscribeUnavailable exceptionType={}",
                            exception.getClass().getSimpleName());
                }
                unavailable = true;
            } finally {
                listener = null;
                subscription = null;
            }
            if (!closed.get()) {
                try {
                    TimeUnit.SECONDS.sleep(2);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private void requestScan() {
        if (closed.get() || !scanPending.compareAndSet(false, true)) {
            return;
        }
        try {
            scans.execute(() -> {
                try {
                    scanner.scanDueTasks();
                } catch (RuntimeException exception) {
                    log.warn("redisWakeupScanFailed exceptionType={}", exception.getClass().getSimpleName());
                } finally {
                    scanPending.set(false);
                }
            });
        } catch (RejectedExecutionException exception) {
            scanPending.set(false);
        }
    }

    private static ThreadPoolExecutor worker(String name) {
        return new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(1), work -> {
            Thread thread = new Thread(work, name);
            thread.setDaemon(true);
            return thread;
        }, new ThreadPoolExecutor.AbortPolicy());
    }

    @Override
    public void close() {
        closed.set(true);
        Jedis active = subscription;
        if (active != null) {
            try {
                active.close();
            } catch (RuntimeException exception) {
                log.warn("redisWakeupCloseFailed exceptionType={}", exception.getClass().getSimpleName());
            }
        }
        Thread thread = subscriber;
        if (thread != null) {
            thread.interrupt();
        }
        if (heartbeat != null) {
            heartbeat.interrupt();
        }
        publisher.shutdownNow();
        scans.shutdownNow();
    }
}
