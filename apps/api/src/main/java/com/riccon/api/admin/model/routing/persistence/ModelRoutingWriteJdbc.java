package com.riccon.api.admin.model.routing.persistence;

import com.riccon.api.admin.persistence.PlatformJdbc;
import com.riccon.api.persistence.BinaryUuids;
import com.riccon.api.persistence.SqlTimestamps;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 模型路由链写访问（全局表）。
 *
 * <p>保存采用“条件更新链头 + 整链替换条目”的单事务语义：链头 {@code version = version + 1} 且
 * {@code WHERE version = :version} 是乐观锁最终防线；条目先删后插，位置从 1 连续递增
 * （ADR-0021 §3.1/§4.3）。本类不写审计，事务与审计由服务层控制。
 */
@Component
public class ModelRoutingWriteJdbc {

    /** 条件推进版本；0 行表示版本冲突或链头不存在。 */
    private static final String ADVANCE_VERSION = """
            UPDATE model_routing
               SET version = version + 1,
                   updated_by = :updatedBy,
                   updated_at = :updatedAt
             WHERE purpose = :purpose
               AND version = :version
            """;

    /** 整链替换前删除旧条目。 */
    private static final String DELETE_ENTRIES = "DELETE FROM model_routing_entry WHERE purpose = :purpose";

    /** 插入单条链目。 */
    private static final String INSERT_ENTRY = """
            INSERT INTO model_routing_entry (purpose, position, model_id)
            VALUES (:purpose, :position, :modelId)
            """;

    /** 平台管理全局表入口。 */
    private final PlatformJdbc platformJdbc;

    /**
     * 创建路由链写访问。
     *
     * @param platformJdbc 平台管理全局表入口
     */
    public ModelRoutingWriteJdbc(PlatformJdbc platformJdbc) {
        this.platformJdbc = Objects.requireNonNull(platformJdbc, "platformJdbc must not be null");
    }

    /**
     * 按期望版本推进链头版本。
     *
     * @param purpose 用途
     * @param expectedVersion 客户端读到的版本
     * @param updatedBy 平台管理员账号 ID
     * @param updatedAt 保存时间（UTC）
     * @return 受影响行数；0 表示版本冲突
     */
    public int advanceVersion(String purpose, int expectedVersion, UUID updatedBy, Instant updatedAt) {
        return platformJdbc.updateGlobal(
                ADVANCE_VERSION,
                Map.of(
                        "purpose",
                        purpose,
                        "version",
                        expectedVersion,
                        "updatedBy",
                        BinaryUuids.toBytes(updatedBy),
                        "updatedAt",
                        SqlTimestamps.toDatabase(updatedAt)));
    }

    /**
     * 整链替换条目。
     *
     * <p>调用方必须已推进链头版本并在同一事务内执行；位置从 1 开始连续，重复模型由服务层先行拒绝。
     *
     * @param purpose 用途
     * @param modelIds 有序模型 ID
     */
    public void replaceEntries(String purpose, List<UUID> modelIds) {
        platformJdbc.updateGlobal(DELETE_ENTRIES, Map.of("purpose", purpose));
        int position = 1;
        for (UUID modelId : modelIds) {
            int inserted = platformJdbc.insertGlobal(
                    INSERT_ENTRY,
                    Map.of("purpose", purpose, "position", position, "modelId", BinaryUuids.toBytes(modelId)));
            if (inserted != 1) {
                throw new IllegalStateException("model routing entry insert did not affect exactly one row");
            }
            position++;
        }
    }
}
