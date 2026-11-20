import type { ModelPurpose } from '@/services/api/types';
import type {
  AdminModel,
  AdminModelPage,
  ModelCredential,
  ModelCredentialCreateRequest,
  ModelCredentialPage,
  ModelCredentialRotateRequest,
  ModelPage,
  ModelProviderPage,
  ModelRouting,
  ModelRoutingList,
  ModelRoutingUpdateRequest,
  ModelStatus,
} from './types';

/**
 * M04 模型网关管理端能力集合。
 *
 * 页面只依赖该接口，组件测试注入替身；生产实现由 `ApiClient` 提供，
 * 复用统一的 CSRF 刷新、401 会话过期与 `ApiError` 归一语义。
 */
export interface ModelAdminApi {
  /** 员工可见模型目录（只读）；管理端只在需要核对模型标识时使用。 */
  listEmployeeModels(): Promise<ModelPage>;
  /** 平台模型目录（只读）；返回 provider、价格与启停状态。 */
  listAdminModels(): Promise<AdminModelPage>;
  /** 平台端点目录；`allowed` 与 `environmentLabel` 由部署配置派生。 */
  listModelProviders(): Promise<ModelProviderPage>;
  /** 启用或停用模型；停用后保留在路由链中但运行时跳过。 */
  updateModelStatus(modelId: string, status: ModelStatus): Promise<AdminModel>;
  /** 读取 CHAT/REPORT 两条路由链。 */
  getModelRouting(): Promise<ModelRoutingList>;
  /** 整链保存指定用途的模型顺序；版本不匹配返回 409。 */
  updateModelRouting(
    purpose: ModelPurpose,
    input: ModelRoutingUpdateRequest,
  ): Promise<ModelRouting>;
  /** 列出端点下的平台 key（掩码）。 */
  listModelCredentials(providerId: string): Promise<ModelCredentialPage>;
  /** 新增平台 key；明文只出现在请求体。 */
  createModelCredential(
    providerId: string,
    input: ModelCredentialCreateRequest,
  ): Promise<ModelCredential>;
  /** 轮换平台 key；服务端在同一事务中新增新 key 并停用旧 key。 */
  rotateModelCredential(
    providerId: string,
    credentialId: string,
    input: ModelCredentialRotateRequest,
  ): Promise<ModelCredential>;
  /** 停用平台 key；`changed=false` 表示已是停用态。 */
  disableModelCredential(providerId: string, credentialId: string): Promise<ModelCredential>;
  /** 恢复 DISABLED/INVALID key 为 ACTIVE，并清理失效标记与冷却。 */
  enableModelCredential(providerId: string, credentialId: string): Promise<ModelCredential>;
}
