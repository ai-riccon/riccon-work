package com.riccon.api.quota;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

import com.riccon.api.quota.persistence.QuotaJdbc;
import com.riccon.api.support.ApiTestClient;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;

/**
 * IT-QUOTA-104：配额存储不可用时 fail-closed。
 *
 * <p>模拟配额表读取故障：返回 503 {@code RICCON-QUOTA-503}，不创建任务/运行；已存在 run 不受影响（本用例只
 * 验证新建路径）。
 */
class QuotaStoreFailureApiTest extends QuotaApiTestSupport {

    /** 抛错的配额持久化。 */
    @MockitoBean
    private QuotaJdbc quotaJdbc;

    /** 每个用例前让租户锁成功、开关读取抛存储异常。 */
    @BeforeEach
    void failStore() {
        when(quotaJdbc.lockTenantExclusive(any())).thenReturn(true);
        when(quotaJdbc.lockTenantShared(any())).thenReturn(true);
        doThrow(new DataAccessResourceFailureException("quota store unavailable"))
                .when(quotaJdbc)
                .findEnforcementSwitch();
    }

    /**
     * 存储故障返回 503 且不创建 run。
     */
    @Test
    @DisplayName("IT-QUOTA-104 存储故障返回 503 不创建 run")
    void storeFailure_returns503WithoutRun() {
        Fixture fixture = fixture();
        UUID toolId = createMcpTool(fixture.tenant());

        ApiTestClient.ApiResponse response =
                postResearchTask(fixture.member(), fixture.memberWorkspace(), "配额存储故障主题", toolId, "quota-store-fail-1");

        assertThat(response.status()).as(response.body()).isEqualTo(503);
        JsonNode problem = client.json(response);
        assertThat(problem.path("status").asInt()).isEqualTo(503);
        assertThat(problem.path("code").asText()).isEqualTo("RICCON-QUOTA-503");
        assertThat(problem.path("detail").asText()).contains("用量服务暂不可用");
        assertThat(taskCount(fixture.tenant())).isZero();
        assertThat(runCount(fixture.tenant())).isZero();
        assertThat(usedCount(fixture.tenant(), "TENANT", fixture.tenant(), "DAILY"))
                .isZero();
    }
}
