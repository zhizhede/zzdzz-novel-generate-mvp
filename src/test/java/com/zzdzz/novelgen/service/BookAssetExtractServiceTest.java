package com.zzdzz.novelgen.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzdzz.novelgen.model.dto.MaterialCardDTO;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 书籍资产抽取的**输出解析**（纯函数，不连库不调 LLM）：模型 JSON → 素材卡草稿 / 大纲 Markdown。 */
class BookAssetExtractServiceTest {

    private static final ObjectMapper M = new ObjectMapper();

    private static BookAssetExtractService service() {
        return new BookAssetExtractService(null, null, null, null, null, null, null, null);
    }

    // ===== 素材卡 =====

    @Test
    void cardsJsonMapsToDraftsWithKindNormalizedAndDuplicatesDropped() throws Exception {
        var node = M.readTree("""
                {"cards":[
                  {"name":"莉娜","kind":"character","aliases":["莉娜小姐","莉娜"],"summary":"灯塔看守之女",
                   "content":"与黑潮号有旧约","pinned":true,"sourceChapter":1},
                  {"name":"莉娜","kind":"character","summary":"重复行应当被丢"},
                  {"name":"黑潮号","kind":"vehicles","summary":"未知类型归 misc"},
                  {"name":"","kind":"item","summary":"空名丢弃"}]}""");
        List<BookAssetExtractService.CardDraft> drafts = service().parseCards(node);

        assertThat(drafts).hasSize(2);
        BookAssetExtractService.CardDraft lina = drafts.get(0);
        assertThat(lina.name()).isEqualTo("莉娜");
        assertThat(lina.kind()).isEqualTo(MaterialCardDTO.KIND_CHARACTER);
        assertThat(lina.aliases()).containsExactly("莉娜小姐");   // 与规范名相同的别名去掉
        assertThat(lina.pinned()).isTrue();
        assertThat(lina.sourceChapter()).isEqualTo(1);
        assertThat(drafts.get(1).kind()).isEqualTo(MaterialCardDTO.KIND_MISC);
        assertThat(drafts.get(1).sourceChapter()).isNull();
    }

    @Test
    void cardsJsonWithoutArrayYieldsEmpty() throws Exception {
        var node = M.readTree("{\"cards\":\"oops\"}");
        assertThat(service().parseCards(node)).isEmpty();
    }

    // ===== 大纲 =====

    @Test
    void outlineJsonRendersMarkdownSkippingBlankSections() throws Exception {
        var node = M.readTree("""
                {"title":"黑潮号","premise":"一艘船的账本","mainline":"从打捞到远航","theme":"",
                 "arcs":[{"title":"卷一 登船","summary":"接活与第一次出海"},{"title":"","summary":""}],
                 "ending":"账本封存"}""");
        String md = service().renderOutline(node);

        assertThat(md).startsWith("# 全书大纲：《黑潮号》");
        assertThat(md).contains("## 一句话前提").contains("一艘船的账本");
        assertThat(md).contains("## 主线").contains("从打捞到远航");
        assertThat(md).doesNotContain("## 主题");                    // 空字段整段跳过
        assertThat(md).contains("## 分卷弧线").contains("**卷一 登船**：接活与第一次出海");
        assertThat(md).contains("## 结局走向").contains("账本封存");
    }

    @Test
    void outlineJsonWithoutTitleStillRenders() throws Exception {
        var node = M.readTree("{\"mainline\":\"只有主线\"}");
        String md = service().renderOutline(node);
        assertThat(md).startsWith("# 全书大纲");
        assertThat(md).contains("只有主线");
    }

    // ===== 围栏与小工具 =====

    @Test
    void codeFenceIsStrippedFromWorldText() {
        assertThat(BookAssetExtractService.stripFence("```markdown\n# 世界观\n文本\n```")).isEqualTo("# 世界观\n文本");
        assertThat(BookAssetExtractService.stripFence("没有围栏")).isEqualTo("没有围栏");
    }

    @Test
    void normalizeKindFallsBackToMisc() {
        assertThat(BookAssetExtractService.normalizeKind(" CHARACTER ")).isEqualTo(MaterialCardDTO.KIND_CHARACTER);
        assertThat(BookAssetExtractService.normalizeKind("vehicles")).isEqualTo(MaterialCardDTO.KIND_MISC);
        assertThat(BookAssetExtractService.normalizeKind(null)).isEqualTo(MaterialCardDTO.KIND_MISC);
    }
}
