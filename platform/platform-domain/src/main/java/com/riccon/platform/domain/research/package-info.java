/**
 * 报告任务与运行领域词汇：任务状态、运行状态、阶段、审批与产物状态。
 *
 * <p>状态机是 J2 的核心契约：API 只接受合法迁移，Worker 通过条件更新领取与推进；重复投递不得产生
 * 第二次执行或重复产物。阶段名同时用于 outbox 事件、运行时间线和前端展示。
 */
package com.riccon.platform.domain.research;
