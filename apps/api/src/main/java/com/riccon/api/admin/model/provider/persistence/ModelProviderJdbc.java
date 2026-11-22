package com.riccon.api.admin.model.provider.persistence;

import com.riccon.api.admin.model.provider.domain.ProviderCatalogRow;
import com.riccon.api.admin.persistence.PlatformJdbc;
import com.riccon.api.persistence.BinaryUuids;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 平台供应商目录访问。
 *
 * <p>目录是全局表；key 计数只读取 {@code tenant_id='platform'} 的 ACTIVE API_KEY。SQL 在子查询中显式固定
 * platform 租户，不按调用方租户展开，也不返回掩码之外任何凭据字段（ADR-0021 §6.1/§6.6）。
 */
@Component
public class ModelProviderJdbc {

    /** 目录与计数查询；子查询只统计平台托管 key，避免 N+1。 */
    private static final String SELECT_ALL = """
            SELECT p.id,
                   p.code,
                   p.name,
                   p.protocol,
                   p.base_url,
                   p.status,
                   (SELECT COUNT(*)
                      FROM provider_credential c
                     WHERE c.tenant_id = 'platform'
                       AND c.provider_id = p.id
                       AND c.credential_type = 'API_KEY'
                       AND c.status = 'ACTIVE') AS active_credential_count,
                   (SELECT COUNT(*) FROM model m WHERE m.provider_id = p.id) AS model_count
              FROM model_provider p
             ORDER BY p.code
            """;

    /** 单行查询。 */
    private static final String SELECT_ONE = """
            SELECT p.id,
                   p.code,
                   p.name,
                   p.protocol,
                   p.base_url,
                   p.status,
                   (SELECT COUNT(*)
                      FROM provider_credential c
                     WHERE c.tenant_id = 'platform'
                       AND c.provider_id = p.id
                       AND c.credential_type = 'API_KEY'
                       AND c.status = 'ACTIVE') AS active_credential_count,
                   (SELECT COUNT(*) FROM model m WHERE m.provider_id = p.id) AS model_count
              FROM model_provider p
             WHERE p.id = :id
            """;

    /** 平台管理全局表入口。 */
    private final PlatformJdbc platformJdbc;

    /**
     * 创建供应商目录访问。
     *
     * @param platformJdbc 平台管理全局表入口
     */
    public ModelProviderJdbc(PlatformJdbc platformJdbc) {
        this.platformJdbc = Objects.requireNonNull(platformJdbc, "platformJdbc must not be null");
    }

    /**
     * 读取全部供应商目录。
     *
     * @return 目录行
     */
    public List<ProviderCatalogRow> findAll() {
        return platformJdbc.queryGlobal(SELECT_ALL, Map.of(), ModelProviderJdbc::mapRow);
    }

    /**
     * 按 ID 读取供应商。
     *
     * @param providerId 供应商 ID
     * @return 目录行
     */
    public Optional<ProviderCatalogRow> findById(UUID providerId) {
        return platformJdbc.queryOneGlobal(
                SELECT_ONE, Map.of("id", BinaryUuids.toBytes(providerId)), ModelProviderJdbc::mapRow);
    }

    /**
     * 更新展示名；只允许改 name，不触碰部署管理的 base_url/protocol。
     *
     * @param providerId 供应商 ID
     * @param name 新展示名
     * @return 受影响行数
     */
    public int updateName(UUID providerId, String name) {
        return platformJdbc.updateGlobal(
                "UPDATE model_provider SET name = :name WHERE id = :id",
                Map.of("id", BinaryUuids.toBytes(providerId), "name", name));
    }

    /**
     * 映射目录行。
     *
     * @param rs 结果集
     * @param rowNum 行号
     * @return 目录行
     * @throws java.sql.SQLException 读取失败
     */
    private static ProviderCatalogRow mapRow(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new ProviderCatalogRow(
                BinaryUuids.fromBytes(rs.getBytes("id")),
                rs.getString("code"),
                rs.getString("name"),
                rs.getString("protocol"),
                rs.getString("base_url"),
                rs.getString("status"),
                rs.getLong("active_credential_count"),
                rs.getLong("model_count"));
    }
}
