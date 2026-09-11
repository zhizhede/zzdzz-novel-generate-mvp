-- 伏笔自动提议：digest 识别新长线自动生成 PROPOSED 条目，人工采纳（→planned）或忽略（→dropped）
ALTER TABLE foreshadows ADD COLUMN proposed_in INTEGER;

ALTER TABLE foreshadows DROP CONSTRAINT foreshadows_status_check;
ALTER TABLE foreshadows ADD CONSTRAINT foreshadows_status_check
    CHECK (status IN ('proposed', 'planned', 'planted', 'recovered', 'dropped'));
