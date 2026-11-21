package com.riccon.api.admin.model.gateway.domain;

/**
 * 网关开关版本乐观锁冲突。
 *
 * <p>服务层在整组版本不匹配或条件更新影响 0 行时抛出；控制器转换为 409 {@code RICCON-MODEL-409}、
 * {@code reason=VERSION_CONFLICT}，并在脱敏说明中给出当前版本供管理端刷新后重试（ADR-0021 §3.4）。
 */
public class ModelGatewaySwitchVersionConflictException extends RuntimeException {

    /** 数据库中的当前版本。 */
    private final int currentVersion;

    /**
     * 创建版本冲突。
     *
     * @param currentVersion 数据库中的当前版本
     */
    public ModelGatewaySwitchVersionConflictException(int currentVersion) {
        super("model gateway switch version conflict");
        this.currentVersion = currentVersion;
    }

    /**
     * 返回数据库中的当前版本。
     *
     * @return 当前版本
     */
    public int currentVersion() {
        return currentVersion;
    }
}
