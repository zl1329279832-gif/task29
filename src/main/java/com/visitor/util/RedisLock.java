package com.visitor.util;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class RedisLock {

    private final StringRedisTemplate stringRedisTemplate;

    private static final String LOCK_PREFIX = "visitor:lock:";
    private static final Duration DEFAULT_EXPIRE = Duration.ofSeconds(10);

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
     * Release a distributed lock (only if value matches)
     */
    public void unlock(String key, String lockValue) {
        if (lockValue == null) return;
        String lockKey = LOCK_PREFIX + key;
        String currentValue = stringRedisTemplate.opsForValue().get(lockKey);
        if (lockValue.equals(currentValue)) {
            stringRedisTemplate.delete(lockKey);
            log.debug("Lock released: {}", key);
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
