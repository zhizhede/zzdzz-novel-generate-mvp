package com.zzdzz.novelgen.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 衍生配置换算基线：掺水量→评审阈值三元组、gate_config 覆盖、JSON 解析容错。 */
class DeriveSupportTest {

    @Test
    void waterFiftyIsDefaultGates() {
        double[] g = DeriveSupport.waterGates(50);
        assertThat(g[0]).isEqualTo(0.33);
        assertThat(g[1]).isEqualTo(0.50);
        assertThat(g[2]).isEqualTo(0.60);
    }

    @Test
    void dryTightensAndWateryLoosens() {
        double[] dry = DeriveSupport.waterGates(0);
        assertThat(dry[0]).isEqualTo(0.20);
        assertThat(dry[1]).isEqualTo(0.35);
        assertThat(dry[2]).isEqualTo(0.70);
        double[] watery = DeriveSupport.waterGates(100);
        assertThat(watery[0]).isEqualTo(0.50);
        assertThat(watery[1]).isEqualTo(0.65);
        assertThat(watery[2]).isEqualTo(0.50);
    }

    @Test
    void waterOutOfRangeClamped() {
        assertThat(DeriveSupport.waterGates(-30)).containsExactly(DeriveSupport.waterGates(0));
        assertThat(DeriveSupport.waterGates(999)).containsExactly(DeriveSupport.waterGates(100));
    }

    @Test
    void applyWaterGatesKeepsOtherKeysAndSurvivesBadJson() {
        String gate = "{\"banned_phrases\":[\"水词\"],\"budget_min\":2400,\"budget_max\":3400}";
        String out = DeriveSupport.applyWaterGates(gate, 20);
        assertThat(out).contains("\"banned_phrases\":[\"水词\"]");
        assertThat(out).contains("\"budget_min\":2400");
        assertThat(out).contains("\"reader_fat_ratio_block\":0.23");
        assertThat(out).contains("\"ai_review_fix_floor\":0.66");
        assertThat(DeriveSupport.applyWaterGates(null, 50)).contains("reader_fat_ratio_block");
        assertThat(DeriveSupport.applyWaterGates("不是json", 60)).contains("reader_fat_ratio_hard");
    }

    @Test
    void parseToleratesNullAndBadJson() {
        DeriveSupport.Cfg empty = DeriveSupport.parse(null);
        assertThat(empty.pov()).isNull();
        assertThat(empty.autoContinueOn()).isFalse();
        DeriveSupport.Cfg broken = DeriveSupport.parse("{bad json");
        assertThat(broken.water()).isNull();
        DeriveSupport.Cfg cfg = DeriveSupport.parse(
                "{\"water\":70,\"pov\":\"第三人称限知\",\"autoContinue\":true,\"priority\":1,\"sourceSampleId\":3}");
        assertThat(cfg.water()).isEqualTo(70);
        assertThat(cfg.pov()).isEqualTo("第三人称限知");
        assertThat(cfg.autoContinueOn()).isTrue();
        assertThat(cfg.priority()).isEqualTo(1);
        assertThat(cfg.sourceSampleId()).isEqualTo(3L);
    }

    @Test
    void densityHintBands() {
        assertThat(DeriveSupport.densityHint(null)).isNull();
        assertThat(DeriveSupport.densityHint(80)).contains("舒缓");
        assertThat(DeriveSupport.densityHint(50)).contains("均衡");
        assertThat(DeriveSupport.densityHint(10)).contains("拉满");
    }

    @Test
    void parseFailsOpenAndLegacyMigrateArchivesToReskin() {
        // 读侧 fail-open：空/坏 JSON/未知 mode 一律 ORIGINAL 默认配方（防复刻防线不因坏配置被静默关掉）
        assertThat(Recipe.parse(null).preset()).isEqualTo(Recipe.Preset.ORIGINAL);
        assertThat(Recipe.parse("{bad json").preset()).isEqualTo(Recipe.Preset.ORIGINAL);
        assertThat(Recipe.parse("{\"mode\":\"WHATEVER\"}").preset()).isEqualTo(Recipe.Preset.ORIGINAL);
        assertThat(Recipe.parse("{\"mode\":\"ORIGINAL\"}").preset()).isEqualTo(Recipe.Preset.ORIGINAL);
        // D1 拍板：历史 MIGRATE 归档 RESKIN 能力集（产线下线，两者能力表一致，蓝图口径提示词段不丢）
        assertThat(Recipe.parse("{\"mode\":\"MIGRATE\"}").preset()).isEqualTo(Recipe.Preset.RESKIN);
        assertThat(Recipe.parse("{\"mode\":\" migrate \"}").preset()).isEqualTo(Recipe.Preset.RESKIN);
        // 写侧归一与读侧同源（Recipe.ofRaw 单点）
        assertThat(Recipe.ofRaw(null)).isEqualTo(Recipe.Preset.ORIGINAL);
        assertThat(Recipe.ofRaw("WHATEVER")).isEqualTo(Recipe.Preset.ORIGINAL);
        assertThat(Recipe.ofRaw("Migrate")).isEqualTo(Recipe.Preset.RESKIN);
        assertThat(Recipe.ofRaw(" reskin ")).isEqualTo(Recipe.Preset.RESKIN);
        // 取材产线能力集：RESKIN 与归档 MIGRATE 完全一致（旧 migrate() 双值语义由能力表承接）
        assertThat(DeriveProfile.of(Recipe.parse("{\"mode\":\"RESKIN\",\"sourceSampleId\":1}")))
                .isEqualTo(DeriveProfile.of(Recipe.parse("{\"mode\":\"MIGRATE\",\"sourceSampleId\":1}")));
    }

    @Test
    void effectivePovChapterOverridesBookThenFallsBack() {
        // 章级压过书级（多视角轮换书逐章不同）
        assertThat(DeriveSupport.effectivePov("第三人称限知", "第一人称")).isEqualTo("第三人称限知");
        // 章级空/空白回退书级（换皮/迁移预物化、旧书的生效路径）
        assertThat(DeriveSupport.effectivePov(null, "第一人称（主角）")).isEqualTo("第一人称（主角）");
        assertThat(DeriveSupport.effectivePov("  ", "第一人称")).isEqualTo("第一人称");
        // 两级都空 → null（不约束不检查，旧书零变化）
        assertThat(DeriveSupport.effectivePov(null, null)).isNull();
        assertThat(DeriveSupport.effectivePov("", " ")).isNull();
        // 章级带头尾空白被 strip
        assertThat(DeriveSupport.effectivePov(" 第一人称 ", null)).isEqualTo("第一人称");
    }
}
