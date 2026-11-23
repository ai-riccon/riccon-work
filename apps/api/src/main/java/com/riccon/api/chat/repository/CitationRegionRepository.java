package com.riccon.api.chat.repository;

import com.riccon.api.chat.domain.CitationRegion;
import com.riccon.api.chat.persistence.CitationRegionJdbc;
import com.riccon.platform.common.tenant.TenantId;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/**
 * 引用分块 OCR 元数据仓储。
 *
 * <p>聊天消息引用与产物引用共用；按分块 ID 批量读取，缺失分块返回空，不抛异常（历史引用与重建索引后不阻断列表）。
 */
@Repository
public class CitationRegionRepository {

    /** 分块区域批量查询。 */
    private final CitationRegionJdbc regionJdbc;

    /**
     * 创建仓储。
     *
     * @param regionJdbc 分块区域批量查询
     */
    public CitationRegionRepository(CitationRegionJdbc regionJdbc) {
        this.regionJdbc = Objects.requireNonNull(regionJdbc, "regionJdbc must not be null");
    }

    /**
     * 批量读取分块区域。
     *
     * @param tenantId 当前租户
     * @param chunkIds 分块 ID 列表
     * @return 分块 ID 到区域的映射
     */
    public Map<UUID, CitationRegion> findRegions(TenantId tenantId, Collection<UUID> chunkIds) {
        return regionJdbc.findRegions(tenantId, chunkIds);
    }
}
