package com.riccon.api.admin.model.credential;

import static org.assertj.core.api.Assertions.assertThat;

import com.riccon.api.admin.model.credential.dto.ModelCredentialCreateRequest;
import com.riccon.api.admin.model.credential.dto.ModelCredentialRotateRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 凭据请求 DTO 的脱敏契约。
 *
 * <p>record 默认 {@code toString()} 会拼接 apiKey；本测试保证写接口的 DTO 覆盖为 {@code [redacted]}，
 * 防止日志、异常或调试输出意外带出明文（ADR-0021 §6.6）。
 */
class ModelCredentialRequestTest {

    /** 占位明文；不对应任何真实密钥。 */
    private static final String CANARY = "sk-canary-m04b5-dto-0123456789abcdef";

    /** 新增请求的 toString 不得包含 apiKey。 */
    @Test
    @DisplayName("ModelCredentialCreateRequest.toString 脱敏 apiKey")
    void createRequestToStringRedactsApiKey() {
        ModelCredentialCreateRequest request = new ModelCredentialCreateRequest("primary", CANARY, 10);

        assertThat(request.toString())
                .contains("apiKey=[redacted]")
                .contains("primary")
                .doesNotContain(CANARY);
    }

    /** 轮换请求的 toString 不得包含 apiKey。 */
    @Test
    @DisplayName("ModelCredentialRotateRequest.toString 脱敏 apiKey")
    void rotateRequestToStringRedactsApiKey() {
        ModelCredentialRotateRequest request = new ModelCredentialRotateRequest(CANARY, "rotated");

        assertThat(request.toString())
                .contains("apiKey=[redacted]")
                .contains("rotated")
                .doesNotContain(CANARY);
    }
}
