package com.riccon.api.audit.service;

import java.time.Duration;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 审计查询与导出配置（ADR-0020 第 1/2 节）。
 *
 * <p>保留天数最少 180 天；导出下载有效期默认 24 小时、上限 7 天；单次导出行数上限默认 10,000。
 * 配置非法时启动直接失败，避免带着错误的保留或导出策略运行。配置只从环境/部署配置读取，
 * 不在运行时热更新。
 */
@Component
@ConfigurationProperties(prefix = "riccon.audit")
public class AuditProperties implements InitializingBean {

    /** 平台审计最小保留天数（合同 data.retention 与 constraints.compliance）。 */
    public static final int MIN_RETENTION_DAYS = 180;

    /** 导出下载有效期上限（ADR-0020 第 2 节）。 */
    public static final Duration MAX_EXPORT_TTL = Duration.ofDays(7);

    /** 单次导出行数允许的最大配置值（OpenAPI AuditExportLimits 上限）。 */
    public static final int MAX_EXPORT_ROW_LIMIT = 50_000;

    /** 审计保留天数；低于 180 天启动失败。 */
    private int retentionDays = 180;

    /** 导出限制。 */
    private Export export = new Export();

    /**
     * 返回审计保留天数。
     *
     * @return 保留天数
     */
    public int getRetentionDays() {
        return retentionDays;
    }

    /**
     * 设置审计保留天数。
     *
     * @param retentionDays 保留天数
     */
    public void setRetentionDays(int retentionDays) {
        this.retentionDays = retentionDays;
    }

    /**
     * 返回导出限制。
     *
     * @return 导出限制
     */
    public Export getExport() {
        return export;
    }

    /**
     * 设置导出限制。
     *
     * @param export 导出限制
     */
    public void setExport(Export export) {
        this.export = export;
    }

    /**
     * 启动时校验配置；非法值直接阻止应用启动。
     */
    @Override
    public void afterPropertiesSet() {
        if (retentionDays < MIN_RETENTION_DAYS) {
            throw new IllegalArgumentException("riccon.audit.retention-days must be >= " + MIN_RETENTION_DAYS);
        }
        if (export == null) {
            throw new IllegalArgumentException("riccon.audit.export must not be null");
        }
        export.validate();
    }

    /** 导出限制配置。 */
    public static class Export {

        /** 批准后下载有效期；默认 24 小时。 */
        private Duration ttl = Duration.ofHours(24);

        /** 单次导出行数上限；默认 10,000。 */
        private int rowLimit = 10_000;

        /**
         * 返回下载有效期。
         *
         * @return 有效期
         */
        public Duration getTtl() {
            return ttl;
        }

        /**
         * 设置下载有效期。
         *
         * @param ttl 有效期
         */
        public void setTtl(Duration ttl) {
            this.ttl = ttl;
        }

        /**
         * 返回导出行数上限。
         *
         * @return 行数上限
         */
        public int getRowLimit() {
            return rowLimit;
        }

        /**
         * 设置导出行数上限。
         *
         * @param rowLimit 行数上限
         */
        public void setRowLimit(int rowLimit) {
            this.rowLimit = rowLimit;
        }

        /**
         * 校验导出配置；非法值直接阻止应用启动。
         */
        void validate() {
            if (ttl == null || ttl.isZero() || ttl.isNegative()) {
                throw new IllegalArgumentException("riccon.audit.export.ttl must be positive");
            }
            if (ttl.compareTo(MAX_EXPORT_TTL) > 0) {
                throw new IllegalArgumentException("riccon.audit.export.ttl must not exceed 7 days");
            }
            if (rowLimit < 1 || rowLimit > MAX_EXPORT_ROW_LIMIT) {
                throw new IllegalArgumentException(
                        "riccon.audit.export.row-limit must be between 1 and " + MAX_EXPORT_ROW_LIMIT);
            }
        }
    }
}
