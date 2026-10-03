-- V37 · 软删机制下线（2026-10-03 用户定调）：删除即物理删除，关联数据靠外键级联清走。
--
-- 背景：删除动作原来是「打 is_deleted 标记、子行留着可恢复」。改成真 DELETE 之后，
-- 外键原本是 NO ACTION（无级联），`DELETE FROM novels WHERE id=?` 会被 chapters 挡住
-- （实测报 violates foreign key constraint "chapters_novel_id_fkey"），所以删任何有内容的书都会直接失败。
--
-- 处置：给「书 / 章 / 样本」三种父行下的 19 个外键加 ON DELETE CASCADE——
--   删书   → 章、场景、门禁报告、步骤、事实账、伏笔、世界状态、素材卡、正典、任务、事件、复盘、向量
--   删章   → 场景、门禁报告、步骤、事实账（场景再往下带门禁报告）
--   删样本 → 资产卡、解析任务、剧情节点
--
-- 刻意**不加**级联的三个外键（父行被删不该牵走别的业务实体）：
--   novels.style_pack_id → style_packs、imported_samples.preset_id → style_packs、novels.user_id → users。
--   （删书时专属风格包由 NovelService 显式判「已无书引用」再删，见 StylePackMapper.deleteOrphanPack。）
--
-- 注意：is_deleted / delete_time 两列与那些带 `WHERE is_deleted=false` 的条件唯一索引**保留不动**
--   （同日定调「列留库里当死列，只清代码」）。存量软删行已由一次性清洗删除，故谓词恒为真、等同普通索引。

ALTER TABLE canon_docs           DROP CONSTRAINT canon_docs_novel_id_fkey,           ADD CONSTRAINT canon_docs_novel_id_fkey           FOREIGN KEY (novel_id)   REFERENCES novels(id)           ON DELETE CASCADE;
ALTER TABLE chapters             DROP CONSTRAINT chapters_novel_id_fkey,             ADD CONSTRAINT chapters_novel_id_fkey             FOREIGN KEY (novel_id)   REFERENCES novels(id)           ON DELETE CASCADE;
ALTER TABLE foreshadows          DROP CONSTRAINT foreshadows_novel_id_fkey,          ADD CONSTRAINT foreshadows_novel_id_fkey          FOREIGN KEY (novel_id)   REFERENCES novels(id)           ON DELETE CASCADE;
ALTER TABLE generation_tasks     DROP CONSTRAINT generation_tasks_novel_id_fkey,     ADD CONSTRAINT generation_tasks_novel_id_fkey     FOREIGN KEY (novel_id)   REFERENCES novels(id)           ON DELETE CASCADE;
ALTER TABLE import_analyze_tasks DROP CONSTRAINT import_analyze_tasks_novel_id_fkey, ADD CONSTRAINT import_analyze_tasks_novel_id_fkey FOREIGN KEY (novel_id)   REFERENCES novels(id)           ON DELETE CASCADE;
ALTER TABLE material_cards       DROP CONSTRAINT material_cards_novel_id_fkey,       ADD CONSTRAINT material_cards_novel_id_fkey       FOREIGN KEY (novel_id)   REFERENCES novels(id)           ON DELETE CASCADE;
ALTER TABLE outline_draft_tasks  DROP CONSTRAINT outline_draft_tasks_novel_id_fkey,  ADD CONSTRAINT outline_draft_tasks_novel_id_fkey  FOREIGN KEY (novel_id)   REFERENCES novels(id)           ON DELETE CASCADE;
ALTER TABLE pipeline_events      DROP CONSTRAINT pipeline_events_novel_id_fkey,      ADD CONSTRAINT pipeline_events_novel_id_fkey      FOREIGN KEY (novel_id)   REFERENCES novels(id)           ON DELETE CASCADE;
ALTER TABLE retro_proposals      DROP CONSTRAINT retro_proposals_novel_id_fkey,      ADD CONSTRAINT retro_proposals_novel_id_fkey      FOREIGN KEY (novel_id)   REFERENCES novels(id)           ON DELETE CASCADE;
ALTER TABLE volume_reviews       DROP CONSTRAINT volume_reviews_novel_id_fkey,       ADD CONSTRAINT volume_reviews_novel_id_fkey       FOREIGN KEY (novel_id)   REFERENCES novels(id)           ON DELETE CASCADE;
ALTER TABLE world_states         DROP CONSTRAINT world_states_novel_id_fkey,         ADD CONSTRAINT world_states_novel_id_fkey         FOREIGN KEY (novel_id)   REFERENCES novels(id)           ON DELETE CASCADE;

ALTER TABLE chapter_scenes       DROP CONSTRAINT chapter_scenes_chapter_id_fkey,     ADD CONSTRAINT chapter_scenes_chapter_id_fkey     FOREIGN KEY (chapter_id) REFERENCES chapters(id)         ON DELETE CASCADE;
ALTER TABLE chapter_steps        DROP CONSTRAINT chapter_steps_chapter_id_fkey,      ADD CONSTRAINT chapter_steps_chapter_id_fkey      FOREIGN KEY (chapter_id) REFERENCES chapters(id)         ON DELETE CASCADE;
ALTER TABLE digests              DROP CONSTRAINT digests_chapter_id_fkey,            ADD CONSTRAINT digests_chapter_id_fkey            FOREIGN KEY (chapter_id) REFERENCES chapters(id)         ON DELETE CASCADE;
ALTER TABLE gate_reports         DROP CONSTRAINT gate_reports_chapter_id_fkey,       ADD CONSTRAINT gate_reports_chapter_id_fkey       FOREIGN KEY (chapter_id) REFERENCES chapters(id)         ON DELETE CASCADE;
ALTER TABLE gate_reports         DROP CONSTRAINT gate_reports_scene_id_fkey,         ADD CONSTRAINT gate_reports_scene_id_fkey         FOREIGN KEY (scene_id)   REFERENCES chapter_scenes(id)   ON DELETE CASCADE;

ALTER TABLE sample_cards         DROP CONSTRAINT sample_cards_sample_id_fkey,        ADD CONSTRAINT sample_cards_sample_id_fkey        FOREIGN KEY (sample_id)  REFERENCES imported_samples(id) ON DELETE CASCADE;
ALTER TABLE sample_parse_tasks   DROP CONSTRAINT sample_parse_tasks_sample_id_fkey,  ADD CONSTRAINT sample_parse_tasks_sample_id_fkey  FOREIGN KEY (sample_id)  REFERENCES imported_samples(id) ON DELETE CASCADE;
ALTER TABLE sample_plot_nodes    DROP CONSTRAINT sample_plot_nodes_sample_id_fkey,   ADD CONSTRAINT sample_plot_nodes_sample_id_fkey   FOREIGN KEY (sample_id)  REFERENCES imported_samples(id) ON DELETE CASCADE;
