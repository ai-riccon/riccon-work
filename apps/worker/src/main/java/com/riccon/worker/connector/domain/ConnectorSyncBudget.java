package com.riccon.worker.connector.domain;

import java.time.Duration;
import java.time.Instant;

/**
 * 单次同步的预算：对象数、总字节与总超时。
 *
 * <p>总超时是连接器自身的收尾上限，必须小于 heavy 看门狗（65 分钟），确保同步先按
 * {@code KB_CONNECTOR_TIMEOUT} 收尾（ADR-0023 第 6.5/7.3 节）。超过对象数/字节上限时把 list 标记为不完整，
 * 不传播删除。
 */
public final class ConnectorSyncBudget {

    /** 开始时间。 */
    private final Instant startedAt;

    /** 总超时。 */
    private final Duration timeout;

    /** 最大对象数。 */
    private final int maxObjects;

    /** 最大总字节。 */
    private final long maxBytes;

    /** 已扫描对象数。 */
    private int scanned;

    /** 已下载字节数。 */
    private long downloadedBytes;

    /** 是否因上限截断。 */
    private boolean truncated;

    /**
     * 创建预算。
     *
     * @param startedAt 开始时间
     * @param timeout 总超时
     * @param maxObjects 最大对象数
     * @param maxBytes 最大总字节
     */
    public ConnectorSyncBudget(Instant startedAt, Duration timeout, int maxObjects, long maxBytes) {
        this.startedAt = startedAt;
        this.timeout = timeout;
        this.maxObjects = maxObjects;
        this.maxBytes = maxBytes;
    }

    /**
     * 判断总超时是否已到。
     *
     * @param now 当前时间
     * @return 已超时返回 true
     */
    public boolean expired(Instant now) {
        return !now.isBefore(startedAt.plus(timeout));
    }

    /**
     * 剩余时间；已超时返回零。
     *
     * @param now 当前时间
     * @return 剩余时间
     */
    public Duration remaining(Instant now) {
        Duration remaining = Duration.between(now, startedAt.plus(timeout));
        return remaining.isNegative() ? Duration.ZERO : remaining;
    }

    /**
     * 登记一个对象；超过对象数上限时标记截断并返回 false。
     *
     * @return 是否仍可处理
     */
    public boolean addObject() {
        if (scanned >= maxObjects) {
            truncated = true;
            return false;
        }
        scanned++;
        return true;
    }

    /**
     * 登记下载字节；超过总字节上限时标记截断。
     *
     * @param bytes 字节数
     * @return 是否未超限
     */
    public boolean addBytes(long bytes) {
        if (bytes < 0) {
            return false;
        }
        downloadedBytes += bytes;
        if (downloadedBytes > maxBytes) {
            truncated = true;
            return false;
        }
        return true;
    }

    /** 返回已扫描对象数。 */
    public int scanned() {
        return scanned;
    }

    /** 返回已下载字节数。 */
    public long downloadedBytes() {
        return downloadedBytes;
    }

    /** 是否因上限截断。 */
    public boolean truncated() {
        return truncated;
    }
}
