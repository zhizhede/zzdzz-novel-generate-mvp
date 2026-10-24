package com.zzdzz.novelgen.derive.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzdzz.novelgen.llm.LlmJson;
import com.zzdzz.novelgen.model.entity.derive.SourceChapterDO;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** v3.6 方案B ④d 文风卡：结构校验 + 正文头部拼接（≤30k）。 */
class DeriveStyleServiceTest {

    private static final ObjectMapper OM = new ObjectMapper();

    @Test
    void parseStyle_acceptsCompleteProfile() throws Exception {
        JsonNode root = OM.readTree("""
                {"v":1,"sentence":"短句为主","dialogue":"少对话，冷硬",
                 "narration":"叙述七景三","lexicon":{"idiom":"无","wenyan":"低","colloquial":"中"},
                 "imagery":["海风","盐","旧钟"],"pov":"三人称贴身",
                 "density":"每千字一转","no_go":["禁止成语堆砌","禁止诗词化旁白"]}
                """);
        assertEquals(root, DeriveStyleService.parseStyle(root));
    }

    @Test
    void parseStyle_rejectsMissingKeys() throws Exception {
        JsonNode noDensity = OM.readTree("""
                {"v":1,"sentence":"短句","dialogue":"冷硬","narration":"叙述",
                 "lexicon":{"idiom":"无","wenyan":"低","colloquial":"中"},
                 "imagery":["海风"],"pov":"三人称","no_go":["禁止注水"]}
                """);
        assertThrows(LlmJson.Bad.class, () -> DeriveStyleService.parseStyle(noDensity));

        JsonNode emptyGo = OM.readTree("""
                {"v":1,"sentence":"短句","dialogue":"冷硬","narration":"叙述",
                 "lexicon":{"idiom":"无","wenyan":"低","colloquial":"中"},
                 "imagery":["海风"],"pov":"三人称","density":"密","no_go":[]}
                """);
        assertThrows(LlmJson.Bad.class, () -> DeriveStyleService.parseStyle(emptyGo));
    }

    @Test
    void joinHead_takesPrefixAtChapterBoundary() {
        SourceChapterDO a = new SourceChapterDO();
        a.setSeq(1);
        a.setContent("一".repeat(20));
        SourceChapterDO b = new SourceChapterDO();
        b.setSeq(2);
        b.setContent("二".repeat(20));
        SourceChapterDO c = new SourceChapterDO();
        c.setSeq(3);
        c.setContent("三".repeat(20));

        String head = DeriveStyleService.joinHead(List.of(a, b, c), 30);
        assertEquals(30, head.length(), "截断到上限字数");
        assertTrue(head.startsWith("一".repeat(20)), "整章边界拼接：先完整第一章");
        assertTrue(head.contains("二"), "第二章参与拼接");

        String all = DeriveStyleService.joinHead(List.of(a, b, c), 100);
        assertEquals(62, all.length(), "20+1+20+1+20（章间换行计入）");
    }
}
