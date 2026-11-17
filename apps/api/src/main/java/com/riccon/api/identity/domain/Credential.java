package com.riccon.api.identity.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * 内置账号密码凭据。
 *
 * @param passwordHash 带 {@code {bcrypt}} 前缀的哈希
 * @param failedAttempts 连续失败次数
 * @param lockedUntil 锁定截止时间；为空表示未锁定
 */
public record Credential(String passwordHash, int failedAttempts, Instant lockedUntil) {

    /** 保证密码哈希存在；哈希本身禁止进入日志与响应。 */
    public Credential {
        Objects.requireNonNull(passwordHash, "passwordHash must not be null");
    }

    /**
     * 判断指定时间是否仍处于锁定状态。
     *
     * @param now 当前时间
     * @return 锁定且未到期时返回 {@code true}
     */
    public boolean isLockedAt(Instant now) {
        return lockedUntil != null && lockedUntil.isAfter(now);
    }
}
