package com.riccon.platform.domain.file;

/**
 * 文件解析状态。
 *
 * <p>一次上传可能对应多次解析尝试；API 返回最近一次尝试。失败必须带稳定错误码与可重试标记，
 * 不能只返回异常文本。
 */
public enum ParseStatus {
    /** 已受理，等待 worker。 */
    PENDING,
    /** worker 正在解析。 */
    RUNNING,
    /** 解析成功，分块与定位信息可用。 */
    SUCCEEDED,
    /** 解析失败；保留错误码供重试入口展示。 */
    FAILED
}
