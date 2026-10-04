-- V40 · entity_aliases：别名反查表（2026-10-05）——把 material_cards.aliases（jsonb 数组）变成可反查的索引。
--
-- 背景：素材卡的别名一直只存在 material_cards.aliases 这个 jsonb 数组里，只能「加载整张卡再在内存里比」，
-- 反过来问「正文/账里出现的这个名字是谁的别名」无路可走。人物状态账（V39）落库后这个问题变尖锐：
-- 账里的名字来自模型输出，可能是别名（老陆）、也可能带括号注释（孩子（沈砚之子，名沈砚）），
-- 与素材卡的名字对不上，任何按名字的比对都会漏。
--
-- 口径：
--   · 一行 = 一本书里的一个可反查名字 → 它所属的卡（alias 可以是卡名本身，is_primary=true 那一行）；
--   · 同一本书内一个名字只许指向一张卡（uq_entity_aliases_alias_alive）——两张卡抢同一个别名是无法自动裁决的歧义；
--   · card_name 冗余存一份：反查的调用方（名字归一化）要的就是卡名，省一次 join；卡改名时重建索引即可；
--   · **本表是 material_cards 的派生索引，不是第二个真源**：卡一写就整书重建（MaterialCardService），
--     存量书用 POST /api/novels/{id}/aliases/rebuild 补齐。
--
-- 为什么不直接把 aliases 拆成关系表替掉 jsonb：卡的读写路径（抽卡、克隆样本资产、界面编辑）都围着
-- 那个数组转，换成关系表要改四条链路且迁移期得双写。派生索引能拿到「可反查」这个收益，且随时可重建、可丢弃。

CREATE TABLE entity_aliases (
    id          BIGSERIAL PRIMARY KEY,
    novel_id    BIGINT      NOT NULL REFERENCES novels(id) ON DELETE CASCADE,
    card_id     BIGINT      NOT NULL REFERENCES material_cards(id) ON DELETE CASCADE,
    alias       VARCHAR(128) NOT NULL,
    card_name   VARCHAR(128) NOT NULL,
    card_kind   VARCHAR(32)  NOT NULL,
    is_primary  BOOLEAN     NOT NULL DEFAULT false,
    is_deleted  BOOLEAN     NOT NULL DEFAULT false,
    create_time TIMESTAMPTZ NOT NULL DEFAULT now(),
    update_time TIMESTAMPTZ NOT NULL DEFAULT now(),
    delete_time TIMESTAMPTZ
);

-- 软删已下线（is_deleted 是死列），谓词恒为真——沿用全库条件唯一索引写法保持一致
CREATE UNIQUE INDEX uq_entity_aliases_alias_alive ON entity_aliases (novel_id, alias) WHERE is_deleted = false;
CREATE INDEX idx_entity_aliases_card ON entity_aliases (card_id);
