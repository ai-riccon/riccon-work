package com.riccon.worker.run.domain;

/**
 * Worker 稳定错误码。
 *
 * <p>错误码写入 {@code file_parse.error_code}、{@code knowledge_document.error_code} 或
 * outbox 的 {@code last_error} 前缀；格式对齐合同 {@code RICCON-<DOMAIN>-<NNN>}。
 * 不得把堆栈、原文或对象 key 写入错误信息。
 */
public final class WorkerErrorCodes {

    /** schema 版本校验失败，进程拒绝启动。 */
    public static final String SCHEMA_VERSION = "RICCON-WORKER-001";

    /** 跨租户或不存在统一返回 404，避免泄露存在性（ADR-0009）。 */
    public static final String TENANT_NOT_FOUND = "RICCON-TENANT-404";

    /** 事件 payload 字段缺失或格式非法。 */
    public static final String EVENT_INVALID = "RICCON-WORKER-422";

    /** 未预期异常；有界重试后进入 DEAD。 */
    public static final String UNEXPECTED = "RICCON-WORKER-500";

    /** 文件内容不可解析或没有可抽取文本。 */
    public static final String FILE_UNPROCESSABLE = "RICCON-FILE-422";

    /** 文件超过 Worker 解析大小上限。 */
    public static final String FILE_TOO_LARGE = "RICCON-FILE-413";

    /** 对象存储临时不可用，可重试。 */
    public static final String STORAGE_UNAVAILABLE = "RICCON-FILE-503";

    /** 知识库文档缺少可索引来源。 */
    public static final String INDEX_SOURCE_MISSING = "RICCON-INDEX-422";

    /** OpenSearch 拒绝请求（映射/参数错误），不可重试。 */
    public static final String INDEX_REJECTED = "RICCON-INDEX-400";

    /** OpenSearch 临时不可用，可重试。 */
    public static final String INDEX_UNAVAILABLE = "RICCON-INDEX-503";

    /** 工具类禁止实例化。 */
    private WorkerErrorCodes() {}
}
