package com.zzdzz.novelgen.service;

/**
 * tuning 调参键的代码默认值（fail-open 回退口径）：一处定义，全部调用方引用。
 * 改默认值只动这里；库内 tuning 行（素材库·调参页）仍可按书/平台覆盖。
 * 键名与 V15/V16/V20/V21 种子及 TuningService 读取逐字对应。
 */
public final class TuningDefaults {
    // 轮数与次数
    public static final int SCENE_REVISE_ROUNDS = 2;
    public static final int CHAPTER_REVISE_ROUNDS = 2;
    public static final int HEAL_RETRY_TIMES = 1;
    public static final int HEAL_REPLAN_TIMES = 1;
    public static final int VOLUME_PLAN_REVIEW_ROUNDS = 3;
    // 长度护栏（相对比例）
    public static final double CHAPTER_REVISE_LEN_MIN = 0.5;
    public static final double CHAPTER_REVISE_LEN_MAX = 1.15;
    public static final double READER_FIX_LEN_MIN = 0.75;
    public static final double READER_FIX_LEN_MAX = 1.15;
    public static final double AI_REVIEW_FIX_FLOOR = 0.60;
    // 读者评审阈值（连贯性优先口径：fat 降为报告项）
    public static final double READER_FAT_RATIO_BLOCK = 0.33;
    public static final double READER_FAT_RATIO_HARD = 0.50;
    // 场景长度带
    public static final double SCENE_LEN_MIN_RATIO = 0.4;
    public static final double SCENE_LEN_MAX_RATIO = 1.6;
    // 打回意见注入上限（字符）
    public static final int REJECT_REASON_MAX_LEN = 200;
    // 队列并行
    public static final int MAX_PARALLEL_NOVELS = 2;
    // 流式输出开关（0=回退阻塞）
    public static final int STREAM_LONG_TEXT = 1;
    // RAG
    public static final int RAG_ENABLED = 1;
    public static final int RAG_TOP_K = 6;
    public static final double RAG_MAX_DISTANCE = 0.55;

    private TuningDefaults() {
    }
}
