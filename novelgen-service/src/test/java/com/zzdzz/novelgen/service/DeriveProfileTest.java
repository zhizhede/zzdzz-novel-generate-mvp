package com.zzdzz.novelgen.service;

import com.zzdzz.novelgen.common.web.BizException;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * M0 新读口的测试面（规划 §6）：Recipe.parse 的 fail-open 与 D1 修正回归、
 * DeriveProfile 能力表快照（防表值被静默改错）——表值变化必须显式改这里。
 */
class DeriveProfileTest {

    private static Set<DeriveProfile.Capability> caps(DeriveProfile.Capability... c) {
        return Set.of(c);
    }

    private static final Set<DeriveProfile.Capability> ORIGINAL_CAPS = caps(
            DeriveProfile.Capability.CLONE_ASSETS,
            DeriveProfile.Capability.RUN_ORIGINALITY_REVIEW,
            DeriveProfile.Capability.INJECT_DERIVE_PROMPT);

    private static final Set<DeriveProfile.Capability> PROTECTED_CAPS = caps(
            DeriveProfile.Capability.PROTECT_BLUEPRINT,
            DeriveProfile.Capability.FORBID_VOLUME_PLAN,
            DeriveProfile.Capability.INJECT_DERIVE_PROMPT,
            DeriveProfile.Capability.QUEUE_BOOK_TASK,
            DeriveProfile.Capability.SEED_PRELUDE);

    /** fail-open：空/坏 JSON 一律 ORIGINAL 默认配方，绝不拦读。 */
    @Test
    void parseFailsOpenToOriginalDefaults() {
        for (String json : new String[]{null, "", "   ", "{oops", "[1,2]"}) {
            Recipe r = Recipe.parse(json);
            assertThat(r.preset()).isEqualTo(Recipe.Preset.ORIGINAL);
            assertThat(r.sources()).isEqualTo(Recipe.Sources.NONE);
            assertThat(r.sourceSampleId()).isNull();
            assertThat(DeriveProfile.of(r)).isEqualTo(ORIGINAL_CAPS);
            assertThat(DeriveProfile.injectSegment(r)).isNull();
        }
    }

    /** D1 修正回归：历史 mode=MIGRATE 归档 RESKIN 能力集，且续写用蓝图口径段而非差异红线。 */
    @Test
    void legacyMigrateModeArchivesToReskinCapabilities() {
        Recipe r = Recipe.parse("{\"mode\":\"MIGRATE\",\"sourceSampleId\":3,\"water\":40}");
        assertThat(r.preset()).isEqualTo(Recipe.Preset.RESKIN);
        assertThat(r.sourceSampleId()).isEqualTo(3L);
        assertThat(r.params().water()).isEqualTo(40);
        assertThat(DeriveProfile.can(r, DeriveProfile.Capability.PROTECT_BLUEPRINT)).isTrue();
        assertThat(DeriveProfile.can(r, DeriveProfile.Capability.FORBID_VOLUME_PLAN)).isTrue();
        assertThat(DeriveProfile.can(r, DeriveProfile.Capability.RUN_ORIGINALITY_REVIEW)).isFalse();
        assertThat(DeriveProfile.injectSegment(r)).isEqualTo("derive_migrate");
    }

    /** 旧 migrate() 对 MIGRATE‖RESKIN 同真——归档后两者能力集必须完全一致。 */
    @Test
    void legacyMigrateAndReskinShareIdenticalCapabilitySets() {
        Recipe migrate = Recipe.parse("{\"mode\":\"MIGRATE\",\"sourceSampleId\":1}");
        Recipe reskin = Recipe.parse("{\"mode\":\"RESKIN\",\"sourceSampleId\":1}");
        assertThat(migrate.preset()).isEqualTo(reskin.preset());
        assertThat(DeriveProfile.of(migrate)).isEqualTo(DeriveProfile.of(reskin));
    }

