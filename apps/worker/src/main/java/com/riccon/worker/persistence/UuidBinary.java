package com.riccon.worker.persistence;

import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.UUID;

/**
 * UUIDv7 生成与 {@code BINARY(16)} 编解码。
 *
 * <p>数据库中的业务 ID 一律为应用生成的 UUIDv7（ADR-0012）；本类只在持久化边界使用，
 * 业务层传 {@link UUID}，不暴露字节数组。UUIDv7 高位包含毫秒时间戳，满足 outbox 按 id 排序。
 */
public final class UuidBinary {

    /** UUIDv7 的版本号位于第 48–51 位。 */
    private static final long VERSION_7 = 0x7000L;

    /** RFC 9562 variant 位。 */
    private static final long RFC_VARIANT = 0x8000000000000000L;

    /** 随机数生成器；UUIDv7 允许使用普通安全随机数。 */
    private static final SecureRandom RANDOM = new SecureRandom();

    /** 工具类禁止实例化。 */
    private UuidBinary() {}

    /**
     * 生成 UUIDv7。
     *
     * @return 时间有序的 UUIDv7
     */
    public static UUID newV7() {
        long timestamp = System.currentTimeMillis() & 0xFFFFFFFFFFFFL;
        long randomA = RANDOM.nextLong() & 0x0FFFL;
        long randB = RANDOM.nextLong() & 0x3FFFFFFFFFFFFFFFL;
        long mostSignificantBits = (timestamp << 16) | VERSION_7 | randomA;
        long leastSignificantBits = RFC_VARIANT | randB;
        return new UUID(mostSignificantBits, leastSignificantBits);
    }

    /**
     * 将 UUID 编码为 MySQL {@code BINARY(16)}。
     *
     * @param uuid 业务 ID
     * @return 16 字节大端表示
     */
    public static byte[] toBytes(UUID uuid) {
        return ByteBuffer.allocate(16)
                .putLong(uuid.getMostSignificantBits())
                .putLong(uuid.getLeastSignificantBits())
                .array();
    }

    /**
     * 将 MySQL {@code BINARY(16)} 解码为 UUID。
     *
     * @param bytes 16 字节大端表示
     * @return 业务 ID
     */
    public static UUID fromBytes(byte[] bytes) {
        if (bytes == null || bytes.length != 16) {
            throw new IllegalArgumentException("UUID binary value must be 16 bytes");
        }
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        return new UUID(buffer.getLong(), buffer.getLong());
    }
}
