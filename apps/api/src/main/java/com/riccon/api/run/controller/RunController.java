package com.riccon.api.run.controller;

import com.riccon.api.identity.domain.RequirePermission;
import com.riccon.api.identity.domain.RicconPrincipal;
import com.riccon.api.run.dto.RunDetailResponse;
import com.riccon.api.run.service.RunDetailService;
import com.riccon.api.web.AuthenticationSupport;
import com.riccon.platform.domain.identity.PermissionCodes;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 运行详情接口。
 *
 * <p>对应 OpenAPI {@code getRun}；读取要求 {@code conversation.read}。可见性在服务层按运行类别收紧，
 * 跨租户或不可见对象统一 404，不返回运行是否存在的信息。
 */
@RestController
@RequestMapping("/api/v1")
public class RunController {

    /** 运行详情服务。 */
    private final RunDetailService runDetailService;

    /**
     * 创建控制器。
     *
     * @param runDetailService 运行详情服务
     */
    public RunController(RunDetailService runDetailService) {
        this.runDetailService = runDetailService;
    }

    /**
     * 读取运行详情。
     *
     * @param runId 运行 ID
     * @param authentication 当前认证
     * @return 运行详情
     */
    @GetMapping("/runs/{runId}")
    @RequirePermission(PermissionCodes.CONVERSATION_READ)
    public RunDetailResponse get(@PathVariable UUID runId, Authentication authentication) {
        RicconPrincipal actor = AuthenticationSupport.requirePrincipal(authentication);
        return runDetailService.get(actor, runId);
    }
}
