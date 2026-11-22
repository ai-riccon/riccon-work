// 假模型模块的公共入口；B11a/B11b 与真实栈脚本统一从这里引用。
//
// 典型用法（同进程）：
//   import { startFakeModelServer } from '../support/fake-model/index.mjs';
//   const fake = await startFakeModelServer({ port: 0, globalScenario: 'ok' });
//   fake.control.setKeyScenarios({ 'bad-key': 'server_error' });
//   await fake.close();
//
// 独立进程（真实栈）由 `scripts/real-stack/fake-model.mjs` 启动；控制端点见 server.mjs 头注释。
export {
  FIXED_USAGE,
  REPORT_TEXT,
  SCENARIOS,
  SCENARIO_ALIASES,
  SCENARIO_NAMES,
  errorBody,
  fixedChunks,
  normalizeScenario,
} from './scenarios.mjs';
export { createFakeModelServer, startFakeModelServer } from './server.mjs';
