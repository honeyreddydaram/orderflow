package com.orderflow.order.service;

import com.orderflow.order.exception.IdempotencyKeyInProgressException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * Makes {@code POST /orders} safe under concurrent replay of the same Idempotency-Key. A plain
 * "check Redis, then create, then write Redis" sequence has a TOCTOU race: two concurrent
 * requests can both miss the check and both create an order. Instead this uses an atomic
 * {@code SET ... NX} as a reservation: only one caller can ever win it, and it starts as a
 * PENDING placeholder that's resolved to the real order id (or released on failure) once the
 * caller finishes - see docs/architecture.md section 10.
 */
@Service
public class IdempotencyKeyService {

    private static final String KEY_PREFIX = "order:idem:";
    private static final String PENDING_MARKER = "PENDING";
    private static final Duration TTL = Duration.ofHours(24);
    private static final int MAX_POLL_ATTEMPTS = 20;
    private static final Duration POLL_INTERVAL = Duration.ofMillis(100);

    private final StringRedisTemplate redisTemplate;

    public IdempotencyKeyService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public sealed interface ReservationResult permits Acquired, AlreadyResolved {
    }

    public record Acquired() implements ReservationResult {
    }

    public record AlreadyResolved(UUID orderId) implements ReservationResult {
    }

    /**
     * Attempts to atomically reserve {@code key}. Returns {@link Acquired} if this caller must
     * now create the order (and MUST call {@link #complete} or {@link #release} afterward), or
     * {@link AlreadyResolved} with the existing order id if another caller already finished.
     * If another caller is still in flight, polls briefly before giving up.
     */
    public ReservationResult reserve(String key) {
        String redisKey = redisKey(key);
        if (Boolean.TRUE.equals(redisTemplate.opsForValue().setIfAbsent(redisKey, PENDING_MARKER, TTL))) {
            return new Acquired();
        }

        for (int attempt = 0; attempt < MAX_POLL_ATTEMPTS; attempt++) {
            String value = redisTemplate.opsForValue().get(redisKey);
            if (value == null) {
                if (Boolean.TRUE.equals(redisTemplate.opsForValue().setIfAbsent(redisKey, PENDING_MARKER, TTL))) {
                    return new Acquired();
                }
            } else if (!PENDING_MARKER.equals(value)) {
                return new AlreadyResolved(UUID.fromString(value));
            }
            sleep();
        }

        throw new IdempotencyKeyInProgressException(
                "A request with this Idempotency-Key is still being processed; retry shortly");
    }

    /** Resolves a reservation this caller acquired to the real order id it created. */
    public void complete(String key, UUID orderId) {
        redisTemplate.opsForValue().set(redisKey(key), orderId.toString(), TTL);
    }

    /** Releases a reservation this caller acquired, e.g. because order creation failed. */
    public void release(String key) {
        redisTemplate.delete(redisKey(key));
    }

    private static String redisKey(String key) {
        return KEY_PREFIX + key;
    }

    private static void sleep() {
        try {
            Thread.sleep(POLL_INTERVAL.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for idempotency key resolution", e);
        }
    }
}
