-- V15 · Tuning 开关中心：管线/门禁/提示词行为参数收编为 key-value + 说明。
-- 代码内数值保留为兜底默认；删行即回退默认。素材库「调参」页可改，30s 缓存内生效。
CREATE TABLE tuning (
    id          BIGSERIAL PRIMARY KEY,
    tkey        VARCHAR(100) NOT NULL,
    tvalue      VARCHAR(200) NOT NULL,
    description VARCHAR(500) NOT NULL DEFAULT '',
    is_deleted  BOOLEAN NOT NULL DEFAULT FALSE,
    create_time TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    update_time TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    delete_time TIMESTAMPTZ
);
CREATE UNIQUE INDEX uq_tuning_key ON tuning(tkey) WHERE is_deleted = FALSE;

INSERT INTO tuning (tkey, tvalue, description) VALUES
    ('scene_revise_rounds', '2', '场景门禁未过带意见重写的最大轮数'),
    ('chapter_revise_rounds', '2', '章级门禁未过带意见修订的最大轮数'),
    ('chapter_revise_len_min', '0.5', '章修订稿长度下限（相对原稿字符比，低于即弃稿）'),
    ('chapter_revise_len_max', '1.15', '章修订稿长度上限（相对原稿字符比，超出即弃稿防膨胀）'),
    ('scene_len_min_ratio', '0.4', '场景长度下限（相对场景字数预算）'),
    ('scene_len_max_ratio', '1.6', '场景长度上限（相对场景字数预算，43 章超长实锤后新增）'),
    ('simile_per1k_abs_max', '8.0', '章级门禁比喻密度硬上限（次/千字）'),
    ('ai_review_fix_floor', '0.6', '审校修订稿保底长度（相对原稿字符比，低于即弃用保留原文）'),
    ('reader_fix_len_min', '0.5', '读者评审重写稿长度下限（相对原稿字符比）'),
    ('reader_fix_len_max', '1.15', '读者评审重写稿长度上限（相对原稿字符比）'),
    ('reader_fat_ratio_block', '0.33', '读者评审判 blocker 的注水率阈值（写进评审提示词）'),
    ('heal_retry_times', '1', '章节失败后直接重试次数（自愈梯子第一级）'),
    ('heal_replan_times', '1', '重试仍败后换目标重写卷纲的再试次数（自愈梯子第二级）'),
    ('volume_plan_review_rounds', '3', '卷纲 AI 规划审校 BLOCKER 重写的最大轮数'),
    ('pack_max_matched_cards', '12', '场景注入别名命中素材卡的上限'),
    ('prompt_simile_per1k', '3', '场景生成提示词中的比喻密度红线（次/千字）');
