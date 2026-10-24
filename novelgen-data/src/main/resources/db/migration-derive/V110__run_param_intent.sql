-- V110: 意图层——run.param_intent（用户创作 brief；⑥b/⑦ 注入生成提示，⑧ domain 项让口；不参与⑤采样过滤）
ALTER TABLE run ADD COLUMN param_intent TEXT;
COMMENT ON COLUMN run.param_intent IS '题材意图 brief（v3.3 意图层）：⑥b/⑦ 作为创作方向注入（人物/走向/结局/描写红线），⑧ domain 项按意图贴合判定；不参与⑤采样过滤';
