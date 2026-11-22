package com.riccon.worker.run.config;

import com.riccon.worker.run.domain.OutboxLane;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Outbox 通道与处理时长配置（ADR-0023 第 7.3 节）。
 *
 * <p>与既有 outbox 轮询配置共用前缀 {@code riccon.worker.outbox}，只负责通道相关键：
 * {@code lanes}（默认 {@code default,heavy}）、{@code heavy-concurrency}（默认 2）与
 * {@code max-processing}（默认 30 分钟）。环境变量分别为
 * {@code RICCON_WORKER_OUTBOX_LANES}、{@code RICCON_WORKER_OUTBOX_HEAVY_CONCURRENCY}、
 * {@code RICCON_WORKER_OUTBOX_MAX_PROCESSING}。
 *
 * <p>放在 {@code run/config} 而非共享 config 包，是 M05-B1 写范围（worker run）内的最小改动；
 * 配置校验在启动期失败，避免无通道或永久处理把事件卡死。
 */
@Component
@ConfigurationProperties(prefix = "riccon.worker.outbox")
public class OutboxLaneProperties implements InitializingBean {

    /** 重通道默认并发；与 ADR-0023 §7.3 一致。 */
    private static final int DEFAULT_HEAVY_CONCURRENCY = 2;

    /** 重通道并发上限，防止单个 Worker 吃满 CPU/内存。 */
    private static final int MAX_HEAVY_CONCURRENCY = 8;

    /** 通道配置原文，逗号分隔。 */
    private List<String> lanes = List.of(OutboxLane.DEFAULT.code(), OutboxLane.HEAVY.code());

    /** 重通道并发。 */
    private int heavyConcurrency = DEFAULT_HEAVY_CONCURRENCY;

    /** 单事件最大处理时长；超时按可重试失败处理。 */
    private Duration maxProcessing = Duration.ofMinutes(30);

    /** 解析后的启用通道，只在启动校验后使用。 */
    private List<OutboxLane> enabledLanes = List.of();

    /**
     * 返回原始通道配置。
     *
     * @return 通道码列表
     */
    public List<String> getLanes() {
        return lanes;
    }

    /**
     * 设置通道配置。
     *
     * @param lanes 通道码列表
     */
    public void setLanes(List<String> lanes) {
        this.lanes = lanes == null ? List.of() : List.copyOf(lanes);
    }

    /**
     * 返回重通道并发。
     *
     * @return 并发数
     */
    public int getHeavyConcurrency() {
        return heavyConcurrency;
    }

    /**
     * 设置重通道并发。
     *
     * @param heavyConcurrency 并发数
     */
    public void setHeavyConcurrency(int heavyConcurrency) {
        this.heavyConcurrency = heavyConcurrency;
    }

    /**
     * 返回单事件最大处理时长。
     *
     * @return 最大处理时长
     */
    public Duration getMaxProcessing() {
        return maxProcessing;
    }

    /**
     * 设置单事件最大处理时长。
     *
     * @param maxProcessing 最大处理时长
     */
    public void setMaxProcessing(Duration maxProcessing) {
        this.maxProcessing = maxProcessing;
    }

    /**
     * 返回启动校验后的启用通道。
     *
     * @return 不可变通道列表
     */
    public List<OutboxLane> enabledLanes() {
        return enabledLanes;
    }

    /**
     * 返回重通道并发（只读访问器）。
     *
     * @return 并发数
     */
    public int heavyConcurrency() {
        return heavyConcurrency;
    }

    /**
     * 返回单事件最大处理时长（只读访问器）。
     *
     * @return 最大处理时长
     */
    public Duration maxProcessing() {
        return maxProcessing;
    }

    /**
     * 校验通道、并发与处理时长。
     *
     * <p>空通道会让所有事件无人领取，必须在启动期失败；重复通道会启动两个相同的轮询器，同样拒绝。
     * 未启用通道的事件类型不在这里失败：分实例部署（一个实例只跑 heavy）是合法运维模式，
     * 由调度器启动日志提示“未覆盖事件类型”。
     */
    @Override
    public void afterPropertiesSet() {
        if (lanes == null || lanes.isEmpty()) {
            throw new IllegalArgumentException("riccon.worker.outbox.lanes must contain at least one lane");
        }
        Set<OutboxLane> parsed = new LinkedHashSet<>();
        List<String> invalid = new ArrayList<>();
        for (String laneCode : lanes) {
            OutboxLane lane = OutboxLane.fromCode(laneCode).orElse(null);
            if (lane == null) {
                invalid.add(String.valueOf(laneCode));
            } else if (!parsed.add(lane)) {
                throw new IllegalArgumentException("riccon.worker.outbox.lanes contains duplicate lane: " + laneCode);
            }
        }
        if (!invalid.isEmpty()) {
            throw new IllegalArgumentException(
                    "riccon.worker.outbox.lanes contains unknown lanes: " + invalid + "; expected default or heavy");
        }
        if (heavyConcurrency < 1 || heavyConcurrency > MAX_HEAVY_CONCURRENCY) {
            throw new IllegalArgumentException(
                    "riccon.worker.outbox.heavy-concurrency must be between 1 and " + MAX_HEAVY_CONCURRENCY);
        }
        if (maxProcessing == null || maxProcessing.isNegative() || maxProcessing.isZero()) {
            throw new IllegalArgumentException("riccon.worker.outbox.max-processing must be positive");
        }
        this.enabledLanes = List.copyOf(parsed);
    }
}
