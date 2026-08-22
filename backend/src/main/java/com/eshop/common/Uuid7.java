package com.eshop.common;

import java.security.SecureRandom;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * UUID version 7 generator (RFC 9562).
 *
 * <p>Version 7 puts a big-endian Unix millisecond timestamp in the high 48 bits, so generated
 * values sort in creation order. That matters here because every primary key in the schema is a
 * UUID: random v4 keys scatter inserts across the whole B-tree and shred cache locality, while v7
 * keys append to the right-hand edge like a sequence would.
 *
 * <p>Layout:
 * <pre>
 *   0                   1                   2                   3
 *   0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
 *  +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 *  |                       unix_ts_ms (48 bits)                    |
 *  +-------------------------------+-------------------------------+
 *  |  ver (4) |     rand_a (12)    |var(2)|      rand_b (62)       |
 *  +-------------------------------+-------------------------------+
 * </pre>
 *
 * <p>Within a single millisecond {@code rand_a} is used as a monotonic counter so that IDs minted
 * in a tight loop still increase; the counter reseeds randomly whenever the clock ticks forward.
 */
public final class Uuid7 {

    private static final SecureRandom RANDOM = new SecureRandom();

    /** Packs the last-observed millisecond (high 22 bits unused) with the sub-millisecond counter. */
    private static final AtomicLong STATE = new AtomicLong();

    private static final long COUNTER_MASK = 0x0FFFL; // 12 bits of rand_a

    private Uuid7() {
    }

    public static UUID generate() {
        long now = System.currentTimeMillis();

        long counter;
        while (true) {
            long previous = STATE.get();
            long previousMillis = previous >>> 12;
            long previousCounter = previous & COUNTER_MASK;

            long candidateMillis;
            long candidateCounter;

            if (now > previousMillis) {
                candidateMillis = now;
                candidateCounter = RANDOM.nextInt(1 << 10); // leave headroom before overflow
            } else if (previousCounter < COUNTER_MASK) {
                // Same millisecond (or the clock stepped backwards) - keep increasing.
                candidateMillis = previousMillis;
                candidateCounter = previousCounter + 1;
            } else {
                // Counter exhausted inside one millisecond: borrow from the next.
                candidateMillis = previousMillis + 1;
                candidateCounter = 0;
            }

            if (STATE.compareAndSet(previous, (candidateMillis << 12) | candidateCounter)) {
                now = candidateMillis;
                counter = candidateCounter;
                break;
            }
        }

        long mostSignificant = (now << 16)              // unix_ts_ms in the top 48 bits
                | (0x7L << 12)                          // version 7
                | counter;                              // rand_a used as a counter

        long leastSignificant = (RANDOM.nextLong() & 0x3FFFFFFFFFFFFFFFL)
                | 0x8000000000000000L;                  // variant 10xx (RFC 4122)

        return new UUID(mostSignificant, leastSignificant);
    }

    /** Milliseconds since the epoch encoded in a v7 UUID, for debugging and data forensics. */
    public static long timestampOf(UUID uuid) {
        if (uuid.version() != 7) {
            throw new IllegalArgumentException("Not a UUIDv7: " + uuid);
        }
        return uuid.getMostSignificantBits() >>> 16;
    }
}
