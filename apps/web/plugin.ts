import type { IApi } from '@umijs/max';

/**
 * 项目级 Umi 插件。
 *
 * 目的：Umi 4 不再支持 document.ejs，需要通过 modifyHTML 注入 HTML 属性。
 * 这里只做一件事——给 <html> 补 lang="zh-CN"，满足 WCAG 2.1 AA 的语言标注要求。
 *
 * @param api Umi 插件 API
 */
export default (api: IApi) => {
  // 使用对象形式是为了让 TypeScript 从 IApi 的 modifyHTML 重载中推断出 cheerio 类型，
  // 避免在应用侧额外声明 cheerio 依赖。
  api.modifyHTML({
    fn: ($) => {
      $('html').attr('lang', 'zh-CN');
      return $;
    },
  });
};
