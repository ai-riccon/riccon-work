# 测试计时报告（surefire）

- 生成时间：2026-11-22T08:39:41.999+08:00
- 报告根目录：`/Users/ricky/Documents/projects/riccon-work`
- 用例总数：**1115**
- 累计 suite 时间：**525.241s**

## 按模块汇总

| 模块 | suite | tests | failures | errors | skipped | 时间 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| apps/api | 120 | 642 | 0 | 0 | 0 | 413.843s |
| apps/worker | 66 | 356 | 0 | 0 | 0 | 103.264s |
| platform/platform-architecture-tests | 1 | 12 | 0 | 0 | 0 | 6.781s |
| platform/platform-common | 5 | 87 | 0 | 0 | 0 | 0.825s |
| platform/platform-domain | 4 | 18 | 0 | 0 | 0 | 0.528s |
| **总计** | 196 | 1115 | 0 | 0 | 0 | 525.241s |

## 最慢 20 个测试类

| # | 测试类 | 模块 | tests | 时间 |
| ---: | --- | --- | ---: | ---: |
| 1 | `com.riccon.worker.research.ApprovalParamsCryptoTest` | apps/worker | 9 | 56.317s |
| 2 | `com.riccon.api.WorkspaceScopeTest` | apps/api | 16 | 33.878s |
| 3 | `com.riccon.api.security.TenantIsolationSecurityTest` | apps/api | 31 | 33.311s |
| 4 | `com.riccon.api.research.ResearchModelUnavailableMappingTest` | apps/api | 3 | 33.066s |
| 5 | `com.riccon.api.migration.V10MigrationTest` | apps/api | 11 | 22.999s |
| 6 | `com.riccon.api.security.DepartmentIsolationTest` | apps/api | 11 | 20.350s |
| 7 | `com.riccon.api.migration.V11MigrationTest` | apps/api | 16 | 18.812s |
| 8 | `com.riccon.api.m10.audit.AuditExportApiTest` | apps/api | 10 | 16.811s |
| 9 | `com.riccon.api.migration.V9MigrationTest` | apps/api | 11 | 13.570s |
| 10 | `com.riccon.api.m10.audit.AuditQueryApiTest` | apps/api | 10 | 12.570s |
| 11 | `com.riccon.api.m10.identity.OidcIdentityBindingApiTest` | apps/api | 14 | 12.310s |
| 12 | `com.riccon.api.ChatStreamApiTest` | apps/api | 11 | 10.955s |
| 13 | `com.riccon.api.AuthFlowTest` | apps/api | 15 | 9.921s |
| 14 | `com.riccon.api.DepartmentAdminApiTest` | apps/api | 11 | 9.771s |
| 15 | `com.riccon.worker.research.ReportArtifactIntegrationTest` | apps/worker | 10 | 8.685s |
| 16 | `com.riccon.api.ContentScopeApiTest` | apps/api | 4 | 8.253s |
| 17 | `com.riccon.api.ChatScopeApiTest` | apps/api | 3 | 8.025s |
| 18 | `com.riccon.api.ResearchTaskApiTest` | apps/api | 7 | 7.744s |
| 19 | `com.riccon.api.chat.ChatModelUnavailableApiTest` | apps/api | 6 | 7.603s |
| 20 | `com.riccon.api.m10.audit.AuditExportPlatformApiTest` | apps/api | 5 | 7.281s |
