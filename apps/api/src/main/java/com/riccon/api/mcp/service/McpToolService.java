package com.riccon.api.mcp.service;

import com.riccon.api.identity.domain.RicconPrincipal;
import com.riccon.api.identity.dto.PageQuery;
import com.riccon.api.mcp.dto.McpToolPageResponse;
import com.riccon.api.mcp.dto.McpToolResponse;
import com.riccon.api.mcp.repository.McpToolRepository;
import com.riccon.platform.common.tenant.TenantId;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 租户 MCP 工具目录查询。
 *
 * <p>J2 创建报告任务前必须先能列出可授权工具；可见性由 {@code mcp.read} 权限与租户授权共同决定，
 * 平台管理员没有 {@code mcp.read}，因此不会读到租户业务目录（ADR-0013 第 4 节）。
 */
@Service
public class McpToolService {

    /** 工具仓储。 */
    private final McpToolRepository mcpToolRepository;

    /**
     * 创建服务。
     *
     * @param mcpToolRepository 工具仓储
     */
    public McpToolService(McpToolRepository mcpToolRepository) {
        this.mcpToolRepository = Objects.requireNonNull(mcpToolRepository, "mcpToolRepository must not be null");
    }

    /**
     * 分页列出本租户已授权且可用的 MCP 工具。
     *
     * @param actor 当前员工
     * @param page 分页参数
     * @return 工具分页
     */
    @Transactional(readOnly = true)
    public McpToolPageResponse list(RicconPrincipal actor, PageQuery page) {
        TenantId tenantId = TenantId.of(actor.tenantId());
        long total = mcpToolRepository.count(tenantId);
        List<McpToolResponse> items = mcpToolRepository.list(tenantId, page.size(), page.offset()).stream()
                .map(McpToolResponse::from)
                .toList();
        return new McpToolPageResponse(page.page(), page.size(), total, page.totalPages(total), items);
    }
}