    /** 能力表快照：改表值必须同步改本断言。 */
    @Test
    void capabilityTableSnapshot() {
        Recipe original = Recipe.parse("{\"mode\":\"ORIGINAL\"}");
        Recipe reskin = Recipe.parse("{\"mode\":\"RESKIN\",\"sourceSampleId\":1}");
        Recipe splice = Recipe.parse("{\"mode\":\"SPLICE\",\"sources\":{\"samples\":[1,2]}}");
        assertThat(DeriveProfile.of(original)).isEqualTo(ORIGINAL_CAPS);
        assertThat(DeriveProfile.of(reskin)).isEqualTo(PROTECTED_CAPS);
        assertThat(DeriveProfile.of(splice)).isEqualTo(PROTECTED_CAPS);
    }

    /** 提示词段分派与旧 ContextPacker 口径对齐：有样本才注入；ORIGINAL 红线、取材产线蓝图口径。 */
    @Test
    void injectSegmentFollowsLegacyContextPackerSemantics() {
        Recipe originalWithSample = Recipe.parse("{\"mode\":\"ORIGINAL\",\"sourceSampleId\":9}");
        Recipe originalNoSample = Recipe.parse("{\"water\":50}");
        Recipe reskin = Recipe.parse("{\"mode\":\"RESKIN\",\"sourceSampleId\":9}");
        assertThat(DeriveProfile.injectSegment(originalWithSample)).isEqualTo("derive_redline");
        assertThat(DeriveProfile.injectSegment(originalNoSample)).isNull();
        assertThat(DeriveProfile.injectSegment(reskin)).isEqualTo("derive_migrate");
    }

    /** SPLICE 不变量：选材不足 2 本即拒（唯一会抛的解析分支）。 */
    @Test
    void spliceWithoutMultiSampleSourcesIsRejected() {
        assertThatThrownBy(() -> Recipe.parse("{\"mode\":\"SPLICE\",\"sourceSampleId\":1}"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("至少 2 本书");
        assertThatThrownBy(() -> Recipe.parse("{\"mode\":\"SPLICE\",\"sources\":{\"samples\":[1]}}"))
                .isInstanceOf(BizException.class);
    }

    /** 建书入队分派：仅取材产线出任务键。 */
    @Test
    void queueTaskKeyOnlyForBookTaskPresets() {
        Recipe original = Recipe.parse("{\"mode\":\"ORIGINAL\",\"sourceSampleId\":1}");
        Recipe reskin = Recipe.parse("{\"mode\":\"RESKIN\",\"sourceSampleId\":1}");
        Recipe splice = Recipe.parse("{\"mode\":\"SPLICE\",\"sources\":{\"samples\":[1,2]}}");
        assertThat(DeriveProfile.queueTaskKey(original)).isNull();
        assertThat(DeriveProfile.queueTaskKey(reskin)).isEqualTo("RESKIN");
        assertThat(DeriveProfile.queueTaskKey(splice)).isEqualTo("SPLICE");
    }

    /** 配方段解析：显式 clones/gates 落段；缺段按 sourceSampleId 推导。 */
    @Test
    void recipeSegmentsParseAndDerive() {
        Recipe explicit = Recipe.parse("""
                {"mode":"ORIGINAL","sourceSampleId":1,
                 "clones":{"characters":true,"cards":false,"world":true,"plotOutline":false},
                 "gates":"{\\"reader_structural_block\\":0}"}""");
        assertThat(explicit.clones()).isEqualTo(new Recipe.Clones(true, false, true, false));
        assertThat(explicit.gates()).contains("reader_structural_block");
        assertThat(Recipe.parse("{\"sourceSampleId\":1}").clones()).isEqualTo(Recipe.Clones.all());
        assertThat(Recipe.parse("{}").clones()).isEqualTo(Recipe.Clones.none());
    }

    /** params 收编：非 mode 的 15 处既有读口载荷经 params 原样可达（水/PVO/章数不丢）。 */
    @Test
    void paramsCarryLegacyCfgFields() {
        Recipe r = Recipe.parse("""
                {"mode":"RESKIN","sourceSampleId":2,"water":70,"pov":"第一人称",
                 "targetChapters":300,"autoContinue":true,"tags":["都市","灵异"]}""");
        assertThat(r.params().water()).isEqualTo(70);
        assertThat(r.params().pov()).isEqualTo("第一人称");
        assertThat(r.params().targetChapters()).isEqualTo(300);
        assertThat(r.params().autoContinueOn()).isTrue();
        assertThat(r.tags()).containsExactly("都市", "灵异");
    }
}
