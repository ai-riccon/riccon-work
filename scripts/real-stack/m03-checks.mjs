#!/usr/bin/env node
// M03 真实栈部门与空间边界复核：跨部门/跨租户 404、平台部门端点边界、成员移出/停用生效与审计租户。
//
// 该脚本只使用公开 HTTP API 与测试栈 SQL，不 import 生产代码，也不修改 M02 的 J1/J2 数据。
// 由 run.mjs 在 checks 之后调用；也可单独执行：
//   REAL_SEED_OUT=.riccon/evidence/<task>/real-stack-seed.json node scripts/real-stack/m03-checks.mjs
//
// 退出码：任一安全断言失败即非零。输出只含 ID、状态与计数，不打印凭据或 PII。

import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import { readFileSync, writeFileSync } from "node:fs";
import path from "node:path";
import { ApiClient } from "./lib/http.mjs";
import { binaryUuid, runSql, sqlString } from "./lib/mysql.mjs";

const root = process.cwd();
const seedPath =
  process.env.REAL_SEED_OUT ??
  path.join(root, ".riccon/evidence/TASK-0024/real-stack-seed.json");
const seed = JSON.parse(readFileSync(seedPath, "utf8"));
const evidenceDir = path.dirname(seedPath);
const suffix = Date.now().toString(36);

/** 断言统一 404。 */
function assertNotFound(response, label) {
  assert.equal(
    response.status,
    404,
    `${label} must be 404, got ${response.status} ${response.text}`,
  );
  assert.equal(
    response.data?.code,
    "RICCON-TENANT-404",
    `${label} must use stable tenant 404 code`,
  );
  for (const field of ["id", "name", "workspaceId", "fileId", "items"]) {
    assert.equal(
      response.data?.[field],
      undefined,
      `${label} leaked field ${field}: ${response.text}`,
    );
  }
}

/** 登录。 */
async function login(tenantCode, username, password) {
  const client = new ApiClient(seed.apiBase);
  await client.login(tenantCode, username, password);
  return client;
}

/** 创建部门；同名已存在时返回既有部门。 */
async function createDepartment(platform, tenant, name) {
  const response = await platform.post(
    `/api/v1/admin/tenants/${tenant}/departments`,
    { name },
  );
  if (response.status === 201) {
    return response.data;
  }
  if (response.status === 409) {
    const page = await platform.get(
      `/api/v1/admin/tenants/${tenant}/departments?page=1&size=100`,
    );
    const found = page.data?.items?.find((item) => item.name === name);
    if (found) {
      return found;
    }
  }
  throw new Error(
    `create department failed: ${response.status} ${response.text}`,
  );
}

/** 创建租户账号。 */
async function createUser(platform, tenant, username, roles) {
  const response = await platform.post(
    `/api/v1/admin/tenants/${tenant}/users`,
    {
      username,
      displayName: `M03 ${username}`,
      password: "m03-check-password-12",
      roles,
    },
  );
  if (response.status !== 201) {
    throw new Error(
      `create user ${username} failed: ${response.status} ${response.text}`,
    );
  }
  return response.data;
}

/** 加入部门。 */
async function addDepartmentMember(platform, tenant, departmentId, userId) {
  return platform.post(
    `/api/v1/admin/tenants/${tenant}/departments/${departmentId}/members`,
    { userId },
  );
}

/** 移出部门。 */
function removeDepartmentMember(platform, tenant, departmentId, userId) {
  return platform.delete(
    `/api/v1/admin/tenants/${tenant}/departments/${departmentId}/members/${userId}`,
  );
}

/** 移交负责人。 */
function transferManager(platform, tenant, departmentId, userId) {
  return platform.request(
    "PUT",
    `/api/v1/admin/tenants/${tenant}/departments/${departmentId}/manager`,
    { body: { userId } },
  );
}

/** 切换部门状态。 */
function setDepartmentStatus(platform, tenant, departmentId, action) {
  return platform.post(
    `/api/v1/admin/tenants/${tenant}/departments/${departmentId}/${action}`,
    {},
  );
}

const platform = await login(
  "platform",
  "platform-admin",
  "test-bootstrap-password-12",
);
const tenantA = seed.tenants.alpha;
const tenantB = seed.tenants.beta;
const alphaAdmin = await login(
  tenantA,
  seed.users.alphaAdmin,
  seed.passwords.alphaAdmin,
);
const alphaMember = await login(
  tenantA,
  seed.users.alphaMember,
  seed.passwords.alphaMember,
);
const betaMember = await login(
  tenantB,
  seed.users.betaMember,
  seed.passwords.betaMember,
);

