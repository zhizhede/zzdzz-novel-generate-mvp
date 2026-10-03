package com.zzdzz.novelgen.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzdzz.novelgen.llm.LlmJson;
import com.zzdzz.novelgen.llm.LlmPort;
import com.zzdzz.novelgen.model.entity.ChapterDO;
import com.zzdzz.novelgen.model.entity.MaterialCardDO;
import com.zzdzz.novelgen.model.entity.NovelDO;
import com.zzdzz.novelgen.service.data.ChapterDataService;
import com.zzdzz.novelgen.service.data.DigestDataService;
import com.zzdzz.novelgen.service.data.MaterialCardDataService;
import com.zzdzz.novelgen.service.data.NovelDataService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 书籍资产抽取：模型 JSON → 素材卡草稿 / 大纲 Markdown（纯解析），以及「已有卡跳过/覆盖」的口径。 */
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
        assertThat(lina.kind()).isEqualTo(MaterialCardDO.KIND_CHARACTER);
        assertThat(lina.aliases()).containsExactly("莉娜小姐");   // 与规范名相同的别名去掉
        assertThat(lina.pinned()).isTrue();
        assertThat(lina.sourceChapter()).isEqualTo(1);
        assertThat(drafts.get(1).kind()).isEqualTo(MaterialCardDO.KIND_MISC);
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
        assertThat(BookAssetExtractService.normalizeKind(" CHARACTER ")).isEqualTo(MaterialCardDO.KIND_CHARACTER);
        assertThat(BookAssetExtractService.normalizeKind("vehicles")).isEqualTo(MaterialCardDO.KIND_MISC);
        assertThat(BookAssetExtractService.normalizeKind(null)).isEqualTo(MaterialCardDO.KIND_MISC);
    }

    // ===== 已有素材卡的「跳过 / 覆盖」（解析链开关；默认不跳过）=====

    private static final String CARDS_JSON = """
            {"cards":[{"name":"莉娜","kind":"character","summary":"新摘要","content":"新正文",
                       "pinned":false,"sourceChapter":2}]}""";

    private MaterialCardDataService cardData;

    private BookAssetExtractService wired() {
        NovelDataService novelData = mock(NovelDataService.class);
        ChapterDataService chapterData = mock(ChapterDataService.class);
        DigestDataService digestData = mock(DigestDataService.class);
        cardData = mock(MaterialCardDataService.class);
        LlmPort llm = mock(LlmPort.class);
        PromptTemplateService prompts = mock(PromptTemplateService.class);
        BookAssetExtractService svc = new BookAssetExtractService(novelData, chapterData, digestData, cardData,
                null, llm, new LlmJson(llm, prompts), prompts);

        NovelDO novel = new NovelDO();
        novel.setTitle("黑潮号");
        when(novelData.getById(34L)).thenReturn(novel);
        ChapterDO ch = new ChapterDO();
        ch.setChapterNo(1);
        ch.setTitle("登船");
        when(chapterData.listSummariesByNovel(34L)).thenReturn(List.of(ch));
        when(digestData.listByNovel(34L))
                .thenReturn(List.of(new DigestDataService.DigestItem(1L, 1, "莉娜登船", "[]", "t")));
        when(llm.chat(any())).thenReturn(new LlmPort.ChatResult(1L, CARDS_JSON, null, new LlmPort.Usage(0, 0, 0)));
        return svc;
    }

    @Test
    void overwriteUpdatesExistingCardInPlace() {
        BookAssetExtractService svc = wired();
        MaterialCardDO existing = new MaterialCardDO();
        existing.setId(55L);
        existing.setKind(MaterialCardDO.KIND_CHARACTER);
        existing.setName("莉娜");
        existing.setPinned(true);
        existing.setStatus("archived");
        when(cardData.listByNovel(34L, null)).thenReturn(List.of(existing));
        when(cardData.exists(34L, MaterialCardDO.KIND_CHARACTER, "莉娜")).thenReturn(true);

        BookAssetExtractService.CardWriteResult w = svc.extractCards(34L, true);

        assertThat(w.created()).isZero();
        assertThat(w.updated()).isEqualTo(1);
        ArgumentCaptor<Boolean> pinned = ArgumentCaptor.forClass(Boolean.class);
        verify(cardData).update(eq(55L), eq("莉娜"), any(), eq("新摘要"), eq("新正文"),
                pinned.capture(), eq("archived"), eq(2));
        assertThat(pinned.getValue()).isTrue();          // 人工钉住标记不被模型结果抹掉
        verify(cardData, times(0)).insert(anyLong(), anyString(), anyString(), any(), anyString(), anyString(),
                anyBoolean(), anyString(), any());
    }

    @Test
    void skipExistingLeavesCardUntouched() {
        BookAssetExtractService svc = wired();
        when(cardData.exists(34L, MaterialCardDO.KIND_CHARACTER, "莉娜")).thenReturn(true);

        BookAssetExtractService.CardWriteResult skipped = svc.extractCards(34L, false);
        assertThat(skipped.created()).isZero();
        assertThat(skipped.updated()).isZero();

        verify(cardData, times(0)).update(anyLong(), any(), any(), any(), any(), any(), any(), any());
        verify(cardData, times(0)).insert(anyLong(), anyString(), anyString(), any(), anyString(), anyString(),
                anyBoolean(), anyString(), any());
    }

    @Test
    void freshCardIsInsertedInBothModes() {
        BookAssetExtractService svc = wired();
        when(cardData.listByNovel(34L, null)).thenReturn(List.of());
        when(cardData.exists(34L, MaterialCardDO.KIND_CHARACTER, "莉娜")).thenReturn(false);

        BookAssetExtractService.CardWriteResult fresh = svc.extractCards(34L, true);
        assertThat(fresh.created()).isEqualTo(1);
        assertThat(fresh.updated()).isZero();

        verify(cardData).insert(eq(34L), eq(MaterialCardDO.KIND_CHARACTER), eq("莉娜"), any(), eq("新摘要"),
                eq("新正文"), eq(false), eq("active"), eq(2));
        verify(cardData, times(0)).update(anyLong(), any(), any(), any(), any(), any(), any(), any());
    }
}
