/**
 * 身份与租户领域词汇：账号状态、认证来源、内置角色与权限码。
 *
 * <p>角色码和权限码是数据库、审计和 OpenAPI 的稳定字符串，不得随展示文案变化。M03 由 V9 新增
 * {@code department.read} 与 {@code platform.department.manage}，既有权限码语义不变。
 */
package com.riccon.platform.domain.identity;
