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
    // 伏笔自动园艺：提议过期章龄（digest 扫描归档 dropped，0 关闭）
    public static final int FORESHADOW_PROPOSED_MAX_AGE = 20;
    // 失败任务自动重试次数上限（章级自愈梯子尽后的任务级重排，0 关闭）
    public static final int TASK_AUTO_RETRY_TIMES = 1;
    // 审校复审仍 BLOCKER 的处置：0=转人工（默认）；1=自动清正文换目标重写一轮，仍不过才转人工
    public static final int REVIEW_BLOCKER_REPLAN = 0;
    public static final double RAG_MAX_DISTANCE = 0.55;
    // 导入小说深度解析：逐章 LLM 并发度；快速档抽样章数
    public static final int SAMPLE_PARSE_PARALLEL = 4;
    public static final int SAMPLE_FAST_CHAPTERS = 40;
    // 单次生成任务章数上限（契约施工 M4 欠账收口；无人续跑自动续批同口径）
    public static final int BATCH_MAX_CHAPTERS = 10;
    // 无人续跑规划卷数保险丝（防失控）
    public static final int AUTO_CONTINUE_MAX_VOLUMES = 50;

    private TuningDefaults() {
    }
}
