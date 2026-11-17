package com.riccon.worker.persistence;

import java.nio.ByteBuffer;
import java.util.UUID;

/**
 * UUIDv7 编解码与生成入口。
 *
 * <p>数据库中的业务 ID 一律为应用生成的 UUIDv7（ADR-0012）；本类只在持久化边界使用，
 * 业务层传 {@link UUID}，不暴露字节数组。生成逻辑位于 {@link com.riccon.worker.common.UuidV7}，
 * 避免 service 层依赖 persistence 包。
 */
public final class UuidBinary {

    /** 工具类禁止实例化。 */
    private UuidBinary() {}

    /**
     * 生成 UUIDv7。
     *
     * @return 时间有序的 UUIDv7
     */
    public static UUID newV7() {
        return com.riccon.worker.common.UuidV7.generate();
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
