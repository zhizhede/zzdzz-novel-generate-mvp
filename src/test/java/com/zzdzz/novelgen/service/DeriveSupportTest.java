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
    void modeDefaultsToOriginalAndOnlyMigrateIsOptIn() {
        // 缺省/未知/坏 JSON 一律按原创衍生（防复刻防线不因坏配置被静默关掉）
        assertThat(DeriveSupport.parse(null).migrate()).isFalse();
        assertThat(DeriveSupport.parse("{bad json").migrate()).isFalse();
        assertThat(DeriveSupport.parse("{\"mode\":\"WHATEVER\"}").migrate()).isFalse();
        assertThat(DeriveSupport.parse("{\"mode\":\"\"}").migrate()).isFalse();
        assertThat(DeriveSupport.parse("{\"mode\":\"ORIGINAL\"}").migrate()).isFalse();
        // MIGRATE 大小写与空白宽容
        assertThat(DeriveSupport.parse("{\"mode\":\"MIGRATE\"}").migrate()).isTrue();
        assertThat(DeriveSupport.parse("{\"mode\":\" migrate \"}").migrate()).isTrue();
        assertThat(DeriveSupport.normalizeMode(null)).isEqualTo(DeriveSupport.MODE_ORIGINAL);
        assertThat(DeriveSupport.normalizeMode("Migrate")).isEqualTo(DeriveSupport.MODE_MIGRATE);
    }

    @Test
    void reskinIsMigrateLikeButFlaggedSeparately() {
        DeriveSupport.Cfg r = DeriveSupport.parse("{\"mode\":\"RESKIN\"}");
        // 换皮同样是「按样本剧情走」：防复刻三闸与自愈换目标都要退让（migrate() 为真）
        assertThat(r.migrate()).isTrue();
        assertThat(r.reskin()).isTrue();
        DeriveSupport.Cfg m = DeriveSupport.parse("{\"mode\":\"MIGRATE\"}");
        assertThat(m.migrate()).isTrue();
        assertThat(m.reskin()).isFalse();
        assertThat(DeriveSupport.parse("{\"mode\":\"ORIGINAL\"}").reskin()).isFalse();
        assertThat(DeriveSupport.parse(null).reskin()).isFalse();
        assertThat(DeriveSupport.normalizeMode(" reskin ")).isEqualTo(DeriveSupport.MODE_RESKIN);
    }
}
