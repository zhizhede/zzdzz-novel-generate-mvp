-- 入库类型（2026-09-30）：区分一本书的来源——手动导入（用户上传 txt/mobi 或用 CLI 导原稿）、
-- 系统衍生（向导选了参考样本，derive_config.sourceSampleId 非空）、系统纯原创（向导无样本直接 AI 生成）。
-- 书籍管理页据此筛选与展示；写入点只有三处（NovelService.create / importBook、ImportRunner），读侧不猜。
ALTER TABLE novels ADD COLUMN IF NOT EXISTS source_type varchar(16);

-- 幂等回填：有源样本的判衍生，其余判纯原创（派生列就位前的历史行只有这两个取值）
UPDATE novels SET source_type = CASE
        WHEN derive_config->>'sourceSampleId' IS NOT NULL THEN 'DERIVED'
        ELSE 'ORIGINAL'
    END
WHERE source_type IS NULL;

-- 一次性修正：2026-09-10 由 ImportRunner 原稿导入产出的两本（早于网页面世，derive_config 为空会被上面误判为原创）。
-- 证据：style_packs 1/2（手搓风/漱石猫风）由同一次 CLI 导入创建，且这两本是当时唯一的作品行；
-- 本次同步已让 ImportRunner 直接写 IMPORTED，故仅需修正这两条历史行。
UPDATE novels SET source_type = 'IMPORTED'
WHERE source_type = 'ORIGINAL' AND title IN ('夜班守则', '黑猫今天也在观察人类');

ALTER TABLE novels ALTER COLUMN source_type SET DEFAULT 'ORIGINAL';
ALTER TABLE novels ALTER COLUMN source_type SET NOT NULL;
ALTER TABLE novels ADD CONSTRAINT novels_source_type_check
    CHECK (source_type IN ('IMPORTED', 'DERIVED', 'ORIGINAL'));
