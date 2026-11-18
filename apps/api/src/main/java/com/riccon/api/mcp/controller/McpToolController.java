package com.riccon.api.mcp.controller;

import com.riccon.api.identity.domain.RequirePermission;
import com.riccon.api.identity.domain.RicconPrincipal;
import com.riccon.api.identity.dto.PageQuery;
import com.riccon.api.mcp.dto.McpToolPageResponse;
import com.riccon.api.mcp.service.McpToolService;
import com.riccon.api.web.AuthenticationSupport;
import com.riccon.platform.domain.identity.PermissionCodes;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 租户 MCP 工具目录接口。
 *
 * <p>对应 OpenAPI {@code listGrantedMcpTools}；读取要求 {@code mcp.read}。只返回本租户已授权且可用的
 * 只读工具，跨租户不可见；返回的 {@code id} 可直接用于创建报告任务。
 */
@RestController
@RequestMapping("/api/v1")
public class McpToolController {

    /** 工具目录服务。 */
    private final McpToolService mcpToolService;

    /**
     * 创建控制器。
     *
     * @param mcpToolService 工具目录服务
     */
    public McpToolController(McpToolService mcpToolService) {
        this.mcpToolService = mcpToolService;
    }

    /**
     * 列出本租户已授权 MCP 工具。
     *
     * @param page 页码
     * @param size 每页大小
     * @param authentication 当前认证
     * @return 工具分页
     */
    @GetMapping("/mcp-tools")
    @RequirePermission(PermissionCodes.MCP_READ)
    public McpToolPageResponse list(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            Authentication authentication) {
        RicconPrincipal actor = AuthenticationSupport.requirePrincipal(authentication);
        return mcpToolService.list(actor, PageQuery.of(page, size));
    }
}
