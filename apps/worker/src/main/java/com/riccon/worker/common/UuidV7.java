package com.riccon.worker.common;

import java.security.SecureRandom;
import java.util.UUID;

/**
 * UUIDv7 生成器。
 *
 * <p>业务层只依赖本类生成时间有序 ID；{@code persistence.UuidBinary} 负责数据库
 * {@code BINARY(16)} 编解码。把生成逻辑放在 persistence 之外，避免 service 依赖持久化层。
 */
public final class UuidV7 {

    /** UUIDv7 版本号。 */
    private static final long VERSION_7 = 0x7000L;

    /** RFC 9562 variant 位。 */
    private static final long RFC_VARIANT = 0x8000000000000000L;

    /** 安全随机源。 */
    private static final SecureRandom RANDOM = new SecureRandom();

    /** 工具类禁止实例化。 */
    private UuidV7() {}

    /**
     * 生成 UUIDv7。
     *
     * @return 时间有序 UUID
     */
    public static UUID generate() {
        long timestamp = System.currentTimeMillis() & 0xFFFFFFFFFFFFL;
        long randomA = RANDOM.nextLong() & 0x0FFFL;
        long randB = RANDOM.nextLong() & 0x3FFFFFFFFFFFFFFFL;
        long mostSignificantBits = (timestamp << 16) | VERSION_7 | randomA;
        long leastSignificantBits = RFC_VARIANT | randB;
        return new UUID(mostSignificantBits, leastSignificantBits);
    }
}
