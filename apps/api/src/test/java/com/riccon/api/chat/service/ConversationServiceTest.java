package com.riccon.api.chat.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** 会话标题生成规则。 */
class ConversationServiceTest {

    /** 短内容原样作为标题。 */
    @Test
    void autoTitleKeepsShortContent() {
        assertThat(ConversationService.autoTitle("你好世界")).isEqualTo("你好世界");
    }

    /** 空白被压缩为单个空格。 */
    @Test
    void autoTitleCollapsesWhitespace() {
        assertThat(ConversationService.autoTitle("  第一行\n第二行\t结束 ")).isEqualTo("第一行 第二行 结束");
    }

    /** 超过 20 个字符时截断并追加省略号。 */
    @Test
    void autoTitleTruncatesLongContent() {
        String title = ConversationService.autoTitle("一二三四五六七八九十一二三四五六七八九十一二");
        assertThat(title).isEqualTo("一二三四五六七八九十一二三四五六七八九十…");
    }

    /** 按 code point 截断，不拆分 emoji 代理对。 */
    @Test
    void autoTitleDoesNotSplitSurrogatePairs() {
        String content = "😀".repeat(21);
        String title = ConversationService.autoTitle(content);
        assertThat(title).isEqualTo("😀".repeat(20) + "…");
    }
}