const alphaAdminId = alphaAdmin.session.user.id;
const alphaMemberId = alphaMember.session.user.id;
const betaMemberId = betaMember.session.user.id;

// 平台管理员部 API 角色边界。
const platformDepartmentRole = await alphaAdmin.get(
  `/api/v1/admin/tenants/${tenantA}/departments`,
);
assert.equal(
  platformDepartmentRole.status,
  403,
  "tenant admin must not call platform department endpoints",
);
const memberDepartmentRole = await alphaMember.get(
  `/api/v1/admin/tenants/${tenantA}/departments`,
);
assert.equal(
  memberDepartmentRole.status,
  403,
  "member must not call platform department endpoints",
);

const missingTenant = await platform.get(
  `/api/v1/admin/tenants/tenant-m03-missing-${suffix}/departments`,
);
assert.equal(missingTenant.status, 404, "missing tenant departments must 404");

const suspendedTenant = `tenant-m03-susp-${suffix}`;
const createdSuspended = await platform.post("/api/v1/admin/tenants", {
  id: suspendedTenant,
  name: `M03 暂停租户 ${suffix}`,
});
assert.equal(createdSuspended.status, 201);
const suspended = await platform.patch(
  `/api/v1/admin/tenants/${suspendedTenant}`,
  { status: "SUSPENDED" },
);
assert.equal(suspended.status, 200);
const suspendedDepartments = await platform.get(
  `/api/v1/admin/tenants/${suspendedTenant}/departments`,
);
assert.equal(
  suspendedDepartments.status,
  409,
  "suspended tenant departments must 409",
);

// 两个部门、单主归属与负责人。
const sales = await createDepartment(platform, tenantA, `销售部-${suffix}`);
const marketing = await createDepartment(platform, tenantA, `市场部-${suffix}`);
assert.equal(
  (await addDepartmentMember(platform, tenantA, sales.id, alphaAdminId)).status,
  201,
);
assert.equal(
  (await addDepartmentMember(platform, tenantA, sales.id, alphaMemberId))
    .status,
  201,
);
const duplicate = await addDepartmentMember(
  platform,
  tenantA,
  sales.id,
  alphaMemberId,
);
assert.equal(duplicate.status, 409);
assert.equal(duplicate.data?.code, "RICCON-DEPT-409");
const duplicateName = await platform.post(
  `/api/v1/admin/tenants/${tenantA}/departments`,
  { name: sales.name },
);
assert.equal(duplicateName.status, 409);
assert.equal(duplicateName.data?.code, "RICCON-DEPT-409");

const secondDepartment = await addDepartmentMember(
  platform,
  tenantA,
  marketing.id,
  alphaMemberId,
);
assert.equal(secondDepartment.status, 409);
assert.equal(secondDepartment.data?.code, "RICCON-DEPT-409");

const outsiderUsername = `alpha-outsider-${suffix}`;
const outsider = await createUser(platform, tenantA, outsiderUsername, [
  "MEMBER",
]);
assert.equal(
  (await addDepartmentMember(platform, tenantA, marketing.id, outsider.id))
    .status,
  201,
);
const outsiderClient = await login(
  tenantA,
  outsiderUsername,
  "m03-check-password-12",
);

// 部门空间、会话与文件。
const teamSpace = await alphaAdmin.post("/api/v1/workspaces", {
  name: `M03 部门空间-${suffix}`,
  departmentId: sales.id,
  visibility: "DEPT",
});
assert.equal(teamSpace.status, 201, teamSpace.text);
const teamWorkspaceId = teamSpace.data.id;
const customSpace = await alphaAdmin.post("/api/v1/workspaces", {
  name: `M03 指定成员空间-${suffix}`,
  departmentId: sales.id,
  visibility: "CUSTOM",
});
assert.equal(customSpace.status, 201, customSpace.text);
const customWorkspaceId = customSpace.data.id;
const customMember = await alphaAdmin.post(
  `/api/v1/workspaces/${customWorkspaceId}/members`,
  { userId: alphaMemberId },
);
assert.equal(customMember.status, 201, customMember.text);

const conversation = await alphaMember.post(
  `/api/v1/workspaces/${teamWorkspaceId}/conversations`,
  { title: `M03 部门会话-${suffix}` },
);
assert.equal(conversation.status, 201, conversation.text);
const file = await alphaAdmin.upload(teamWorkspaceId, {
  filename: "m03-scope.pdf",
  mimeType: "application/pdf",
  content: Buffer.from("%PDF-1.4\n% M03 real stack scope\n"),
});
assert.equal(file.status, 201, file.text);

