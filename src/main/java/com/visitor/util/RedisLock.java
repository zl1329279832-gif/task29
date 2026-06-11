package com.visitor.util;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Collections;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class RedisLock {

    private final StringRedisTemplate stringRedisTemplate;

    private static final String LOCK_PREFIX = "visitor:lock:";
    private static final Duration DEFAULT_EXPIRE = Duration.ofSeconds(10);

    /**
     * Lua script for atomic compare-and-delete.
     * Only deletes the key if the stored value matches the expected lock value.
     * This prevents accidentally releasing another instance's lock.
     */
    private static final String UNLOCK_SCRIPT =
            "if redis.call('get', KEYS[1]) == ARGV[1] then " +
            "  return redis.call('del', KEYS[1]) " +
            "else return 0 end";

    private static final DefaultRedisScript<Long> UNLOCK_REDIS_SCRIPT;
    static {
        UNLOCK_REDIS_SCRIPT = new DefaultRedisScript<>();
        UNLOCK_REDIS_SCRIPT.setScriptText(UNLOCK_SCRIPT);
        UNLOCK_REDIS_SCRIPT.setResultType(Long.class);
    }

    /**
     * Try to acquire a distributed lock
     * @return lock value (for release) or null if failed
     */
    public String tryLock(String key) {
        return tryLock(key, DEFAULT_EXPIRE);
    }

    public String tryLock(String key, Duration expire) {
        String lockKey = LOCK_PREFIX + key;
        String lockValue = UUID.randomUUID().toString();
        Boolean acquired = stringRedisTemplate.opsForValue()
                .setIfAbsent(lockKey, lockValue, expire);
        if (Boolean.TRUE.equals(acquired)) {
            log.debug("Lock acquired: {}", key);
            return lockValue;
        }
        log.debug("Lock failed to acquire: {}", key);
        return null;
    }

    /**
     * Release a distributed lock atomically using Lua script.
     * Only releases if the stored value matches (prevents releasing another instance's lock).
     */
    public void unlock(String key, String lockValue) {
        if (lockValue == null) return;
        String lockKey = LOCK_PREFIX + key;
        try {
            Long result = stringRedisTemplate.execute(
                    UNLOCK_REDIS_SCRIPT,
                    Collections.singletonList(lockKey),
                    lockValue);
            if (Long.valueOf(1L).equals(result)) {
                log.debug("Lock released: {}", key);
            } else {
                log.debug("Lock release skipped (value mismatch or expired): {}", key);
            }
        } catch (Exception e) {
            log.warn("Failed to release lock {}: {}", key, e.getMessage());
        }
    }

    /**
     * Execute a task with distributed lock protection
     */
    public <T> T executeWithLock(String key, LockCallback<T> callback) {
        String lockValue = tryLock(key);
        if (lockValue == null) {
            return callback.onLockFailed();
        }
        try {
            return callback.onLockAcquired();
        } finally {
            unlock(key, lockValue);
        }
    }

    @FunctionalInterface
    public interface LockCallback<T> {
        T onLockAcquired();
        default T onLockFailed() {
            return null;
        }
    }
}
