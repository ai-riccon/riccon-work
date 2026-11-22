package com.riccon.api.admin.model.routing.repository;

import com.riccon.api.admin.model.routing.persistence.ModelRoutingWriteJdbc;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/**
 * 平台模型路由写仓储。
 *
 * <p>只暴露条件版本推进与整链替换；不提供单条追加/删除，确保“整链保存”是唯一写语义
 * （ADR-0021 §3.1）。
 */
@Repository
public class PlatformModelRoutingRepository {

    /** 路由链写访问。 */
    private final ModelRoutingWriteJdbc writeJdbc;

    /**
     * 创建写仓储。
     *
     * @param writeJdbc 路由链写访问
     */
    public PlatformModelRoutingRepository(ModelRoutingWriteJdbc writeJdbc) {
        this.writeJdbc = Objects.requireNonNull(writeJdbc, "writeJdbc must not be null");
    }

    /**
     * 按期望版本推进链头版本。
     *
     * @param purpose 用途
     * @param expectedVersion 客户端读到的版本
     * @param updatedBy 平台管理员账号 ID
     * @param updatedAt 保存时间（UTC）
     * @return 是否推进成功；false 表示版本冲突
     */
    public boolean advanceVersion(String purpose, int expectedVersion, UUID updatedBy, Instant updatedAt) {
        return writeJdbc.advanceVersion(purpose, expectedVersion, updatedBy, updatedAt) == 1;
    }

    /**
     * 整链替换条目。
     *
     * @param purpose 用途
     * @param modelIds 有序模型 ID
     */
    public void replaceEntries(String purpose, List<UUID> modelIds) {
        writeJdbc.replaceEntries(purpose, modelIds);
    }
}
