package com.riccon.worker.run.config;

import com.riccon.worker.run.domain.OutboxLane;
import java.time.Duration;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Outbox heavy 通道看门狗与连接器同步总超时的启动校验（M05-B1 F-004，ADR-0023 §6.5/§7.3）。
 *
 * <p>连接器同步允许总计 60 分钟；heavy 通道的看门狗默认 65 分钟，留 5 分钟让同步按
 * {@code KB_CONNECTOR_TIMEOUT} 自行收尾。若部署把 heavy 上限调到 60 分钟以下，长同步会被看门狗
 * 提前中断并按可重试失败反复重放。连接器启用（{@code RICCON_CONNECTOR_ENABLED=true}）且本实例
 * 启用 heavy 通道时，必须保证 heavy 上限不小于 60 分钟，否则启动失败。
 *
 * <p>连接器关闭时允许把 heavy 上限调到更小值，供测试或只跑解析/索引的小部署使用；只启用 default
 * 通道的分实例部署不消费连接器事件，也不受此校验影响。
 */
@Component
public class OutboxProcessingTimeoutValidator implements InitializingBean {

    /** 连接器同步总超时（ADR-0023 §6.5）；heavy 看门狗不得小于该值。 */
    static final Duration CONNECTOR_SYNC_TIMEOUT = Duration.ofMinutes(60);

    /** 连接器总开关。 */
    private final boolean connectorEnabled;

    /** 通道与处理时长配置。 */
    private final OutboxLaneProperties laneProperties;

    /**
     * 创建连接器看门狗校验器。
     *
     * @param connectorEnabled 连接器总开关（{@code RICCON_CONNECTOR_ENABLED}）
     * @param laneProperties 通道与处理时长配置
     */
    public OutboxProcessingTimeoutValidator(
            @Value("${riccon.connector.enabled:false}") boolean connectorEnabled, OutboxLaneProperties laneProperties) {
        this.connectorEnabled = connectorEnabled;
        this.laneProperties = laneProperties;
    }

    /**
     * 连接器启用且本实例启用 heavy 通道时，校验 heavy 看门狗不小于连接器同步总超时。
     *
     * <p>错误信息使用中文并同时给出配置键与当前值，部署人员无需翻阅 ADR 即可定位。
     */
    @Override
    public void afterPropertiesSet() {
        if (!connectorEnabled || !laneProperties.enabledLanes().contains(OutboxLane.HEAVY)) {
            return;
        }
        Duration heavyMaxProcessing = laneProperties.getHeavyMaxProcessing();
        if (heavyMaxProcessing.compareTo(CONNECTOR_SYNC_TIMEOUT) < 0) {
            throw new IllegalStateException("连接器已启用（RICCON_CONNECTOR_ENABLED=true）时，"
                    + "riccon.worker.outbox.heavy-max-processing 必须 >= 60m（连接器同步总超时，ADR-0023 §6.5），"
                    + "当前值=" + heavyMaxProcessing + "（约 " + heavyMaxProcessing.toMinutes() + " 分钟）"
                    + "；请设置 RICCON_WORKER_OUTBOX_HEAVY_MAX_PROCESSING >= 60m 或关闭连接器");
        }
    }
}
