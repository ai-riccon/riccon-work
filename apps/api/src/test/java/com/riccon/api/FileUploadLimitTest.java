package com.riccon.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.riccon.api.support.ApiTestClient;
import com.riccon.api.support.FileApiTestSupport;
import com.riccon.api.support.TestFiles;
import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * multipart 容器上限集成测试。
 *
 * <p>生产上限为 200 MB；这里把容器阈值降到 1 KB，用真实 Tomcat 验证超限请求被映射为 413 与
 * {@code RICCON-FILE-413}，避免只依赖应用层分支。
 */
class FileUploadLimitTest extends FileApiTestSupport {

    /**
     * 收紧 multipart 阈值，触发 Spring/Tomcat 的超限异常。
     *
     * @param registry 动态属性注册表
     */
    @DynamicPropertySource
    static void multipartLimit(DynamicPropertyRegistry registry) {
        registry.add("spring.servlet.multipart.max-file-size", () -> "1KB");
        registry.add("spring.servlet.multipart.max-request-size", () -> "8KB");
    }

    /** 超过容器阈值的上传返回 413。 */
    @Test
    @DisplayName("超过 multipart 阈值返回 413")
    void upload_overContainerLimit_returnsPayloadTooLarge() {
        TenantFixture fixture = tenantFixture();
        upload(fixture.member(), fixture.memberWorkspace(), "small.pdf", "application/pdf", TestFiles.pdf(), null);
        byte[] content = Arrays.copyOf(TestFiles.pdf(), 4096);
        ApiTestClient.ApiResponse response =
                uploadRaw(fixture.member(), fixture.memberWorkspace(), "large.pdf", "application/pdf", content, null);
        assertThat(response.status()).as(response.body()).isEqualTo(413);
        assertThat(client.json(response).path("code").asText()).isEqualTo("RICCON-FILE-413");
        assertThat(response.header("content-type")).startsWith("application/problem+json");
    }
}
