/**
 * 组织领域词汇：部门状态等租户内组织元数据。
 *
 * <p>部门是平台管理员跨租户管理的组织元数据（ADR-0018 第 1.4 节），不承载业务内容；部门负责人是
 * {@code department.manager_user_id} 关系，不新增 RBAC 角色码。
 */
package com.riccon.platform.domain.organization;
