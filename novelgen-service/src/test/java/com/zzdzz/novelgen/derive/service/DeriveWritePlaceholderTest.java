package com.zzdzz.novelgen.derive.service;

import com.zzdzz.novelgen.model.entity.derive.BeatInstanceDO;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * run45 实弹：⑦ 输出「请提供第12章正文」式助手反问后，旧校验先报「缺拍兑现契约/evidence 不在正文」，
 * 把模型推向继续索稿的死循环；内容红线（过程话/禁词/主角名）必须先于契约检查命中，
 * 且三轮兜底取稿不得让占位话按字数距离赢过真章。
 */
class DeriveWritePlaceholderTest {

    private static BeatInstanceDO beat(int seq) {
        BeatInstanceDO b = new BeatInstanceDO();
        b.setSeq(seq);
        return b;
    }

    @Test
    void assistantAskForPreviousDraftIsRejectedAsMetaLeak() {
        String raw = "我需要先确认上一稿的正文，因为拍兑现契约要求 evidence 从正文逐字摘录，"
                + "而我目前没有看到第12章的正文。\n请提供第12章《旧账对质》的正文内容。";
        String issue = DeriveWriteService.validate(raw, 900, 1100, "陆迟", false,
                List.of(beat(1)), false);
        assertNotNull(issue, "助手反问必须被打回");
        assertTrue(issue.contains("过程话"), "必须报过程话而不是契约问题：" + issue);
        assertFalse(issue.contains("拍兑现契约"), "契约反馈会误导模型继续索稿：" + issue);
    }

    @Test
    void metaLeakStillCheckedBeforeContractWhenContractMissing() {
        // 无契约 + 过程话：先报过程话（旧顺序先报「缺拍兑现契约」）
        String raw = "我先核对状态包，确认本章要兑现的内容，再动笔。";
        String issue = DeriveWriteService.validate(raw, 1, 5000, "陆迟", false,
                List.of(beat(1)), false);
        assertNotNull(issue);
        assertTrue(issue.contains("过程话"), issue);
    }

    @Test
    void contentCleanPrefersRealDraftOverPlaceholder() {
        String placeholder = "请提供第12章正文，我会根据正文逐字摘录 evidence。";
        String real = "陆迟推开门，潮气扑面而来。" + "他翻开本子，把今天的记录誊进笔录。".repeat(40);

        assertFalse(DeriveWriteService.contentClean(placeholder, "陆迟"),
                "占位助手话不是干净稿");
        assertTrue(DeriveWriteService.contentClean(real, "陆迟"), "真章必须判干净");
        assertFalse(DeriveWriteService.contentClean(real, "不存在的主角"),
                "缺主角名不算干净稿");
    }

    @Test
    void writePromptsForbidAskingForMaterials() {
        assertTrue(DeriveWriteService.WRITE_PROMPT_INDEPENDENT.contains("禁止反问"),
                "独立会话提示词必须禁止反问索稿");
        assertTrue(DeriveWriteService.WRITE_PROMPT_CONTINUOUS.contains("禁止反问"),
                "连续会话提示词必须禁止反问索稿");
    }
}