-- V122: beat_instance.source 扩宽以容纳 SYNTHETIC（9 字符）。
ALTER TABLE beat_instance
    ALTER COLUMN source TYPE VARCHAR(16);

COMMENT ON COLUMN beat_instance.source IS
    '拍来源：ARC_SEG=弧段拆拍 / ASSET=拍素材直采 / SYNTHETIC=结构槽补拍';
