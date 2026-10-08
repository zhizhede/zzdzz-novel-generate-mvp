package com.zzdzz.novelgen.service;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 能力声明表（业务侧推翻重构 L2，规划 §3.2）：预设 → 能力集的**唯一读口**。
 * 消费方只问 can()/段名分派，不再知道 mode 的存在——同一谓词 7 处重复实现（规划 §2.1）
 * 收敛于此；表值按目标态定义，**改动必须同步 DeriveProfileTest 的能力表快照断言**。
 */
public final class DeriveProfile {

    /** 配方授予的能力。PROTECT/FORBID 守卫语义上无结构事实可依（规划 §3.1 修正），必须由配方携带。 */
    public enum Capability {
        PROTECT_BLUEPRINT,
        FORBID_VOLUME_PLAN,
        CLONE_ASSETS,
        RUN_ORIGINALITY_REVIEW,
        INJECT_DERIVE_PROMPT,
        QUEUE_BOOK_TASK,
        SEED_PRELUDE
    }

    private static final Map<Recipe.Preset, Set<Capability>> TABLE = Map.of(
            Recipe.Preset.ORIGINAL, EnumSet.of(
                    Capability.CLONE_ASSETS,
                    Capability.RUN_ORIGINALITY_REVIEW,
                    Capability.INJECT_DERIVE_PROMPT),
            Recipe.Preset.RESKIN, EnumSet.of(
                    Capability.PROTECT_BLUEPRINT,
                    Capability.FORBID_VOLUME_PLAN,
                    Capability.INJECT_DERIVE_PROMPT,
                    Capability.QUEUE_BOOK_TASK,
                    Capability.SEED_PRELUDE),
            Recipe.Preset.SPLICE, EnumSet.of(
                    Capability.PROTECT_BLUEPRINT,
                    Capability.FORBID_VOLUME_PLAN,
                    Capability.INJECT_DERIVE_PROMPT,
                    Capability.QUEUE_BOOK_TASK,
                    Capability.SEED_PRELUDE));

    private DeriveProfile() {
    }

    /** 预设的能力集（不可变副本）。未知预设 fail-open 到 ORIGINAL 口径。 */
    public static Set<Capability> of(Recipe recipe) {
        Set<Capability> caps = TABLE.get(recipe.preset());
        return Set.copyOf(caps != null ? caps : TABLE.get(Recipe.Preset.ORIGINAL));
    }

    public static boolean can(Recipe recipe, Capability capability) {
        return of(recipe).contains(capability);
    }

    /**
     * 场景提示词的衍生段名：ORIGINAL→差异红线段，取材产线（RESKIN/归档 MIGRATE/SPLICE）→蓝图口径段；
     * 无注入能力或无样本来源返回 null（与旧 ContextPackerService:78-86 口径逐一对齐）。
     * SPLICE 暂用蓝图口径段，专属拼接段 M4 接入时再分派。
     */
    public static String injectSegment(Recipe recipe) {
        if (!can(recipe, Capability.INJECT_DERIVE_PROMPT) || recipe.sourceSampleId() == null) {
            return null;
        }
        return recipe.preset() == Recipe.Preset.ORIGINAL ? "derive_redline" : "derive_migrate";
    }

    /** 建书入队任务键（TaskKind 口径）；无能力返回 null。只在建书时消费一次。 */
    public static String queueTaskKey(Recipe recipe) {
        if (!can(recipe, Capability.QUEUE_BOOK_TASK)) {
            return null;
        }
        return recipe.preset() == Recipe.Preset.RESKIN ? "RESKIN" : "SPLICE";
    }
}
