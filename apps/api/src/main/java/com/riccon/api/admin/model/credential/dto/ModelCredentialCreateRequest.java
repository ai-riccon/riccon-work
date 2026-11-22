package com.riccon.api.admin.model.credential.dto;

/**
 * 新增平台模型凭据请求（OpenAPI {@code ModelCredentialCreateRequest}）。
 *
 * <p>{@code apiKey} 是 writeOnly 明文：只进入服务端内存并立即信封加密，不写入日志、审计、响应或
 * {@link #toString()}。重复请求不承诺幂等键，由 fingerprint 唯一键兜底（ADR-0021 §6.7）。
 *
 * @param label 管理员可读标签，1..64 字符
 * @param apiKey 明文 API key，16..512 字符；只进不出
 * @param priority 优先级 1..1000；缺省 100
 */
public record ModelCredentialCreateRequest(String label, String apiKey, Integer priority) {

    /**
     * 脱敏输出：禁止把 apiKey 带进日志、异常或调试输出。
     *
     * @return 只含 label/priority 与掩码占位的字符串
     */
    @Override
    public String toString() {
        return "ModelCredentialCreateRequest[label=" + label + ", apiKey=[redacted], priority=" + priority + "]";
    }
}
