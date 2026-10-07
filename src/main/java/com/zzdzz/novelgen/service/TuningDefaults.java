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
    /** 读者评审「复沓清单」触发去复沓修订的最低条数（评审通过也治；治不好不阻塞，照常落报告）。**≤0＝关闭**。 */
    public static final int READER_REPEAT_FIX_MIN = 3;
    /** 读者评审结构性四问（hook/stakes/continuity/consequence）未过时是否拦章：1=拦（默认）；0=只报不拦。 */
    public static final int READER_STRUCTURAL_BLOCK = 1;
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
    // AI 审校轮数（含首审；blocker 期间每轮带清单修订→复审）。2026-10-07 自动自愈闭环从 2 提到 3——
    // 「第 2 轮才冒出的新问题」是转人工主因（minor 首轮不修、次轮升级成 blocker 就没预算了）
    public static final int AI_REVIEW_ROUNDS = 3;
    // 审校 BLOCKER 后的保剧情重写（清正文+场景草稿，章纲/场景蓝图保留——换皮与迁移剧情不毁）：
    // 0=关；1=开（auto 模式第一档自愈，全模式可用，含迁移/换皮）
    public static final int REVIEW_REWRITE = 1;
    // 自愈穷尽仍 BLOCKER 的终态（仅 auto 审批模式生效；manual 永远转人工）：
    // 1=自动放行出厂（stage 事件标 auto_accepted 供离线抽检，2026-10-07 用户定调「别转人工」）；0=转人工
    public static final int REVIEW_AUTO_ACCEPT = 1;
    // 评审修订后机械复检未过的处置：KEEP=放行并标记（默认，原行为）/ ROLLBACK=回退到修订前 / REVISE=再修订
    public static final String GATE_RECHECK_ACTION = "KEEP";
    public static final int GATE_RECHECK_REVISE_ROUNDS = 1;
    public static final double RAG_MAX_DISTANCE = 0.55;
    // 导入小说深度解析：逐章 LLM 并发度；快速档抽样章数
    public static final int SAMPLE_PARSE_PARALLEL = 4;
    public static final int SAMPLE_FAST_CHAPTERS = 40;
    // 单次生成任务章数上限（契约施工 M4 欠账收口；无人续跑自动续批同口径）
    public static final int BATCH_MAX_CHAPTERS = 10;
    // 无人续跑规划卷数保险丝（防失控）
    public static final int AUTO_CONTINUE_MAX_VOLUMES = 50;
    // AI 大纲草稿并发生成数（批量开书排队消费）
    public static final int OUTLINE_DRAFT_PARALLEL = 4;

    private TuningDefaults() {
    }
}
