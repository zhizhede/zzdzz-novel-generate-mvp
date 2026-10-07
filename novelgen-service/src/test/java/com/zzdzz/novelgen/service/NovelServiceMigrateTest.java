package com.zzdzz.novelgen.service;

import com.zzdzz.novelgen.service.OutlineService.SceneSpec;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 剧情迁移的节拍→场景映射基线（纯函数，不连库）：
 * 样本章级 beats（[{goal,outcome,conflict}]）是迁移后该章唯一方向约束，映射错就会把剧情写歪。
 */
class NovelServiceMigrateTest {

    @Test
    void beatsMapToScenesWithConflictAppendedAndOutcomeAsReveal() {
        String beats = "[{\"goal\":\"夜归旧村\",\"outcome\":\"母亲倚门\",\"conflict\":\"绕道避母\"},"
                + "{\"goal\":\"满月刃鸣\",\"outcome\":\"刃指古寺\",\"conflict\":\"寸步难离\"}]";
        List<SceneSpec> specs = NovelService.beatsToScenes(beats, 2700);
        assertThat(specs).hasSize(2);
        assertThat(specs.get(0).sceneNo()).isEqualTo(1);
        assertThat(specs.get(0).goal()).isEqualTo("夜归旧村（对抗：绕道避母）");
        assertThat(specs.get(0).mustReveal()).containsExactly("母亲倚门");
        assertThat(specs.get(0).mustNot()).isEmpty();
        assertThat(specs.get(1).sceneNo()).isEqualTo(2);
        assertThat(specs.get(1).goal()).isEqualTo("满月刃鸣（对抗：寸步难离）");
    }

    @Test
    void chapterBudgetIsSplitAcrossBeatsWithFloorAndCeiling() {
        // 章预算下限摊到节拍数：2 拍 → 1350 字/场；单拍 → 夹到上限 1500（不许一场吃掉整章）
        assertThat(NovelService.beatsToScenes("[{\"goal\":\"a\",\"outcome\":\"x\"},{\"goal\":\"b\",\"outcome\":\"y\"}]", 2700)).allSatisfy(s -> assertThat(s.words()).isEqualTo(1350));
        assertThat(NovelService.beatsToScenes("[{\"goal\":\"a\",\"outcome\":\"x\"}]", 2700).get(0).words())
                .isEqualTo(1500);
        // 摊到 5 拍以下限保底 300（短章也不会把单场压成一句话）
        String five = "[{\"goal\":\"1\",\"outcome\":\"a\"},{\"goal\":\"2\",\"outcome\":\"b\"},{\"goal\":\"3\",\"outcome\":\"c\"},"
                + "{\"goal\":\"4\",\"outcome\":\"d\"},{\"goal\":\"5\",\"outcome\":\"e\"}]";
        assertThat(NovelService.beatsToScenes(five, 900)).allSatisfy(s -> assertThat(s.words()).isEqualTo(300));
    }

    @Test
    void blankOrBadBeatsDegradeToEmpty() {
        assertThat(NovelService.beatsToScenes(null, 2700)).isEmpty();
        assertThat(NovelService.beatsToScenes("", 2700)).isEmpty();
        assertThat(NovelService.beatsToScenes("不是json", 2700)).isEmpty();
        assertThat(NovelService.beatsToScenes("{\"goal\":\"非数组\"}", 2700)).isEmpty();
        assertThat(NovelService.beatsToScenes("[]", 2700)).isEmpty();
        assertThat(NovelService.beatsToScenes("[{\"goal\":\"\",\"outcome\":\"\"}]", 2700)).isEmpty();
    }

    @Test
    void emptyBeatSkippedButOthersKeepTheirNo() {
        String beats = "[{\"goal\":\"\",\"outcome\":\"\"},{\"goal\":\"第二拍\",\"outcome\":\"结果\"}]";
        List<SceneSpec> specs = NovelService.beatsToScenes(beats, 2700);
        assertThat(specs).hasSize(1);
        // 场景号按 beats 里的位次走（跳过空拍不重排），与样本节拍一一对应便于人工核对
        assertThat(specs.get(0).sceneNo()).isEqualTo(2);
        assertThat(specs.get(0).goal()).isEqualTo("第二拍");
    }

    @Test
    void renameProtagonistOnlyWhenBothSidesUsable() {
        String t = "源主角与源配角甲同修，源主角问。";
        assertThat(NovelService.renameProtagonist(t, "源主角", "林峰")).isEqualTo("林峰与源配角甲同修，林峰问。");
        // 未指定原书主角名（protagonistFrom 空）/ 未设主视角 / 同名 → 原样返回，绝不乱改配角名
        assertThat(NovelService.renameProtagonist(t, null, "林峰")).isEqualTo(t);
        assertThat(NovelService.renameProtagonist(t, "源主角", "")).isEqualTo(t);
        assertThat(NovelService.renameProtagonist(t, "源主角", "源主角")).isEqualTo(t);
        assertThat(NovelService.renameProtagonist(null, "源主角", "林峰")).isNull();
    }
}
