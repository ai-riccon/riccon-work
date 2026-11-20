package com.riccon.api.admin.model.credential.domain;

/**
 * 模型凭据管理端稳定原因码。
 *
 * <p>只用于管理端 {@code ProblemDetail.reason} 与测试断言，不进入员工侧错误文案；不得包含明文、
 * 密文或 provider 地址（ADR-0021 §6.6/§7.3）。
 */
public final class CredentialReasonCodes {

    /** 同一 provider 下明文 fingerprint 重复。 */
    public static final String DUPLICATE_CREDENTIAL = "DUPLICATE_CREDENTIAL";

    /** 并发状态变更使条件更新失败；请求需重新读取后再试。 */
    public static final String CREDENTIAL_STATE_CONFLICT = "CREDENTIAL_STATE_CONFLICT";

    /** provider 不在部署允许列表、协议不支持或已停用。 */
    public static final String PROVIDER_NOT_ALLOWED = "PROVIDER_NOT_ALLOWED";

    /** apiKey 为空、长度不在 16..512、含首尾空白或为已知占位值。 */
    public static final String INVALID_API_KEY = "INVALID_API_KEY";

    /** label 为空或超过 64 字符。 */
    public static final String INVALID_LABEL = "INVALID_LABEL";

    /** priority 不在 1..1000。 */
    public static final String INVALID_PRIORITY = "INVALID_PRIORITY";

    /** 工具类禁止实例化。 */
    private CredentialReasonCodes() {}
}
