package com.riccon.api.admin.model.gateway.domain;

import java.util.List;

/**
 * 环境变量覆盖生效时写入网关开关被拒绝。
 *
 * <p>ADR-0021 §3.4 规定优先级为“环境变量 &gt; 数据库 &gt; 默认”；只要任意一个开关由环境变量覆盖，
 * 写接口就必须返回 409，避免 UI 显示“已保存”但运行时仍使用部署值。异常只携带被覆盖的开关名，
 * 不携带部署环境细节。
 */
public class ModelGatewaySwitchEnvOverrideException extends RuntimeException {

    /** 被环境变量覆盖的开关名。 */
    private final List<String> overriddenKeys;

    /**
     * 创建环境覆盖冲突。
     *
     * @param overriddenKeys 被环境变量覆盖的开关名
     */
    public ModelGatewaySwitchEnvOverrideException(List<String> overriddenKeys) {
        super("model gateway switches are overridden by environment variables");
        this.overriddenKeys = List.copyOf(overriddenKeys);
    }

    /**
     * 返回被环境变量覆盖的开关名。
     *
     * @return 开关名列表
     */
    public List<String> overriddenKeys() {
        return overriddenKeys;
    }
}
