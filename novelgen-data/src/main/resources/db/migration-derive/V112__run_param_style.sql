-- V112: 目标文风——run.param_style（v3.6 方案B）：⑤入参自由文本，⑦ 状态包注入优先于成品书 style_profile，冲突处弃源书习惯
ALTER TABLE run ADD COLUMN param_style TEXT;

COMMENT ON COLUMN run.param_style IS '目标文风（v3.6 方案B）：⑤入参自由文本（如"西方正剧腔、短句、零成语"），⑦ 注入优先于 story_settings.style_profile（源书文风卡）';
