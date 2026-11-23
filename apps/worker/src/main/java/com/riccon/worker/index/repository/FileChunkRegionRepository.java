package com.riccon.worker.index.repository;

import com.riccon.platform.common.tenant.TenantId;
import com.riccon.worker.parse.domain.BoundingBox;
import com.riccon.worker.parse.domain.ParsedChunk;
import com.riccon.worker.persistence.TenantJdbc;
import com.riccon.worker.persistence.UuidBinary;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 文件分块 OCR 区域/置信度持久化。
 *
 * <p>{@code file_chunk} 的 bbox_x/y/w/h 与 ocr_confidence 由 B5 解析链产出、V12 建列；
 * 本任务只负责落库。写入放在 {@code index/repository} 而不是修改 B5 的
 * {@code parse/repository/FileRepository}：后者不在 TASK-0169 的写入范围内，且
 * {@link com.riccon.worker.parse.repository.FileRepository} 的“先删后插”语义保持不变。
 *
 * <p>按 {@code ordinal} 定位文件分块：{@code FileRepository.replaceChunks} 的序号从 1 开始且
 * 顺序与解析结果一致，因此同一批分块可以幂等重放；没有 bbox/置信度的分块跳过，不产生空更新。
 */
@Component
public class FileChunkRegionRepository {

    /** 租户数据访问层。 */
    private final TenantJdbc tenantJdbc;

    /**
     * 创建文件分块区域仓库。
     *
     * @param tenantJdbc 租户数据访问层
     */
    public FileChunkRegionRepository(TenantJdbc tenantJdbc) {
        this.tenantJdbc = Objects.requireNonNull(tenantJdbc, "tenantJdbc must not be null");
    }

    /**
     * 为一次解析尝试写入分块区域与置信度。
     *
     * @param tenantId 当前租户
     * @param parseId 解析尝试 ID
     * @param chunks 解析结果分块（顺序与落库 ordinal 一致）
     * @return 实际更新的分块数
     */
    public int updateRegions(TenantId tenantId, UUID parseId, List<ParsedChunk> chunks) {
        int updated = 0;
        int ordinal = 1;
        for (ParsedChunk chunk : chunks) {
            BoundingBox bbox = chunk.bbox();
            if (bbox == null && chunk.ocrConfidence() == null) {
                // 文本型 PDF/DOCX/Excel 没有区域；避免无意义 UPDATE 放大写放大。
                ordinal++;
                continue;
            }
            Map<String, Object> params = new HashMap<>();
            params.put("parseId", UuidBinary.toBytes(parseId));
            params.put("ordinal", ordinal++);
            params.put("bboxX", bbox == null ? null : bbox.x());
            params.put("bboxY", bbox == null ? null : bbox.y());
            params.put("bboxW", bbox == null ? null : bbox.width());
            params.put("bboxH", bbox == null ? null : bbox.height());
            params.put("ocrConfidence", chunk.ocrConfidence());
            updated += tenantJdbc.update(tenantId, """
                    UPDATE file_chunk
                       SET bbox_x = :bboxX,
                           bbox_y = :bboxY,
                           bbox_w = :bboxW,
                           bbox_h = :bboxH,
                           ocr_confidence = :ocrConfidence
                     WHERE tenant_id = :tenantId
                       AND parse_id = :parseId
                       AND ordinal = :ordinal
                    """, params);
        }
        return updated;
    }
}
