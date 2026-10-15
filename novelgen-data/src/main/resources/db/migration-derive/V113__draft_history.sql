-- V113: draft_history 正文草稿归档（附录 A-2 预留表启用）
-- 触发条件实弹发生（架构设计 v1.1 附录 A-2：「查看/对比被覆盖中间稿」的审计需求）：
-- run11 第 3 轮 REWORK→draft 清正文后 write 失败，前两轮 22826 字成稿全丢。
-- 归档时机：DeriveDraftService 打回清正文（clearForRewrite）之前，content 非空的章逐行落档。
CREATE TABLE draft_history (
    id               BIGSERIAL PRIMARY KEY,
    run_id           BIGINT      NOT NULL REFERENCES run (id),
    novel_id         BIGINT      NOT NULL,
    chapter_id       BIGINT      NOT NULL,
    chapter_no       INT         NOT NULL,
    version          INT         NOT NULL,  -- 归档序（=归档时 qc_round；同章同轮只归一次）
    qc_round         INT         NOT NULL,  -- 归档时 run.qc_round（被第几轮打回）
    content          TEXT        NOT NULL,  -- 被打回的整章正文
    char_count       INT         NOT NULL,
    reason           VARCHAR(16) NOT NULL,  -- REWORK（⑧打回）；预留 FAILED 等终态归档
    created_time     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_time     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_user_id  BIGINT,
    updated_user_id  BIGINT
);
CREATE INDEX idx_draft_history_run ON draft_history (run_id, chapter_no, version);

COMMENT ON TABLE draft_history IS '正文草稿版本归档（附录 A-2 预留表，V113 启用）：⑧打回清正文前落档，按章回看/对比被覆盖的中间稿';
COMMENT ON COLUMN draft_history.version IS '归档序（=归档时 qc_round）；同章 (chapter_id, version) 每次打回各一行';
COMMENT ON COLUMN draft_history.reason IS '归档原因：REWORK=⑧打回清稿前；预留 FAILED 等';
