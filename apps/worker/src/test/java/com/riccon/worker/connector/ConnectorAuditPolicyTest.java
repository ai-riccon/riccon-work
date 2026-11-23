package com.riccon.worker.connector;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.riccon.platform.domain.audit.AuditActions;
import com.riccon.worker.connector.service.ConnectorAuditPolicy;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Worker 连接器审计白名单 fail-closed。
 */
class ConnectorAuditPolicyTest {

    @Test
    void validate_acceptsSyncCompletedAndRejectsUnknownKeysAndEnums() {
        assertThatCode(() -> ConnectorAuditPolicy.validate(
                        AuditActions.KNOWLEDGE_CONNECTOR_SYNC_COMPLETED,
                        Map.of(
                                "connectorId",
                                "c1",
                                "syncId",
                                "s1",
                                "scanned",
                                1,
                                "created",
                                1,
                                "updated",
                                0,
                                "skipped",
                                0,
                                "failed",
                                0,
                                "deleted",
                                0,
                                "durationMs",
                                10,
                                "complete",
                                true)))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> ConnectorAuditPolicy.validate(
                        AuditActions.KNOWLEDGE_CONNECTOR_SYNC_COMPLETED,
                        Map.of("connectorId", "c1", "objectKey", "secret/key")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not allowed");
        assertThatThrownBy(() -> ConnectorAuditPolicy.validate(
                        AuditActions.KNOWLEDGE_CONNECTOR_SYNC_STARTED,
                        Map.of("connectorId", "c1", "syncId", "s1", "trigger", "UNKNOWN")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not allowed");
    }
}
