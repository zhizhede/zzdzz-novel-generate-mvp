-- P6 立项闸·母题黑名单（2026-10-17）：跨稿复读母题词面，⑥b 产出评审（derive_gate）注入比对。
-- kind 沿用 BLACKLIST（CHECK 白名单内），grp='MOTIF' 与⑧正文黑名单区分——⑧ 加载须排除 grp='MOTIF'
-- （母题是大纲层语义判据，不进正文 grep 硬判）。wordlist 无唯一约束，Flyway 一次性写入。
INSERT INTO wordlist (kind, word, grp) VALUES
  ('BLACKLIST', '抱遗骸疯唱安抚调', 'MOTIF'),
  ('BLACKLIST', '封禁舱内赤身抱襁褓', 'MOTIF'),
  ('BLACKLIST', '色相求饶被推开', 'MOTIF'),
  ('BLACKLIST', '隔门窥看后加固门闩', 'MOTIF'),
  ('BLACKLIST', '湿泥鞋印嫁祸侦探', 'MOTIF'),
  ('BLACKLIST', '替身伏法真身仍逃', 'MOTIF'),
  ('BLACKLIST', '风掀纸角按同一处', 'MOTIF');
