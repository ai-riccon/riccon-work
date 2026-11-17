/**
 * 身份与租户领域词汇：账号状态、认证来源、内置角色与权限码。
 *
 * <p>角色码和权限码是数据库、审计和 OpenAPI 的稳定字符串，不得随展示文案变化。M03 的 OIDC 只扩展
 * {@link com.riccon.platform.domain.identity.AuthSource} 的使用路径，不改变既有权限码语义。
 */
package com.riccon.platform.domain.identity;
