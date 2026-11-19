package com.riccon.api.m10.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.riccon.api.identity.dto.UserSummaryResponse;
import com.riccon.platform.domain.identity.AuthSource;
import com.riccon.platform.domain.identity.UserStatus;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * {@code UserSummaryResponse.authSource} 序列化契约。
 *
 * <p>OpenAPI {@code UserSummary.authSource} 是可选属性。管理端必须输出真实来源；会话摘要的兼容调用方未提供
 * 来源时省略字段，不能输出 {@code null}，也不能把未知来源误报为 LOCAL。
 */
class UserSummaryAuthSourceTest {

    /** JSON 序列化。 */
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 管理端来源存在时输出枚举值。 */
    @Test
    @DisplayName("authSource 存在时按枚举名序列化")
    void serializesAuthSourceWhenPresent() {
        UserSummaryResponse summary = new UserSummaryResponse(
                UUID.randomUUID(),
                "alice",
                "Alice",
                null,
                UserStatus.ACTIVE,
                List.of("MEMBER"),
                AuthSource.OIDC,
                null,
                null);

        String json = objectMapper.writeValueAsString(summary);

        assertThat(json).contains("\"authSource\":\"OIDC\"");
    }

    /** 兼容调用方未提供来源时省略，不输出 null。 */
    @Test
    @DisplayName("authSource 为空时省略字段")
    void omitsAuthSourceWhenAbsent() {
        UserSummaryResponse summary = new UserSummaryResponse(
                UUID.randomUUID(), "bob", "Bob", null, UserStatus.ACTIVE, List.of("MEMBER"), null, null);

        String json = objectMapper.writeValueAsString(summary);

        assertThat(json).doesNotContain("authSource");
    }
}
