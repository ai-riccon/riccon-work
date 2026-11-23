package com.riccon.api.chat.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.riccon.api.chat.domain.CitationRegion;
import com.riccon.api.persistence.TenantJdbc;
import com.riccon.platform.common.tenant.TenantId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 引用分块区域批量查询：一次请求只查一次，禁止逐条 N+1。
 */
class CitationRegionJdbcTest {

    /** 多个分块 ID 合并为一次固定参数查询。 */
    @Test
    @DisplayName("分块区域：多个 chunkId 合并为一次查询")
    void findRegions_queriesOnceForAllChunks() {
        TenantJdbc tenantJdbc = mock(TenantJdbc.class);
        when(tenantJdbc.query(any(TenantId.class), anyString(), anyMap(), any()))
                .thenReturn(List.of());
        CitationRegionJdbc jdbc = new CitationRegionJdbc(tenantJdbc);
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();

        Map<UUID, CitationRegion> regions = jdbc.findRegions(TenantId.of("acme"), List.of(first, second));

        assertThat(regions).isEmpty();
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> params = ArgumentCaptor.forClass(Map.class);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(tenantJdbc, times(1)).query(any(TenantId.class), sql.capture(), params.capture(), any());
        assertThat(sql.getValue())
                .contains("FROM knowledge_chunk")
                .contains(":chunkId0")
                .contains(":chunkId1");
        assertThat(params.getValue()).hasSize(2).containsKeys("chunkId0", "chunkId1");
    }

    /** 空分块列表不访问数据库。 */
    @Test
    @DisplayName("分块区域：空列表不查库")
    void findRegions_emptySkipsQuery() {
        TenantJdbc tenantJdbc = mock(TenantJdbc.class);
        CitationRegionJdbc jdbc = new CitationRegionJdbc(tenantJdbc);

        assertThat(jdbc.findRegions(TenantId.of("acme"), List.of())).isEmpty();
        verify(tenantJdbc, times(0)).query(any(TenantId.class), anyString(), anyMap(), any());
    }
}