// 跨部门/跨租户读取统一 404，且无业务字段。
assertNotFound(
  await outsiderClient.get(`/api/v1/workspaces/${teamWorkspaceId}`),
  "cross-department workspace",
);
assertNotFound(
  await outsiderClient.get(`/api/v1/conversations/${conversation.data.id}`),
  "cross-department conversation",
);
assertNotFound(
  await outsiderClient.get(`/api/v1/files/${file.data.id}`),
  "cross-department file metadata",
);
assertNotFound(
  await outsiderClient.get(`/api/v1/files/${file.data.id}/download-url`),
  "cross-department file download",
);
assertNotFound(
  await betaMember.get(`/api/v1/workspaces/${teamWorkspaceId}`),
  "cross-tenant workspace",
);
for (const [label, response] of [
  [
    "platform admin workspace content",
    await platform.get(`/api/v1/workspaces/${teamWorkspaceId}`),
  ],
  [
    "platform admin conversation",
    await platform.get(`/api/v1/conversations/${conversation.data.id}`),
  ],
]) {
  assert.ok(
    response.status === 403 || response.status === 404,
    `${label} must be denied, got ${response.status} ${response.text}`,
  );
  assert.ok(
    !response.text.includes("M03 部门空间") &&
      !response.text.includes("M03 部门会话"),
    `${label} leaked business content`,
  );
}
const outsiderList = await outsiderClient.get(
  "/api/v1/workspaces?page=1&size=100",
);
assert.equal(outsiderList.status, 200);
assert.ok(
  !JSON.stringify(outsiderList.data.items).includes(teamWorkspaceId),
  "cross-department workspace must not appear in list",
);

// 跨租户账号加入、单主部门与负责人约束。
const crossTenantAdd = await addDepartmentMember(
  platform,
  tenantA,
  sales.id,
  betaMemberId,
);
assertNotFound(crossTenantAdd, "cross-tenant account join");
assert.ok(
  !crossTenantAdd.text.includes("beta-member"),
  "cross-tenant join must not echo username",
);
// 负责人移交：旧负责人失去管理能力，新负责人获得；租户管理员仍可管理。
const managerCandidate = await createUser(
  platform,
  tenantA,
  `alpha-manager2-${suffix}`,
  ["MEMBER"],
);
assert.equal(
  (await addDepartmentMember(platform, tenantA, sales.id, managerCandidate.id))
    .status,
  201,
);
const firstManager = await transferManager(
  platform,
  tenantA,
  sales.id,
  alphaMemberId,
);
assert.equal(firstManager.status, 200, firstManager.text);
const removeCurrentManager = await removeDepartmentMember(
  platform,
  tenantA,
  sales.id,
  alphaMemberId,
);
assert.equal(removeCurrentManager.status, 409);
assert.equal(removeCurrentManager.data?.code, "RICCON-DEPT-409");
assert.equal(
  (
    await alphaMember.patch(`/api/v1/workspaces/${teamWorkspaceId}`, {
      name: `M03 负责人改名-${suffix}`,
    })
  ).status,
  200,
);
const newManager = await transferManager(
  platform,
  tenantA,
  sales.id,
  managerCandidate.id,
);
assert.equal(newManager.status, 200, newManager.text);
const oldManagerPatch = await alphaMember.patch(
  `/api/v1/workspaces/${teamWorkspaceId}`,
  { name: `M03 旧负责人改名-${suffix}` },
);
assert.equal(
  oldManagerPatch.status,
  403,
  `old manager must lose management capability: ${oldManagerPatch.status} ${oldManagerPatch.text}`,
);

// 成员移出后下一次请求即 404；CUSTOM 显式行不授权。
const removed = await removeDepartmentMember(
  platform,
  tenantA,
  sales.id,
  alphaMemberId,
);
assert.equal(removed.status, 204);
assertNotFound(
  await alphaMember.get(`/api/v1/workspaces/${teamWorkspaceId}`),
  "removed member workspace",
);
assertNotFound(
  await alphaMember.get(`/api/v1/workspaces/${customWorkspaceId}`),
  "removed member custom workspace",
);
const residualRows = (
  await runSql(
    `SELECT COUNT(*) AS total FROM workspace_member WHERE tenant_id = ${sqlString(tenantA)} AND workspace_id = ${binaryUuid(customWorkspaceId)};`,
    { repoRoot: root },
  )
).stdout
  .trim()
  .split("\n")
  .pop();
assert.equal(residualRows, "0", "removed member must clear custom rows");

