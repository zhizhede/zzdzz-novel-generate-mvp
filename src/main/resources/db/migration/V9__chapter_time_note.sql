-- 卷纲时间跳跃：本章距上一章的时间跨度（自由文本，如「新年祭后第三日」），进章纲提示词与 digest 时间锚定
ALTER TABLE chapters ADD COLUMN time_note VARCHAR(128);
