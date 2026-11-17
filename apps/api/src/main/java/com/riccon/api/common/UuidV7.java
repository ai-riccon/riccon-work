package com.riccon.api.common;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * UUIDv7 生成器（RFC 9562）。
 *
 * <p>M02 的所有业务对象与审计事件都使用时间有序的 UUIDv7；数据库层由各 feature 的持久化代码转换为
 * {@code BINARY(16)}。这里只负责生成标准 UUID 值，不承担租户或数据库语义。
 */
public final class UuidV7 {

    /** 版本号位（UUIDv7）。 */
    private static final long VERSION_BITS = 0x7000L;

    /** RFC 4122/9562 变体位（10xx）。 */
    private static final long VARIANT_BITS = 0x8000_0000_0000_0000L;

    /** 随机位掩码：低 62 位。 */
    private static final long RANDOM_LSB_MASK = 0x3FFF_FFFF_FFFF_FFFFL;

    /** 工具类禁止实例化。 */
    private UuidV7() {}

    /**
     * 生成一个 UUIDv7。
     *
     * <p>高 48 位为 Unix 毫秒时间戳，随后 4 位版本号、12 位随机数；低 64 位为变体位加 62 位随机数。
     * 同一毫秒内不保证严格单调，但整体时间有序，满足按时间检索的需要。
     *
     * @return 新生成的 UUIDv7
     */
    public static UUID generate() {
        return generate(Instant.now());
    }

    /**
     * 使用指定时间生成 UUIDv7，便于测试与审计事件使用同一发生时间。
     *
     * @param timestamp 事件时间
     * @return 新生成的 UUIDv7
     */
    public static UUID generate(Instant timestamp) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        long epochMillis = timestamp.toEpochMilli() & 0x0000_FFFF_FFFF_FFFFL;
        long mostSignificant = (epochMillis << 16) | VERSION_BITS | (random.nextLong() & 0x0FFFL);
        long leastSignificant = (random.nextLong() & RANDOM_LSB_MASK) | VARIANT_BITS;
        return new UUID(mostSignificant, leastSignificant);
    }

    /**
     * 读取 UUIDv7 中编码的 Unix 毫秒时间戳。
     *
     * @param uuid 必须为版本 7 的 UUID
     * @return 生成时间
     * @throws IllegalArgumentException UUID 版本不是 7
     */
    public static Instant timestampOf(UUID uuid) {
        if (uuid.version() != 7) {
            throw new IllegalArgumentException("uuid is not version 7: " + uuid);
        }
        long epochMillis = uuid.getMostSignificantBits() >>> 16;
        return Instant.ofEpochMilli(epochMillis);
    }
}
