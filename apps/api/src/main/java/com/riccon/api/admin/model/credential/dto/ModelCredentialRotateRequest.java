package com.riccon.api.admin.model.credential.dto;

/**
 * 轮换平台模型凭据请求（OpenAPI {@code ModelCredentialRotateRequest}）。
 *
 * <p>{@code apiKey} 是 writeOnly 明文；{@code label} 为空时继承旧 key 的标签，priority 也继承旧 key。
 * {@link #toString()} 永不回显明文（ADR-0021 §6.3/§6.6）。
 *
 * @param apiKey 新明文 API key，16..512 字符；只进不出
 * @param label 新标签；为空时继承旧 key
 */
public record ModelCredentialRotateRequest(String apiKey, String label) {

    /**
     * 脱敏输出：禁止把 apiKey 带进日志、异常或调试输出。
     *
     * @return 只含 label 与掩码占位的字符串
     */
    @Override
    public String toString() {
        return "ModelCredentialRotateRequest[apiKey=[redacted], label=" + label + "]";
    }
}
