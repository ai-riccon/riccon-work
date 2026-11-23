package com.riccon.api.chat.persistence;

import com.riccon.api.chat.domain.CitationBoundingBox;
import com.riccon.api.chat.domain.CitationRegion;
import com.riccon.api.persistence.BinaryUuids;
import com.riccon.api.persistence.TenantJdbc;
import com.riccon.platform.common.tenant.TenantId;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 引用分块 OCR 元数据批量查询。
 *
 * <p>按一页引用涉及的 {@code chunk_id} 一次读取 {@code knowledge_chunk.bbox_*} 与 {@code ocr_confidence}，供聊天
 * 消息引用与产物引用共用；禁止逐条查询（ADR-0023 第 5.4 节）。分块已重建或被删除时映射为空，历史引用不回填。
 */
@Component
public class CitationRegionJdbc {

    /** 租户守卫数据访问入口。 */
    private final TenantJdbc tenantJdbc;

    /**
     * 创建分块区域查询。
     *
     * @param tenantJdbc 租户守卫数据访问入口
     */
    public CitationRegionJdbc(TenantJdbc tenantJdbc) {
        this.tenantJdbc = Objects.requireNonNull(tenantJdbc, "tenantJdbc must not be null");
    }

    /**
     * 批量读取分块 OCR 元数据。
     *
     * @param tenantId 当前租户
     * @param chunkIds 分块 ID 列表
     * @return 分块 ID 到区域的映射；不存在或无可展示数据时不返回
     */
    public Map<UUID, CitationRegion> findRegions(TenantId tenantId, Collection<UUID> chunkIds) {
        if (chunkIds == null || chunkIds.isEmpty()) {
            return Map.of();
        }
        StringBuilder sql = new StringBuilder("SELECT id, bbox_x, bbox_y, bbox_w, bbox_h, ocr_confidence"
                + " FROM knowledge_chunk WHERE tenant_id = :tenantId AND id IN (");
        Map<String, Object> params = new LinkedHashMap<>();
        int index = 0;
        for (UUID chunkId : chunkIds) {
            if (index > 0) {
                sql.append(", ");
            }
            String name = "chunkId" + index;
            sql.append(':').append(name);
            params.put(name, BinaryUuids.toBytes(chunkId));
            index++;
        }
        sql.append(')');
        List<Map.Entry<UUID, CitationRegion>> rows =
                tenantJdbc.query(tenantId, sql.toString(), params, CitationRegionJdbc::mapRegion);
        Map<UUID, CitationRegion> regions = new LinkedHashMap<>(rows.size());
        for (Map.Entry<UUID, CitationRegion> row : rows) {
            if (row.getValue().hasData()) {
                regions.put(row.getKey(), row.getValue());
            }
        }
        return regions;
    }

    /**
     * 映射分块行。
     *
     * @param rs 结果集
     * @param rowNum 行号
     * @return 分块 ID 与区域
     * @throws SQLException 列读取失败
     */
    private static Map.Entry<UUID, CitationRegion> mapRegion(ResultSet rs, int rowNum) throws SQLException {
        Integer x = rs.getObject("bbox_x", Integer.class);
        Integer y = rs.getObject("bbox_y", Integer.class);
        Integer width = rs.getObject("bbox_w", Integer.class);
        Integer height = rs.getObject("bbox_h", Integer.class);
        // 四元组必须完整且合法才返回 bbox；坏数据降级为 null，不让整页引用失败。
        CitationBoundingBox bbox = null;
        if (x != null
                && y != null
                && width != null
                && height != null
                && x >= 0
                && y >= 0
                && width >= 1
                && height >= 1) {
            bbox = new CitationBoundingBox(x, y, width, height);
        }
        BigDecimal confidence = rs.getBigDecimal("ocr_confidence");
        return Map.entry(BinaryUuids.fromBytes(rs.getBytes("id")), new CitationRegion(bbox, confidence));
    }
}
