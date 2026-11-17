package com.riccon.api.persistence;

import java.nio.ByteBuffer;
import java.util.UUID;

/**
 * UUID 与 MySQL {@code BINARY(16)} 的互转。
 *
 * <p>业务代码只使用 {@link UUID}；字节表示仅存在于 {@code ..persistence..} 包，避免 controller/service
 * 暴露数据库细节（ADR-0012）。
 */
public final class BinaryUuids {

    /** 工具类禁止实例化。 */
    private BinaryUuids() {}

    /**
     * 将 UUID 转为 16 字节大端表示。
     *
     * @param uuid 业务 ID，不能为空
     * @return 16 字节数组
     */
    public static byte[] toBytes(UUID uuid) {
        return ByteBuffer.allocate(16)
                .putLong(uuid.getMostSignificantBits())
                .putLong(uuid.getLeastSignificantBits())
                .array();
    }

    /**
     * 将 16 字节大端表示还原为 UUID。
     *
     * @param bytes 数据库列值；为 {@code null} 时返回 {@code null}
     * @return UUID 或 {@code null}
     * @throws IllegalArgumentException 字节长度不是 16
     */
    public static UUID fromBytes(byte[] bytes) {
        if (bytes == null) {
            return null;
        }
        if (bytes.length != 16) {
            throw new IllegalArgumentException("binary uuid must be 16 bytes, got " + bytes.length);
        }
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        return new UUID(buffer.getLong(), buffer.getLong());
    }
}
