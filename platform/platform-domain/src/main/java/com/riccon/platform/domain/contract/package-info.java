/**
 * 跨部署物契约常量：事件名与 HTTP 头。
 *
 * <p>事件名格式固定 {@code riccon.<domain>.<event>.v<n>}；版本升级意味着不兼容变更，必须新增事件名并保留
 * 旧事件至少一个发布周期。HTTP 头名集中在此，避免 API 与 Worker 各写一份字符串。
 */
package com.riccon.platform.domain.contract;
