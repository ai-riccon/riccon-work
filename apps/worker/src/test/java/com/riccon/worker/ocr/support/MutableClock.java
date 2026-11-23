package com.riccon.worker.ocr.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 可推进的测试时钟。
 *
 * <p>用于确定性验证文档级 OCR 超时：解析器读取 {@link #millis()}，测试在 OCR 第一页成功后
 * 手动推进时间，避免依赖 {@code Thread.sleep} 或真实墙钟。
 */
public final class MutableClock extends Clock {

    /** 当前毫秒数。 */
    private final AtomicLong millis = new AtomicLong();

    /** 时区。 */
    private final ZoneId zone;

    /** 使用 UTC 创建时钟。 */
    public MutableClock() {
        this(ZoneOffset.UTC);
    }

    /**
     * 创建时钟。
     *
     * @param zone 时区
     */
    public MutableClock(ZoneId zone) {
        this.zone = zone;
    }

    /**
     * 推进时间。
     *
     * @param duration 增量
     */
    public void advance(Duration duration) {
        millis.addAndGet(duration.toMillis());
    }

    /** 返回当前毫秒数。 */
    @Override
    public long millis() {
        return millis.get();
    }

    /** 返回当前瞬时时间。 */
    @Override
    public Instant instant() {
        return Instant.ofEpochMilli(millis());
    }

    /** 返回时区。 */
    @Override
    public ZoneId getZone() {
        return zone;
    }

    /** 返回指定时区的同一时钟视图。 */
    @Override
    public Clock withZone(ZoneId newZone) {
        return new MutableClock(newZone);
    }
}
