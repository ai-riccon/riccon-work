package com.riccon.api.admin.model.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.riccon.api.admin.model.catalog.domain.AdminModelRow;
import com.riccon.api.admin.model.catalog.repository.PlatformModelRepository;
import com.riccon.api.admin.model.routing.domain.ModelRoutingVersionConflictException;
import com.riccon.api.admin.model.routing.repository.PlatformModelRoutingRepository;
import com.riccon.api.admin.model.routing.service.ModelRoutingService;
import com.riccon.api.audit.service.AuditService;
import com.riccon.api.identity.domain.RequestMeta;
import com.riccon.api.identity.domain.RicconPrincipal;
import com.riccon.api.model.routing.domain.RoutingChain;
import com.riccon.api.model.routing.repository.ModelRoutingReadRepository;
import com.riccon.platform.common.model.ManagedEndpointRegistry;
import com.riccon.platform.domain.model.ModelPurpose;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 路由保存的并发分支单元测试。
 *
 * <p>集成测试覆盖普通旧版本冲突；本测试覆盖“读到版本后、条件更新前被其他事务抢先”的竞态：条件更新
 * 影响 0 行时必须重读当前版本并返回 409，且不得写条目或审计（ADR-0021 §4.3）。
 */
class ModelRoutingServiceRaceTest {

    /** 读仓储。 */
    private final ModelRoutingReadRepository readRepository = mock(ModelRoutingReadRepository.class);

    /** 写仓储。 */
    private final PlatformModelRoutingRepository writeRepository = mock(PlatformModelRoutingRepository.class);

    /** 模型目录。 */
    private final PlatformModelRepository modelRepository = mock(PlatformModelRepository.class);

    /** 端点允许列表。 */
    private final ManagedEndpointRegistry endpointRegistry = mock(ManagedEndpointRegistry.class);

    /** 审计写入。 */
    private final AuditService auditService = mock(AuditService.class);

    /** 固定时钟。 */
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-20T18:00:00Z"), ZoneOffset.UTC);

    /** 竞态下返回最新版本且不产生任何写副作用。 */
    @Test
    @DisplayName("条件更新 0 行返回最新版本 409，且不写条目与审计")
    void saveChain_versionRace_returnsLatestVersionWithoutWrites() {
        ModelRoutingService service = new ModelRoutingService(
                readRepository, writeRepository, modelRepository, endpointRegistry, auditService, clock);
        UUID modelId = UUID.randomUUID();
        AdminModelRow model = new AdminModelRow(
                modelId,
                UUID.randomUUID(),
                "m04-race-model",
                "竞态模型",
                32768,
                2048,
                null,
                null,
                null,
                "ACTIVE",
                "m04-race",
                "OPENAI_COMPATIBLE",
                "https://10.20.30.80/v1",
                "ACTIVE");
        when(modelRepository.findById(modelId)).thenReturn(Optional.of(model));
        when(endpointRegistry.isAllowed("m04-race", "https://10.20.30.80/v1")).thenReturn(true);
        when(readRepository.find(ModelPurpose.CHAT))
                .thenReturn(
                        Optional.of(new RoutingChain(ModelPurpose.CHAT, 3, clock.instant(), null, List.of())),
                        Optional.of(new RoutingChain(ModelPurpose.CHAT, 4, clock.instant(), null, List.of())));
        when(writeRepository.advanceVersion(
                        ModelPurpose.CHAT.name(),
                        3,
                        UUID.fromString("01932aaa-0000-7000-8000-000000000001"),
                        clock.instant()))
                .thenReturn(false);

        RicconPrincipal actor = new RicconPrincipal(
                UUID.fromString("01932aaa-0000-7000-8000-000000000001"),
                "platform",
                "platform-admin",
                "Platform Admin");

        assertThatThrownBy(() -> service.saveChain(
                        ModelPurpose.CHAT,
                        3,
                        List.of(modelId.toString()),
                        actor,
                        new RequestMeta("trace-1", "request-1", null)))
                .isInstanceOf(ModelRoutingVersionConflictException.class)
                .satisfies(exception -> assertThat(((ModelRoutingVersionConflictException) exception).currentVersion())
                        .isEqualTo(4));

        verify(writeRepository, never())
                .replaceEntries(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyList());
        verifyNoInteractions(auditService);
    }
}
