package com.zzdzz.novelgen.llm;

/**
 * LLM 节点温度表：具名常量替代散落调用点的魔法小数（与 LlmNode 注册表对应）。
 * 语义分层：创作类高（要味道）→ 修订类中（收敛但不僵）→ 判定/抽取类低（要稳）。
 */
public final class LlmTemps {
    /** 场景正文生成（要味道） */
    public static final double SCENE_DRAFT = 0.9;
    /** 开书向导·全书大纲草稿（创作型） */
    public static final double DERIVE_OUTLINE = 0.8;
    /** 场景门禁重写 */
    public static final double SCENE_REVISE = 0.8;
    /** 章级修订 */
    public static final double CHAPTER_REVISE = 0.5;
    /** 读者评审修复稿 */
    public static final double READER_FIX = 0.5;
    /** 读者评审「删过头」恢复扩写 */
    public static final double RECOVERY_EXPAND = 0.5;
    /** 审校 BLOCKER 修复稿 */
    public static final double AI_REVIEW_REVISE = 0.5;
    /** AI 章纲（要稳的 JSON） */
    public static final double OUTLINE = 0.3;
    /** 读者评审（判定） */
    public static final double READER_REVIEW = 0.2;
    /** AI 语义审校（判定） */
    public static final double AI_REVIEW = 0.2;
    /** digest 四产出（抽取） */
    public static final double DIGEST = 0.3;
    /** 世界状态回填（抽取） */
    public static final double WORLD_STATE = 0.2;
    /** 卷纲整卷规划（创作） */
    public static final double VOLUME_PLAN = 0.6;
    /** 卷纲 AI 审校（判定） */
    public static final double VOLUME_PLAN_REVIEW = 0.2;
    /** 单章卷纲重写/自愈换目标（创作） */
    public static final double CHAPTER_REPLAN = 0.6;
    /** 卷级复盘漂移分析（分析） */
    public static final double VOLUME_REVIEW = 0.3;

    private LlmTemps() {
    }
}