// 重新加入后恢复；部门停用下一次请求 404，启用后恢复。
assert.equal(
  (await addDepartmentMember(platform, tenantA, sales.id, alphaMemberId))
    .status,
  201,
);
assert.equal(
  (await alphaMember.get(`/api/v1/workspaces/${teamWorkspaceId}`)).status,
  200,
);
assert.equal(
  (await setDepartmentStatus(platform, tenantA, sales.id, "disable")).status,
  200,
);
assertNotFound(
  await alphaMember.get(`/api/v1/workspaces/${teamWorkspaceId}`),
  "disabled department workspace",
);
assert.equal(
  (await setDepartmentStatus(platform, tenantA, sales.id, "enable")).status,
  200,
);
assert.equal(
  (await alphaMember.get(`/api/v1/workspaces/${teamWorkspaceId}`)).status,
  200,
);

// 平台部门端点的部门不匹配：A 的部门 ID 放进 B 的路径必须 404。
assertNotFound(
  await platform.get(
    `/api/v1/admin/tenants/${tenantB}/departments/${sales.id}/members`,
  ),
  "department tenant mismatch",
);

// 审计：部门事件写 platform + details.targetTenantId；空间事件写操作者租户；details 无 PII。
const audit = (
  await runSql(
    `
SELECT tenant_id, action, target_id, details
  FROM audit_event
 WHERE action IN ('department.created','department.member.removed','department.manager.changed',
                  'department.disabled','workspace.created')
   AND (target_id = ${sqlString(sales.id)} OR details LIKE ${sqlString(`%${sales.id}%`)})`,
    { repoRoot: root },
  )
).stdout;
assert.ok(
  audit.includes("department.created"),
  "department.created must be audited",
);
assert.ok(
  audit.includes("department.member.removed"),
  "department.member.removed must be audited",
);
assert.ok(
  audit.includes("department.manager.changed"),
  "department.manager.changed must be audited",
);
assert.ok(
  !audit.includes("@") && !audit.includes("m03-check-password"),
  "audit details must not contain email or password",
);
const departmentEventTenants = runSql(
  `
SELECT DISTINCT tenant_id FROM audit_event
 WHERE action LIKE 'department.%'
   AND details LIKE ${sqlString(`%${sales.id}%`)};`,
  { repoRoot: root },
).stdout
  .trim()
  .split("\n")
  .slice(1)
  .join(" ");
assert.ok(
  departmentEventTenants.includes("platform"),
  "department events must be recorded under platform tenant",
);
const workspaceEventTenants = runSql(
  `
SELECT DISTINCT tenant_id FROM audit_event
 WHERE action = 'workspace.created'
   AND target_id = ${sqlString(teamWorkspaceId)};`,
  { repoRoot: root },
).stdout
  .trim()
  .split("\n")
  .pop();
assert.equal(
  workspaceEventTenants,
  tenantA,
  "workspace events must be recorded under actor tenant",
);

// 平台管理员部门响应只含组织元数据。
const departmentPage = await platform.get(
  `/api/v1/admin/tenants/${tenantA}/departments?page=1&size=100`,
);
assert.equal(departmentPage.status, 200);
const department = departmentPage.data.items.find(
  (item) => item.id === sales.id,
);
assert.ok(department, "sales department must exist");
for (const forbidden of [
  "conversationId",
  "fileId",
  "reportId",
  "artifactId",
  "workspaceId",
]) {
  assert.equal(
    department[forbidden],
    undefined,
    `department response must not include ${forbidden}`,
  );
}
assert.equal(typeof department.memberCount, "number");
assert.ok(
  !JSON.stringify(departmentPage.data).includes("m03-scope.pdf"),
  "department response must not include business content",
);

const summary = {
  status: "passed",
  generatedAt: new Date().toISOString(),
  tenantA,
  tenantB,
  departments: { sales: sales.id, marketing: marketing.id },
  workspaces: { team: teamWorkspaceId, custom: customWorkspaceId },
  checked: [
    "cross-department workspace/conversation/file 404",
    "cross-tenant workspace and platform content denial",
    "single primary department and manager removal 409",
    "manager transfer old 403 / new 200",
    "member removal and department disable take effect next request",
    "platform department endpoint 404/409/403 and metadata-only response",
    "department/workspace audit tenant and details whitelist",
  ],
  note: "DEPT/TEN 的完整矩阵由 apps/api/src/test/java/com/riccon/api/security 下的 JUnit 套件覆盖；本脚本补充真实栈端到端复核。",
  residualCustomRows: residualRows,
};
const outFile = path.join(evidenceDir, "m03-checks.json");
writeFileSync(outFile, `${JSON.stringify(summary, null, 2)}\n`);
console.log(JSON.stringify(summary, null, 2));
