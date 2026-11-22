package com.riccon.api.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 知识库 ACL 部署开关的边界校验（ADR-0023 第 5.8 节）。
 *
 * <p>属性前缀 {@code riccon.kb.acl} 与环境变量的映射由 Spring Boot 绑定负责；本测试只锁定构造期
 * 边界与固定 WARN/指标名，避免把上限写成 0 或把指标名改掉后 RUNBOOK/告警失效。
 */
class KnowledgeAclPropertiesTest {

    /** 默认关闭、上限 200，与 ADR 首次部署一致。 */
    @Test
    @DisplayName("ACL 默认关闭且上限 200")
    void defaults_matchAdr() {
        KnowledgeAclProperties properties = new KnowledgeAclProperties(false, 200);
        assertThat(properties.enforcementEnabled()).isFalse();
        assertThat(properties.maxUsers()).isEqualTo(200);
        assertThat(KnowledgeAclStartupCheck.WARN_CODE).isEqualTo("RICCON-KB-001");
        assertThat(KnowledgeAclStartupCheck.METRIC_NAME).isEqualTo("riccon.kb.acl.enforcement.disabled");
    }

    /** 上限必须在 1–1000 之间，避免 0 或不可控规模。 */
    @Test
    @DisplayName("ACL 上限越界启动失败")
    void maxUsers_mustBeWithinBounds() {
        assertThatThrownBy(() -> new KnowledgeAclProperties(true, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("max-users");
        assertThatThrownBy(() -> new KnowledgeAclProperties(true, 1001))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("max-users");
        assertThat(new KnowledgeAclProperties(true, 1).maxUsers()).isEqualTo(1);
        assertThat(new KnowledgeAclProperties(true, 1000).maxUsers()).isEqualTo(1000);
    }
}
