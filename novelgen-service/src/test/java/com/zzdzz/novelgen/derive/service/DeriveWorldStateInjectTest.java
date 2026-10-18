package com.zzdzz.novelgen.derive.service;

import com.zzdzz.novelgen.model.entity.derive.BeatInstanceDO;
import com.zzdzz.novelgen.model.entity.derive.ChapterDO;
import com.zzdzz.novelgen.model.entity.derive.NovelDO;
import com.zzdzz.novelgen.model.entity.derive.RunDO;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * V119 世界状态账接线：⑦ 注入上一章快照；META_LEAK 拦过程话（审稿实锤回归钉）。
 */
class DeriveWorldStateInjectTest {

    private final DeriveWriteService svc =
            new DeriveWriteService(null, null, null, null, null, null, null, null);

    private RunDO run() {
        RunDO run = new RunDO();
        run.setDomainSnapshot("{}");
        run.setParamTags("{\"推理\":1}");
        run.setParamPace(new BigDecimal("0.50"));
        run.setParamOutcome("闭合");
        run.setParamForm("SHORT_STANDALONE");
        run.setParamIntent("海边小镇探案。");
        return run;
    }

    private NovelDO novel() {
        NovelDO novel = new NovelDO();
        novel.setTitle("账本·试");
        novel.setStoryAssets("""
                {"protagonist":"林深","cast":[{"name":"林深"}],
                 "brief":{"premise":"少年探案","ending":""}}
                """);
        novel.setStorySettings("{}");
        return novel;
    }

    private ChapterDO chapter(int no) {
        ChapterDO ch = new ChapterDO();
        ch.setChapterNo(no);
        ch.setTitle("第" + no + "章");
        ch.setBudget(3000);
        ch.setDraftOutline("本章测试章纲");
        return ch;
    }

    private static final String PREV_STATE = """
            {"time":"凌晨两点四十一分","locations":{"林深":"洋馆外石阶"},
             "present":["林深","傅苓"],"statuses":{"谈叙":"仍困酒窖下层"},
             "key_facts":["暗门已从内侧封死"],"open_loops":["下层四人未脱困"]}
            """;

    @Test
    void buildUser_injectsWorldStateLedger() {
        String user = svc.buildUser(run(), novel(), chapter(2),
                List.<BeatInstanceDO>of(), List.of(chapter(1), chapter(2)), false, PREV_STATE);

        assertTrue(user.contains("世界状态账"), "写下一章必注入世界状态账");
        assertTrue(user.contains("凌晨两点四十一分"), "time 快照照常下发");
        assertTrue(user.contains("下层四人未脱困"), "open_loops 下发防重复脱困");
    }

    @Test
    void buildUser_withoutState_omitsLedgerKey() {
        String user = svc.buildUser(run(), novel(), chapter(1),
                List.<BeatInstanceDO>of(), List.of(chapter(1)), false, null);

        assertFalse(user.contains("世界状态账"), "首章无快照不注入空账");
    }

    @Test
    void validate_rejectsMetaLeaks() {
        assertTrue(DeriveWriteService.validate(
                "现在估算字数：前 2170 + 各段增量 ≈ 2415。他推开了门。",
                100, 5000, "林深", false).contains("过程话"));

        assertTrue(DeriveWriteService.validate(
                "先前怀疑你亲自送信，第十一章——他顿住。",
                100, 5000, "林深", false).contains("过程话"));

        assertTrue(DeriveWriteService.validate(
                "林深推开门，潮气扑面而来。",
                100, 5000, "林深", false) == null, "正常正文放行");
    }

    @Test
    void worldStatePrompt_hasTimeAntiPattern() {
        assertNotNull(DeriveWriteService.WORLD_STATE_PROMPT);
        assertTrue(DeriveWriteService.WORLD_STATE_PROMPT.contains("严禁反推总历时"),
                "时间防呆（AGENTS #22 同款）进抽取提示词");
        assertTrue(DeriveWriteService.WORLD_STATE_PROMPT.contains("open_loops"));
    }
}
