package com.zzdzz.novelgen.derive.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Phase4：模型重复 seq/错挂章不再让具体稿整轮停摆。 */
class DeriveDraftBatchRepairTest {

    private static final ObjectMapper M = new ObjectMapper();

    @Test
    void duplicateSeqIsDeduplicatedAndMovedBackToPlannedChapter() throws Exception {
        var root = M.readTree("""
                {"chapters":[
                  {"no":9,"title":"甲","draft_outline":"甲","time":"当晚","state_changes":[],
                   "beats":[{"seq":9,"filled":"C1第一拍"},{"seq":12,"filled":"C1重复拍"}]},
                  {"no":10,"title":"乙","draft_outline":"乙","time":"次日","state_changes":[],
                   "beats":[{"seq":10,"filled":"C1第二拍"},{"seq":11,"filled":"C1第三拍"},
                            {"seq":12,"filled":"C1计划内的拍"}]}
                ],"skipped":[]}
                """);
        var ctx = new DeriveDraftService.BatchContext(
                List.of(9, 10, 11, 12), List.of(9, 10), Set.of("C1"), Map.of(12, 10));

        var out = DeriveDraftService.parseBatch(root, ctx);

        assertEquals(2, out.path("chapters").size());
        assertEquals(3, out.path("chapters").get(1).path("beats").size());
        long seq12 = 0;
        for (var chapter : out.path("chapters")) {
            for (var beat : chapter.path("beats")) {
                if (beat.path("seq").asInt() == 12) {
                    seq12++;
                    assertEquals(10, chapter.path("no").asInt());
                }
            }
        }
        assertEquals(1, seq12);
    }

    @Test
    void missingChapterIsSynthesizedForWindowCoverage() throws Exception {
        var root = M.readTree("""
                {"chapters":[{"no":9,"title":"甲","draft_outline":"甲","time":"当晚",
                  "state_changes":[],"beats":[{"seq":9,"filled":"C1第一拍"},
                  {"seq":10,"filled":"C1第二拍"},{"seq":11,"filled":"C1第三拍"},
                  {"seq":12,"filled":"C1第四拍"}]}],"skipped":[]}
                """);
        var ctx = new DeriveDraftService.BatchContext(
                List.of(9, 10, 11, 12), List.of(9, 10), Set.of("C1"),
                Map.of(9, 9, 10, 9, 11, 10, 12, 10));

        var out = DeriveDraftService.parseBatch(root, ctx);

        assertEquals(2, out.path("chapters").size());
        assertEquals(2, out.path("chapters").get(1).path("beats").size());
    }
}


