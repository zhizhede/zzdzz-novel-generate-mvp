-- V119: 衍生侧世界状态账——把 V102 种子里闲置的 world_state 节点真正接进⑦写链
-- 背景：⑦ 前情此前只有「已写章正文尾段≤300字」，跨章时间/位置/脱困与否无结构化账本，
--       实弹出现「酒窖脱困写两次」类时序事故；主站有 world_states 随 digest 滚动，衍生侧从未建表。
-- 形态：一章一份 jsonb 快照（time/locations/present/statuses/key_facts/open_loops），
--       ⑦ 落库后用 world_state 节点抽取并 upsert；写下一章时注入最新快照。
-- 软删已下线：物理删除；一章一份靠普通唯一约束覆盖。
CREATE TABLE world_states (
    id              BIGSERIAL PRIMARY KEY,
    novel_id        BIGINT      NOT NULL REFERENCES novels (id) ON DELETE CASCADE,
    chapter_no      INT         NOT NULL,
    state           JSONB       NOT NULL,
    created_time    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_time    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_user_id BIGINT REFERENCES users (id),
    updated_user_id BIGINT REFERENCES users (id),
    CONSTRAINT uq_derive_world_states_ch UNIQUE (novel_id, chapter_no)
);
CREATE INDEX idx_derive_world_states_novel ON world_states (novel_id, chapter_no);

COMMENT ON TABLE world_states IS '衍生世界状态账：⑦ 每章写完抽取快照，写下一章注入防时序/位置/事件重复';
COMMENT ON COLUMN world_states.state IS '{"time","locations","present","statuses","key_facts","open_loops"}';
