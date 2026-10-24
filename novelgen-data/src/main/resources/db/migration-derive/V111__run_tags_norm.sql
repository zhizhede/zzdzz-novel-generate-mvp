-- V111: 类别归一化记录落 run（⑤ 前置归一化，待办 #9 收口）
-- param_tags=映射后的池键；本列存原始 tags + 映射明细，供 seed 复现与审计
ALTER TABLE run ADD COLUMN tags_norm JSONB;
COMMENT ON COLUMN run.tags_norm IS '类别归一化记录（v3.5）：{"v":1,"raw":[原始词],"mapped":[池键],"steps":[{"input","key","confidence","reason"}]}；恒等映射（全部词本就是池键）不调 LLM';
