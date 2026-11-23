package com.riccon.worker.connector.domain;

/**
 * 连接器稳定错误码（RUNBOOK「M05b-5」）。
 *
 * <p>写入 {@code kb_connector.last_error_code}、条目 {@code error_code} 与同步审计；不包含对象 key、
 * bucket 或凭据。
 */
public final class ConnectorErrorCodes {

    /** 源端认证失败。 */
    public static final String AUTH = "KB_CONNECTOR_AUTH";

    /** 端点不可达/网络错误。 */
    public static final String UNREACHABLE = "KB_CONNECTOR_UNREACHABLE";

    /** 同步总超时或单对象下载超时。 */
    public static final String TIMEOUT = "KB_CONNECTOR_TIMEOUT";

    /** 凭据不可解或未配置。 */
    public static final String CREDENTIAL = "KB_CONNECTOR_CREDENTIAL";

    /** 配置非法（端点不在允许列表、bucket 非法等）。 */
    public static final String CONFIG = "KB_CONNECTOR_CONFIG";

    /** 类型不在白名单。 */
    public static final String TYPE_UNSUPPORTED = "KB_CONNECTOR_TYPE_UNSUPPORTED";

    /** 单对象超过大小上限。 */
    public static final String OBJECT_TOO_LARGE = "KB_CONNECTOR_OBJECT_TOO_LARGE";

    /** 单次对象总数超过上限。 */
    public static final String OBJECT_LIMIT = "KB_CONNECTOR_OBJECT_LIMIT";

    /** 单次总字节超过上限。 */
    public static final String TOTAL_BYTES_LIMIT = "KB_CONNECTOR_TOTAL_BYTES_LIMIT";

    /** 源端返回 truncated 但缺少 continuation token；list 不完整，不得传播删除。 */
    public static final String TRUNCATED = "KB_CONNECTOR_TRUNCATED";

    /** externalId 超过数据库列长度。 */
    public static final String EXTERNAL_ID_TOO_LONG = "KB_CONNECTOR_EXTERNAL_ID_TOO_LONG";

    /** 单对象下载或校验失败。 */
    public static final String OBJECT_FAILED = "KB_CONNECTOR_OBJECT_FAILED";

    /** 来源文档缺失（历史数据异常）。 */
    public static final String DOCUMENT_MISSING = "KB_CONNECTOR_DOCUMENT_MISSING";

    /** 未预期同步失败。 */
    public static final String INTERNAL = "KB_CONNECTOR_INTERNAL";

    /** 工具类禁止实例化。 */
    private ConnectorErrorCodes() {}
}
