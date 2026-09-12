package com.zzdzz.novelgen.llm;

/**
 * LLM 节点名注册表：llm_call_log 的成本归集维度、llm_node_config 的路由键、
 * 素材库「模型路由」页的行清单都以此为准。新节点先在此登记再用。
 */
public final class LlmNode {

    // ===== 管线：章内 =====
    public static final String OUTLINE = "outline";                 // AI 章纲（场景拆解）
    public static final String SCENE_DRAFT = "scene_draft";         // 场景正文生成
    public static final String SCENE_REVISE = "scene_revise";       // 场景门禁失败重写
    public static final String CHAPTER_REVISE = "chapter_revise";   // 章级机械门禁失败修订
    public static final String READER_REVIEW = "reader_review";     // 读者评审（反无聊闸门）
    public static final String READER_FIX = "reader_fix";           // 读者评审判 blocker 后重写
    public static final String AI_REVIEW = "ai_review";             // AI 语义审校
    public static final String AI_REVIEW_REVISE = "ai_review_revise"; // 审校 BLOCKER 修订
    public static final String DIGEST = "digest";                   // 事实账+世界状态+伏笔提议
    public static final String WORLD_STATE = "world_state";         // 存量章世界状态回填

    // ===== 管线：卷规划 =====
    public static final String VOLUME_PLAN = "volume_plan";         // 卷纲整卷规划
    public static final String VOLUME_PLAN_REVIEW = "volume_plan_review"; // AI 规划审校
    public static final String VOLUME_REVIEW = "volume_review";     // 卷级复盘（漂移分析）
    public static final String CHAPTER_REPLAN = "chapter_replan";   // 单章卷纲重写/自愈换目标

    // ===== 工具 =====
    public static final String EMBEDDING = "embedding";             // embo-01 向量化（RAG 语义检索）
    public static final String SMOKE = "smoke";                     // 冒烟连通性测试

    private LlmNode() {}
}
