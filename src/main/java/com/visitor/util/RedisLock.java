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

    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;

    static {
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setScriptText(
                "if redis.call('get', KEYS[1]) == ARGV[1] then " +
                "  return redis.call('del', KEYS[1]) " +
                "else " +
                "  return 0 " +
                "end");
        UNLOCK_SCRIPT.setResultType(Long.class);
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
     * Only deletes the key if the stored value matches, preventing
     * one client from releasing another client's lock.
     */
    public void unlock(String key, String lockValue) {
        if (lockValue == null) return;
        String lockKey = LOCK_PREFIX + key;
        Long result = stringRedisTemplate.execute(
                UNLOCK_SCRIPT,
                Collections.singletonList(lockKey),
                lockValue);
        if (result != null && result == 1L) {
            log.debug("Lock released: {}", key);
        } else {
            log.debug("Lock release skipped (value mismatch or expired): {}", key);
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

    public <T> T executeWithLock(String key, Duration expire, LockCallback<T> callback) {
        String lockValue = tryLock(key, expire);
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
