package com.zzdzz.novelgen.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.zzdzz.novelgen.model.entity.CanonDocDO;
import com.zzdzz.novelgen.model.entity.ImportedSampleDO;
import com.zzdzz.novelgen.model.entity.MaterialCardDO;
import com.zzdzz.novelgen.model.entity.NovelDO;
import com.zzdzz.novelgen.model.entity.SampleCardDO;
import com.zzdzz.novelgen.model.entity.SamplePlotNodeDO;
import com.zzdzz.novelgen.model.entity.StylePackDO;
import com.zzdzz.novelgen.model.entity.ChapterDO;
import com.zzdzz.novelgen.model.enums.ChapterStatus;
import com.zzdzz.novelgen.model.enums.NovelSourceType;
import com.zzdzz.novelgen.model.enums.PlanMode;
import com.zzdzz.novelgen.model.dto.NovelCreateDTO;
import com.zzdzz.novelgen.model.vo.NovelImportVO;
import com.zzdzz.novelgen.common.web.BizException;
import com.zzdzz.novelgen.llm.LlmJson;
import com.zzdzz.novelgen.llm.LlmNode;
import com.zzdzz.novelgen.llm.LlmPort;
import com.zzdzz.novelgen.llm.LlmTemps;
import com.zzdzz.novelgen.common.web.ErrorCode;
import com.zzdzz.novelgen.service.data.CanonDocDataService;
import com.zzdzz.novelgen.service.data.ImportedSampleDataService;
import com.zzdzz.novelgen.service.data.MaterialCardDataService;
import com.zzdzz.novelgen.service.data.NovelDataService;
import com.zzdzz.novelgen.service.data.SampleCardDataService;
import com.zzdzz.novelgen.service.data.SamplePlotNodeDataService;
import com.zzdzz.novelgen.service.data.StylePackDataService;
import com.zzdzz.novelgen.model.vo.NovelVO;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** 作品查询、开书（预设克隆 + 样本资产克隆 + 衍生配置）与审批模式切换。 */
@Service
@Slf4j
@RequiredArgsConstructor
public class NovelService {

    private final NovelDataService novelData;
    private final StylePackDataService stylePackData;
    private final com.zzdzz.novelgen.service.data.ChapterDataService chapterData;
    private final DigestService digestService;
    private final SampleCardDataService sampleCardData;
    private final SamplePlotNodeDataService plotData;
    private final ImportedSampleDataService sampleData;
    private final MaterialCardDataService cardData;
    private final CanonDocDataService canonData;
    private final com.zzdzz.novelgen.service.data.GenerationTaskDataService taskData;
    private final LlmPort llm;
    private final LlmJson llmJson;
    private final PromptTemplateService promptTemplates;
    private final ObjectMapper mapper;

    public List<NovelVO> list() {
        return list(null);
    }

    /**
     * 书籍管理列表：读全量活书 → 按条件筛选 → 排序（书籍量级为百千级，读侧内存筛，不额外落 SQL）。
     * 筛选/排序语义见 {@link #matches} 与 {@link #comparator}（纯函数，单测锁定）。
     */
    public List<NovelVO> list(com.zzdzz.novelgen.model.dto.NovelQueryDTO condition) {
        return novelData.listAlive().stream().map(n -> {
            DeriveSupport.Cfg c = DeriveSupport.parse(novelData.findDeriveConfig(n.getId()));
            return new NovelVO(n.getId(), n.getTitle(), n.getDescription(), n.getApprovalMode(),
                    n.getStatus(), NovelSourceType.normalize(n.getSourceType()),
                    novelData.chapterCount(n.getId()), n.getCreateTime(),
                    c.autoContinueOn(), c.targetChapters());
        })
                .filter(row -> matches(row, condition))
                .sorted(comparator(condition == null ? null : condition.sort()))
                .toList();
    }

    /**
     * 单行条件判定（纯函数：无库依赖，便于单测锁定筛选语义）。
     * keyword 命中书名/简介；未填的条件不参与筛选。
     */
    static boolean matches(NovelVO row, com.zzdzz.novelgen.model.dto.NovelQueryDTO q) {
        if (q == null) {
            return true;
        }
        if (notBlank(q.keyword())) {
            String kw = q.keyword().strip().toLowerCase(java.util.Locale.ROOT);
            String haystack = ((row.title() == null ? "" : row.title()) + "\n"
                    + (row.description() == null ? "" : row.description())).toLowerCase(java.util.Locale.ROOT);
            if (!haystack.contains(kw)) {
                return false;
            }
        }
        if (notAll(q.sourceType()) && !q.sourceType().strip().equalsIgnoreCase(row.sourceType())) {
            return false;
        }
        if (notAll(q.status()) && !q.status().strip().equalsIgnoreCase(row.status())) {
            return false;
        }
        if (notAll(q.approvalMode()) && !q.approvalMode().strip().equalsIgnoreCase(row.approvalMode())) {
            return false;
        }
        if ("ON".equalsIgnoreCase(q.autoContinue()) && !row.autoContinue()) {
            return false;
        }
        if ("OFF".equalsIgnoreCase(q.autoContinue()) && row.autoContinue()) {
            return false;
        }
        if (q.minChapters() != null && row.chapterCount() < q.minChapters()) {
            return false;
        }
        if (q.maxChapters() != null && row.chapterCount() > q.maxChapters()) {
            return false;
        }
        java.time.LocalDate from = parseDate(q.from(), "起始日期");
        java.time.LocalDate to = parseDate(q.to(), "结束日期");
        java.time.LocalDate created = row.createTime() == null ? null
                : row.createTime().atZoneSameInstant(java.time.ZoneId.systemDefault()).toLocalDate();
        if (from != null && (created == null || created.isBefore(from))) {
            return false;
        }
        if (to != null && (created == null || created.isAfter(to))) {
            return false;
        }
        return true;
    }

    /**
     * 排序口径（纯函数）：**未指定 sort 时保持历史默认（按 id 升序）**——本端点同时给全站 7 处「作品下拉」供数，
     * 默认序不能随书籍管理页的偏好漂移；书籍管理页自己在筛选栏里显式传 TIME_DESC。
     */
    static java.util.Comparator<NovelVO> comparator(String sort) {
        java.util.Comparator<java.time.OffsetDateTime> timeAsc =
                java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder());
        java.util.Comparator<java.time.OffsetDateTime> timeDesc =
                java.util.Comparator.nullsLast(java.util.Comparator.reverseOrder());
        java.util.Comparator<Integer> chaptersDesc =
                java.util.Comparator.nullsLast(java.util.Comparator.reverseOrder());
        String key = sort == null ? "" : sort.strip().toUpperCase(java.util.Locale.ROOT);
        return switch (key) {
            case "TIME_ASC" -> java.util.Comparator.comparing(NovelVO::createTime, timeAsc);
            case "TIME_DESC" -> java.util.Comparator.comparing(NovelVO::createTime, timeDesc);
            case "CHAPTERS_DESC" -> java.util.Comparator.comparing(NovelVO::chapterCount, chaptersDesc);
            case "TITLE_ASC" -> java.util.Comparator.comparing(row -> row.title() == null ? "" : row.title());
            default -> java.util.Comparator.comparing(NovelVO::id);
        };
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static boolean notAll(String s) {
        return notBlank(s) && !"ALL".equalsIgnoreCase(s.strip());
    }

    private static java.time.LocalDate parseDate(String text, String label) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return java.time.LocalDate.parse(text.strip());
        } catch (Exception e) {
            throw new BizException(ErrorCode.PARAM_ERROR, label + "格式应为 yyyy-MM-dd：" + text);
        }
    }

    /** 草稿书完成激活（draft → active；条件更新防重复激活）。骨架大纲门禁：未改写的样本骨架直通下游会让卷规划/正文沿原书剧情生成。 */
    public void activate(long novelId) {
        requireNovel(novelId);
        String outline = canonData.findContentByKindName(novelId, "misc", "大纲");
        if (outline != null && outline.strip().startsWith(SKELETON_OUTLINE_MARKER)) {
            throw new BizException(ErrorCode.STATE_CONFLICT,
                    "大纲仍是样本剧情骨架（未改写）——先完成「AI 生成大纲」或在「规划」页改写大纲后再激活");
        }
        if (novelData.activate(novelId) == 0) {
            throw new BizException(ErrorCode.STATE_CONFLICT, "该书不是草稿状态（可能已激活）");
        }
    }

    /** 编辑书籍基本信息（改名全站唯一；不触碰风格包/衍生配置/生成数据）。 */
    public void updateProfile(long novelId, String title, String description) {
        requireNovel(novelId);
        if (title == null || title.isBlank()) {
            throw new BizException(ErrorCode.PARAM_ERROR, "书名必填");
        }
        String t = title.strip();
        if (t.length() > 256) {
            throw new BizException(ErrorCode.PARAM_ERROR, "书名过长（≤256 字）");
        }
        Long existing = novelData.findIdByTitle(t);
        if (existing != null && existing != novelId) {
            throw new BizException(ErrorCode.STATE_CONFLICT, "已有同名作品：" + t);
        }
        novelData.updateProfile(novelId, t, description == null ? "" : description.strip());
    }

    /**
     * 删除书籍（软删，可 psql 恢复）。守卫：有排队/运行中的生成任务拒绝（先停止再删）；
     * 无人续跑开着时先关闭（derive_config.autoContinue=false + 链置 OFF），防止删除后队列钩子继续排任务。
     */
    public void deleteNovel(long novelId) {
        requireNovel(novelId);
        if (taskData.existsActiveForNovel(novelId)) {
            throw new BizException(ErrorCode.STATE_CONFLICT,
                    "这本书还有排队/运行中的生成任务——先到工作台停止任务，再删除");
        }
        DeriveSupport.Cfg cfg = DeriveSupport.parse(novelData.findDeriveConfig(novelId));
        if (cfg.autoContinueOn()) {
            novelData.updateDeriveConfig(novelId, deriveConfigJson(
                    new NovelCreateDTO.DeriveConfigVO(cfg.water(), cfg.pov(), cfg.povCharacter(), cfg.pacingNote(),
                            cfg.chaptersPerVolume(), cfg.targetChapters(), false, cfg.priority(), cfg.tags()), novelId));
        }
        novelData.updateAutoState(novelId, "OFF", "书籍已删除，无人续跑已关闭");
        novelData.softDelete(novelId);
        // 专属风格包一并软删：否则「书名·风格」这个活名被残留包占着，同名书再开/再导必然撞
        // uq_style_packs_name_alive（共享包与预设不动——SQL 里已判活书引用与 is_preset）。
        int freedPacks = stylePackData.softDeleteOrphanOfNovel(novelId);
        log.info("书籍软删 novelId={} 专属风格包一并软删={}", novelId, freedPacks > 0);
    }

    /**
     * 取本书的私有风格包（开书/导入共用）。活名唯一约束 uq_style_packs_name_alive 不认历史残留，
     * 所以顺序为：①同名可复用包（**活着但无活书引用的孤儿包**）原地改写复用 → ②活名空缺则新建 →
     * ③活名被在用的包/预设占着才退让改名（书名·风格·2…）。任何一步都不该再抛数据库唯一键异常。
     * 2026-10-03 起「软删行一律不可见」，故不再回收已软删的包：同名包被删后重导走 ② 新建一行
     * （软删行不占活名，条件唯一索引放行），代价是留一行历史数据。
     */
    private long acquireStylePack(String title, String description, String rulesMd, String fingerprint,
                                 String gateConfig) {
        String base = title + "·风格";
        String name = base;
        for (int i = 1; i <= 50; i++) {
            Long reusable = stylePackData.findReusablePackId(name);
            if (reusable != null) {
                stylePackData.reusePack(reusable, name, description, rulesMd, fingerprint, gateConfig);
                log.info("风格包复用：name={} packId={}（无活书引用的孤儿包，原地改写复用）", name, reusable);
                return reusable;
            }
            if (stylePackData.findIdByName(name) == null) {
                return stylePackData.insertPack(name, description, rulesMd, fingerprint, gateConfig);
            }
            name = base + "·" + (i + 1);
        }
        throw new BizException(ErrorCode.STATE_CONFLICT, "同名风格包过多（" + base + " 已排到 ·51），换个书名再试");
    }

    public void setApprovalMode(long novelId, String mode) {
        if (!PlanMode.AUTO.is(mode) && !PlanMode.MANUAL.is(mode)) {
            throw new BizException(ErrorCode.PARAM_ERROR, "审批模式只支持 auto / manual");
        }
        novelData.updateApprovalMode(novelId, mode);
    }

    /**
     * 开书：克隆所选品类预设（指纹/门禁/规则）为本书私有风格包并挂书；
     * P2 起可选克隆导入样本资产（★2+ 素材卡/世界观/剧情骨架预填大纲）与衍生配置
     * （掺水量换算进本书 gate_config；POV/节奏进提示词；无人续跑强制 auto 双模式）。
     */
    @Transactional
    public NovelVO create(NovelCreateDTO vo, long userId) {
        String title = requireTitle(vo.title());
        StylePackDO preset = requirePreset(vo.presetId());
        String gateConfig = stylePackData.findGateConfigById(preset.getId());
        NovelCreateDTO.DeriveConfigVO derive = vo.deriveConfig();
        if (derive != null && derive.water() != null) {
            gateConfig = DeriveSupport.applyWaterGates(gateConfig, derive.water());
        }
        long packId = acquireStylePack(title, "开书克隆自预设：" + preset.getName(),
                preset.getRulesMd() == null ? "" : preset.getRulesMd(),
                preset.getFingerprint(), gateConfig);
        String bookStatus = Boolean.TRUE.equals(vo.draft()) ? "draft" : "active";
        long novelId = novelData.insert(userId, title, vo.description() == null ? "" : vo.description(), packId, "auto",
                bookStatus, com.zzdzz.novelgen.model.enums.NovelSourceType.ofSample(vo.sampleId()));
        if (derive != null) {
            novelData.updateDeriveConfig(novelId, deriveConfigJson(derive, vo.sampleId()));
            if (derive.autoContinue() != null && derive.autoContinue()) {
                novelData.updatePlanMode(novelId, PlanMode.AUTO.wire());
            }
        }
        if (vo.sampleId() != null) {
            cloneSampleAssets(novelId, vo.sampleId(), vo.cloneAssets());
        }
        NovelDO n = novelData.getById(novelId);
        return new NovelVO(n.getId(), n.getTitle(), n.getDescription(), n.getApprovalMode(), n.getStatus(),
                NovelSourceType.normalize(n.getSourceType()), 0,
                n.getCreateTime(), derive != null && derive.autoContinue() != null && derive.autoContinue(),
                derive == null ? null : derive.targetChapters());
    }

    // ===== 导入书籍（书籍管理页）：既有正文（粘贴 / txt / mobi）切章入库 =====

    /** 导入正文上限：与样本导入同一量级（超出请分段导入）。电子书字节上限见 GenrePresetService.MAX_EBOOK_BYTES。 */
    private static final int IMPORT_MAX_CHARS = 8_000_000;

    /**
     * 导入正文自成一卷（第 1 卷，arc 固定为「导入正文」）：导入的书**正文就是第一卷**，
     * 后续卷规划于是从第 2 卷起接在正文之后——否则导入章永远是「未分卷」，首次卷规划会把这卷编成
     * 「第 1 卷」（卷号对不上正文），规划页还会出现「第 0 卷 · 未分卷」这个伪分组。
     */
    static final int IMPORT_VOLUME_NO = 1;
    static final String IMPORT_VOLUME_ARC = "导入正文";

    /** 「第N章」章标题（阿拉伯数字）：与 ImportRunner 的章文件命名口径一致。 */
    private static final java.util.regex.Pattern CHAPTER_HEADING =
            java.util.regex.Pattern.compile("^\\s*第\\s*(\\d{1,4})\\s*章\\s*(.*)$");

    /** 「第X章」章标题（中文数字：第一章 / 第十一章）。 */
    private static final java.util.regex.Pattern CHAPTER_HEADING_CN =
            java.util.regex.Pattern.compile("^\\s*第\\s*([一二三四五六七八九十百零两]{1,6})\\s*章\\s*(.*)$");

    /** 「X、标题」式分节（中文数字 + 顿号/点/冒号：一、登船 / 十二、漂流）。 */
    private static final java.util.regex.Pattern SECTION_HEADING_CN =
            java.util.regex.Pattern.compile("^\\s*([一二三四五六七八九十百零两]{1,4})\\s*[、.．，,:：]\\s*(.*)$");

    /**
     * 中文数字标题样式的整行长度上限：标题行短、正文行长的经验闸。
     * 没有它，「一、他想起那件事的时候正在下雨……」这类正文行会被当成章标题把书切碎。
     * 阿拉伯数字的「第N章」不设此限——那是已在跑的口径，不动。
     */
    private static final int CN_HEADING_MAX_CHARS = 30;

    /** 切章结果（no=原章号或重排后章号；title 不含「第N章」前缀）。 */
    record ChapterSlice(int no, String title, String content) {
    }

    /** 识别出的章标题行（no=解析出的章号；title=去掉编号后的标题，空则回退「第N章」）。 */
    record Heading(int no, String title) {
    }

    /** 导入结果：章数、是否重排过章号、是否需前端接着走提指纹流程，以及需要用户知道的口径提示。 */
    public record NovelImportResultVO(long novelId, String title, int chapterCount, boolean renumbered,
                                      boolean pendingFingerprint, List<String> notes) {
    }

    /** 补事实账结果（逐章成败，失败不抛断整体）。 */
    /** skipped = 本次因「已有事实账且选了跳过」而未重算的章数（force 模式下恒为 0）。 */
    public record DigestBackfillVO(int requested, int digested, int skipped, List<String> notes) {
    }

    /**
     * 导入书籍：正文切章 → 落 chapters（status=FINAL，不经生成管线）→ 书行标 IMPORTED。
     * 事务只包短写（建风格包 + 建书 + 落章）；事实账（LLM）走 {@link #backfillDigests} 由前端二次调用——
     * 事务内不得有 LLM 调用（§6），也让用户在导入结果可见后再决定要不要花钱补前情。
     */
    @Transactional
    public NovelImportResultVO importBook(NovelImportVO vo, long userId) {
        String title = requireTitle(vo.title());
        // 预设可选：选了就克隆其口径（指纹+门禁+规则）；没选建空风格包，指纹稍后由前端按本书正文提回填。
        StylePackDO preset = vo.presetId() == null ? null : requirePreset(vo.presetId());
        String text = importText(vo.text(), vo.fileBase64());
        List<ChapterSlice> slices = new ArrayList<>(splitChapters(text));
        List<String> notes = new ArrayList<>();
        if (slices.size() == 1) {
            notes.add("未识别到章标题——整篇已作为第 1 章入库（支持的标题行：第N章 / 第一章 / 一、标题）");
        }
        boolean renumbered = renumber(slices);
        if (renumbered) {
            notes.add("原章号不连续（或未从 1 开始）——已按出现顺序重排为 1.." + slices.size()
                    + "，方便卷规划从这里往后接续");
        }
        String gateConfig = preset == null ? null : stylePackData.findGateConfigById(preset.getId());
        double[] band = budgetBand(gateConfig);
        // 未选预设时建空风格包：指纹/门禁传 null（列可空；空串转 jsonb 会报错），GateService 无配置时回退
        // tuning/代码默认值，不会跑挂。导入后立刻按本书正文提指纹回填——见 pendingFingerprint。
        long packId = acquireStylePack(title,
                preset == null ? "导入书籍：待按本书正文提指纹" : "导入书籍克隆自预设：" + preset.getName(),
                preset == null || preset.getRulesMd() == null ? "" : preset.getRulesMd(),
                preset == null ? null : preset.getFingerprint(),
                gateConfig);
        long novelId = novelData.insert(userId, title, vo.description() == null ? "" : vo.description().strip(),
                packId, "auto", "active", NovelSourceType.IMPORTED.wire());
        for (ChapterSlice s : slices) {
            // 导入正文即第 1 卷（见 IMPORT_VOLUME_NO）：续写卷规划从第 2 卷起，规划页不再出现「未分卷」伪分组
            chapterData.insertPlan(novelId, s.no(), IMPORT_VOLUME_NO, IMPORT_VOLUME_ARC, s.title(), null, null, null,
                    "[]", "[]", band == null ? 0 : (int) band[0], band == null ? 0 : (int) band[1]);
            ChapterDO chapter = chapterData.find(novelId, s.no())
                    .orElseThrow(() -> new IllegalStateException("导入落章失败：novelId=" + novelId + " no=" + s.no()));
            chapterData.saveFullText(chapter.getId(), s.content());
            chapterData.updateStatus(chapter.getId(), ChapterStatus.FINAL.wire());
        }
        if (preset == null) {
            notes.add("未选文风预设——已按本书正文自动进入提指纹流程（草稿需你确认采纳后才会写入门禁阈值）");
        }
        log.info("书籍导入：novelId={} title={} 章数={} 重排={} 预设={}", novelId, title, slices.size(), renumbered,
                preset == null ? "（未选，待提指纹）" : preset.getName());
        return new NovelImportResultVO(novelId, title, slices.size(), renumbered, preset == null, notes);
    }

    /**
     * 为最新章节补 AI 事实账（导入正文后的续写前情来源）：逐章调用，单章失败只记 note 不中断。
     * 事务外执行（LLM 调用不得进事务）；已导入的章状态保持 FINAL 不动——事实账是补充记忆，不改变章状态。
     */
    public DigestBackfillVO backfillDigests(long novelId, int recent) {
        return backfillDigests(novelId, recent, false);
    }

    /**
     * force=false（默认）：已有事实账的章跳过（省 token 的幂等口径）。
     * force=true（用户选「覆盖已有」）：这些章重算并原地更新事实账 —— 解析链里「跳过/覆盖」开关的落点。
     */
    public DigestBackfillVO backfillDigests(long novelId, int recent, boolean force) {
        requireNovel(novelId);
        if (recent <= 0) {
            return new DigestBackfillVO(0, 0, 0, new ArrayList<>());
        }
        int want = Math.min(recent, 20);
        List<ChapterDO> chapters = new ArrayList<>(chapterData.listSummariesByNovel(novelId));
        List<String> notes = new ArrayList<>();
        if (chapters.isEmpty()) {
            return new DigestBackfillVO(0, 0, 0, List.of("本书还没有章节，无需补事实账"));
        }
        int digested = 0;
        int skipped = 0;
        int attempted = 0;
        for (int i = chapters.size() - 1; i >= 0 && attempted < want; i--) {
            ChapterDO summary = chapters.get(i);
            ChapterDO full = chapterData.find(novelId, summary.getChapterNo()).orElse(null);
            if (full == null || full.getFullText() == null || full.getFullText().isBlank()) {
                continue;
            }
            attempted++;
            try {
                if (digestService.digest(novelId, full.getId(), full.getChapterNo(), full.getFullText(), force)) {
                    digested++;
                } else {
                    skipped++;
                }
            } catch (Exception e) {
                notes.add("第 " + full.getChapterNo() + " 章事实账生成失败：" + (e.getMessage() == null ? "未知错误" : e.getMessage()));
                log.warn("导入后补事实账失败：novelId={} 章={} {}", novelId, full.getChapterNo(), e.getMessage());
            }
        }
        if (digested < attempted) {
            notes.add("已成功 " + digested + "/" + attempted + " 章"
                    + (skipped > 0 ? "（" + skipped + " 章已有事实账，本次选了跳过）" : "")
                    + "；失败章可在章节页重新审批或稍后重试补账");
        }
        log.info("导入后补事实账：novelId={} 成功 {}/{}（跳过已有 {}）覆盖={}", novelId, digested, attempted, skipped, force);
        return new DigestBackfillVO(attempted, digested, skipped, notes);
    }

    /** 导入正文取值：粘贴文本优先，其次电子书 base64（共用 MobiExtractor 的 data URL 解码与体积守卫）。 */
    private String importText(String text, String fileBase64) {
        String body = text;
        if (body == null || body.isBlank()) {
            body = com.zzdzz.novelgen.common.util.DocumentTextExtractor
                    .extractFromBase64(fileBase64, GenrePresetService.MAX_EBOOK_BYTES);
        }
        if (body == null || body.isBlank()) {
            throw new BizException(ErrorCode.PARAM_ERROR, "请粘贴正文或上传 txt/docx/mobi/azw 文件");
        }
        if (body.length() > IMPORT_MAX_CHARS) {
            throw new BizException(ErrorCode.PARAM_ERROR,
                    "正文超长（" + (body.length() / 10000) + " 万字 > 上限 800 万），请分段导入");
        }
        return body;
    }

    /**
     * 按章标题行切章（纯函数，可单测）：标题行本身不计入正文；首章前的残余文字并入第 1 章；
     * 完全没有标题行时整篇作为第 1 章。章标题取标题行去掉编号后的剩余文字，为空则用「第N章」。
     */
    static List<ChapterSlice> splitChapters(String text) {
        String[] lines = text.split("\\r?\\n", -1);
        List<int[]> heads = new ArrayList<>();
        List<String> headTitles = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            Heading heading = parseHeading(lines[i]);
            if (heading != null) {
                heads.add(new int[]{i, heading.no()});
                headTitles.add(heading.title());
            }
        }
        if (heads.isEmpty()) {
            return List.of(new ChapterSlice(1, "第1章", text.strip()));
        }
        String preamble = String.join("\n", java.util.Arrays.copyOfRange(lines, 0, heads.get(0)[0])).strip();
        List<ChapterSlice> out = new ArrayList<>();
        for (int k = 0; k < heads.size(); k++) {
            int start = heads.get(k)[0];
            int end = k + 1 < heads.size() ? heads.get(k + 1)[0] : lines.length;
            String body = String.join("\n", java.util.Arrays.copyOfRange(lines, start + 1, end)).strip();
            if (k == 0 && !preamble.isEmpty()) {
                body = preamble + "\n\n" + body;
            }
            int no = heads.get(k)[1];
            String heading = headTitles.get(k);
            out.add(new ChapterSlice(no, heading.isEmpty() ? "第" + no + "章" : heading, body));
        }
        return out;
    }

    /**
     * 章标题行识别（纯函数，null=不是标题）。三种样式：
     * ①「第N章」阿拉伯数字（既有口径）；②「第X章」中文数字（第一章）；③「X、标题」中文数字 + 顿号/点/冒号（一、登船）。
     * ②③ 受 {@link #CN_HEADING_MAX_CHARS} 长度闸保护，避免把「一、他想起……」这类正文行误判成章标题。
     */
    static Heading parseHeading(String line) {
        if (line == null || line.isBlank()) {
            return null;
        }
        String s = line.strip();
        java.util.regex.Matcher m = CHAPTER_HEADING.matcher(s);
        if (m.matches()) {
            return new Heading(Integer.parseInt(m.group(1)), group2(m));
        }
        if (s.length() > CN_HEADING_MAX_CHARS) {
            return null;
        }
        m = CHAPTER_HEADING_CN.matcher(s);
        if (m.matches()) {
            return new Heading(chineseToInt(m.group(1)), group2(m));
        }
        m = SECTION_HEADING_CN.matcher(s);
        if (m.matches()) {
            return new Heading(chineseToInt(m.group(1)), group2(m));
        }
        return null;
    }

    private static String group2(java.util.regex.Matcher m) {
        return m.group(2) == null ? "" : m.group(2).strip();
    }

    /** 中文数字转整数（一→1、十→10、十一→11、二十一→21、一百→100）；识别不出的回 0，由章号重排按顺序兜底。 */
    static int chineseToInt(String cn) {
        java.util.Map<Character, Integer> digits = java.util.Map.ofEntries(
                java.util.Map.entry('零', 0), java.util.Map.entry('一', 1), java.util.Map.entry('二', 2),
                java.util.Map.entry('两', 2), java.util.Map.entry('三', 3), java.util.Map.entry('四', 4),
                java.util.Map.entry('五', 5), java.util.Map.entry('六', 6), java.util.Map.entry('七', 7),
                java.util.Map.entry('八', 8), java.util.Map.entry('九', 9));
        int section = 0;
        int number = 0;
        for (char c : cn.toCharArray()) {
            Integer d = digits.get(c);
            if (d != null) {
                number = d;
            } else if (c == '十') {
                section += (number == 0 ? 1 : number) * 10;
                number = 0;
            } else if (c == '百') {
                section += (number == 0 ? 1 : number) * 100;
                number = 0;
            }
        }
        return section + number;
    }

    /** 章号重排（原地，纯函数）：已是 1..N 则不动返回 false；否则按顺序重排（无标题的「第N章」标题一并改写）。 */
    static boolean renumber(List<ChapterSlice> slices) {
        boolean already = true;
        for (int i = 0; i < slices.size(); i++) {
            if (slices.get(i).no() != i + 1) {
                already = false;
                break;
            }
        }
        if (already) {
            return false;
        }
        for (int i = 0; i < slices.size(); i++) {
            ChapterSlice s = slices.get(i);
            int next = i + 1;
            String title = ("第" + s.no() + "章").equals(s.title()) ? "第" + next + "章" : s.title();
            slices.set(i, new ChapterSlice(next, title, s.content()));
        }
        return true;
    }

    /** 风格包 gate_config 的章长预算带（无配置/坏 JSON 返回 null；导入章的预算与后续生成同一口径）。 */
    private double[] budgetBand(String gateConfigJson) {
        if (gateConfigJson == null || gateConfigJson.isBlank()) {
            return null;
        }
        try {
            JsonNode cfg = mapper.readTree(gateConfigJson);
            return cfg.path("budget_min").isNumber() && cfg.path("budget_max").isNumber()
                    ? new double[]{cfg.path("budget_min").asDouble(), cfg.path("budget_max").asDouble()}
                    : null;
        } catch (Exception e) {
            log.warn("门禁配置解析失败，导入章预算按 0 落库：{}", e.getMessage());
            return null;
        }
    }

    /** 品类预设校验（开书与导入共用）：必须存在且 is_preset。 */
    private StylePackDO requirePreset(Long presetId) {
        if (presetId == null) {
            throw new BizException(ErrorCode.PARAM_ERROR,
                    "请选择品类预设（没有可用预设时，先到素材库·质量与风格·品类预设提取一个）");
        }
        StylePackDO preset = stylePackData.getById(presetId);
        if (preset == null || !preset.isPreset()) {
            throw new BizException(ErrorCode.PARAM_ERROR, "所选预设不存在: " + presetId);
        }
        return preset;
    }

    /** 样本资产克隆：素材卡（★2+，★3 置常驻）/世界观文档/剧情骨架预填大纲（标注待改写）。 */
    private void cloneSampleAssets(long novelId, long sampleId, NovelCreateDTO.CloneAssetsVO flags) {
        ImportedSampleDO sample = sampleData.getById(sampleId);
        if (sample == null) {
            throw new BizException(ErrorCode.PARAM_ERROR, "所选导入样本不存在: " + sampleId);
        }
        boolean cloneCards = flags == null || flags.cards() == null || flags.cards();
        boolean cloneWorld = flags == null || flags.world() == null || flags.world();
        boolean cloneOutline = flags == null || flags.plotOutline() == null || flags.plotOutline();
        if (cloneCards) {
            int count = 0;
            for (SampleCardDO c : sampleCardData.listBySample(sampleId)) {
                if (c.getKind().equals("world") || (c.getImportance() == null || c.getImportance() < 2)) {
                    continue;
                }
                // 衍生书克隆语义（书 10 实证教训）：
                // ① 人物卡（character）不克隆——衍生新书的主角团必须原创，克隆原书主角团会让卷规划
                //    顺着原书人生轨迹排章（衍生变复述）；原书人物应由用户按需在素材库手动补；
                // ② 设定类卡（地点/物品/组织/现象等）照常克隆——这是"沿用样本世界观"的部分；
                // ③ cloned 卡一律不 pinned（常驻注入会持续把原书设定压进每章上下文）。
                if (c.getKind().equals("character")) {
                    continue;
                }
                cardData.insert(novelId, c.getKind(), c.getName(), parseAliases(c.getAliases()),
                        c.getSummary(), c.getContentMd(), false,
                        "active", c.getFirstSeq());
                count++;
            }
            log.info("样本资产克隆：sampleId={} → novelId={} 设定卡 {} 张（★2+，人物卡不克隆）", sampleId, novelId, count);
        }
        if (cloneWorld) {
            for (SampleCardDO c : sampleCardData.listBySample(sampleId)) {
                if (c.getKind().equals("world") && c.getContentMd() != null && !c.getContentMd().isBlank()) {
                    canonData.insert(novelId, "world", "世界观", stripFastNote(c.getContentMd()));
                    break;
                }
            }
        }
        if (cloneOutline) {
            for (SamplePlotNodeDO n : plotData.listBySample(sampleId)) {
                if (n.getLevel().equals("book") && n.getSummary() != null && !n.getSummary().isBlank()) {
                    canonData.insert(novelId, "misc", "大纲",
                            "> 由样本《" + sample.getTitle() + "》剧情骨架生成，供参考改写——确认人设与主线后再交给卷规划。\n\n"
                                    + n.getSummary());
                    break;
                }
            }
        }
    }

    /** derive_config JSON 组装（空值字段不落，读侧 fail-open 给默认）。 */
    private String deriveConfigJson(NovelCreateDTO.DeriveConfigVO d, Long sampleId) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (d.water() != null) {
            m.put("water", Math.max(0, Math.min(100, d.water())));
        }
        if (d.pov() != null && !d.pov().isBlank()) {
            m.put("pov", d.pov().strip());
        }
        if (d.povCharacter() != null && !d.povCharacter().isBlank()) {
            m.put("povCharacter", d.povCharacter().strip());
        }
        if (d.pacingNote() != null && !d.pacingNote().isBlank()) {
            m.put("pacingNote", d.pacingNote().strip());
        }
        if (d.chaptersPerVolume() != null) {
            m.put("chaptersPerVolume", Math.max(3, Math.min(30, d.chaptersPerVolume())));
        }
        if (d.targetChapters() != null && d.targetChapters() > 0) {
            m.put("targetChapters", d.targetChapters());
        }
        if (d.autoContinue() != null) {
            m.put("autoContinue", d.autoContinue());
        }
        if (d.priority() != null) {
            m.put("priority", Math.max(0, Math.min(2, d.priority())));
        }
        if (sampleId != null) {
            m.put("sourceSampleId", sampleId);
        }
        if (d.tags() != null && !d.tags().isEmpty()) {
            java.util.List<String> tags = d.tags().stream()
                    .map(t -> t == null ? "" : t.strip())
                    .filter(t -> !t.isEmpty())
                    .map(t -> t.length() > 12 ? t.substring(0, 12) : t)
                    .distinct()
                    .limit(20)
                    .toList();
            if (!tags.isEmpty()) {
                m.put("tags", tags);
            }
        }
        try {
            return mapper.writeValueAsString(m);
        } catch (Exception e) {
            throw new IllegalStateException("衍生配置序列化失败", e);
        }
    }

    /** 衍生配置全量读（书全生命周期可改参的回显口；无配置返回默认值对象）。 */
    public DeriveConfigFullVO deriveConfig(long novelId) {
        requireNovel(novelId);
        DeriveSupport.Cfg c = DeriveSupport.parse(novelData.findDeriveConfig(novelId));
        return new DeriveConfigFullVO(c.water(), c.pov(), c.povCharacter(), c.pacingNote(),
                c.chaptersPerVolume(), c.targetChapters(), c.autoContinue(), c.priority(),
                c.sourceSampleId(), c.tags());
    }

    /** 衍生配置编辑（开书后改目标章数/掺水量/POV/每卷章数/标签/无人续跑/优先级；老书由此启用无人续跑）。
     * sourceSampleId 保留原值；开无人续跑同时强制规划模式 auto（与开书口径一致）。
     * 掺水量变更时注水/审校三阈值按新水重算写入本书 gate_config——与开书同一换算（单一口径），
     * 其余门禁键（恢复线/扩写护栏等）不动。 */
    public DeriveConfigFullVO updateDeriveConfig(long novelId, NovelCreateDTO.DeriveConfigVO d) {
        requireNovel(novelId);
        DeriveSupport.Cfg old = DeriveSupport.parse(novelData.findDeriveConfig(novelId));
        novelData.updateDeriveConfig(novelId, deriveConfigJson(d, old.sourceSampleId()));
        if (d.autoContinue() != null && d.autoContinue()) {
            novelData.updatePlanMode(novelId, PlanMode.AUTO.wire());
        }
        if (d.water() != null && !java.util.Objects.equals(old.water(), d.water())) {
            stylePackData.updateGateConfigByNovel(novelId,
                    DeriveSupport.applyWaterGates(stylePackData.findGateConfigByNovel(novelId), d.water()));
        }
        return deriveConfig(novelId);
    }

    private void requireNovel(long novelId) {
        if (novelData.getById(novelId) == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "作品不存在: " + novelId);
        }
    }

    /** 衍生配置全量（含 sourceSampleId 回显与类型标签）。 */
    public record DeriveConfigFullVO(Integer water, String pov, String povCharacter, String pacingNote,
                                     Integer chaptersPerVolume, Integer targetChapters, Boolean autoContinue,
                                     Integer priority, Long sourceSampleId, java.util.List<String> tags) {
    }

    /**
     * 开书向导「AI 生成大纲」：基本信息 + 衍生设定（含样本骨架与标签）→ 大纲草稿 markdown。
     * 纯生成不落库（llm_call_log 照常记账）；样本只借结构与节奏气质，提示词明令禁止搬运专有设定。
     */
    public String draftOutline(NovelCreateDTO vo) {
        // 只做非空校验，不做全站查重——草稿流程里书已落库，worker 重放会撞自己的名字
        if (vo.title() == null || vo.title().isBlank()) {
            throw new BizException(ErrorCode.PARAM_ERROR, "书名必填");
        }
        String title = vo.title().strip();
        NovelCreateDTO.DeriveConfigVO d = vo.deriveConfig();
        StylePackDO preset = vo.presetId() == null ? null : stylePackData.getById(vo.presetId());
        if (preset == null || !preset.isPreset()) {
            throw new BizException(ErrorCode.PARAM_ERROR, "请先在第一步选择品类预设（文风语境）");
        }
        // 样本仅贡献：类型标签回填 + （勾选预填时）canon 骨架。AI 生成本身不喂样本剧情——
        // 实训教训：剧情骨架进生成材料，模型会逐桥段复刻原书（换名不换故事），衍生变抄袭。
        String skeleton = "";
        java.util.List<String> tags = d != null && d.tags() != null ? d.tags() : new java.util.ArrayList<>();
        if (vo.sampleId() != null) {
            ImportedSampleDO sample = sampleData.getById(vo.sampleId());
            if (sample != null) {
                if (tags.isEmpty()) {
                    try {
                        com.fasterxml.jackson.databind.JsonNode t = mapper.readTree(sample.getTags() == null ? "[]" : sample.getTags());
                        t.forEach(x -> tags.add(x.asText()));
                    } catch (Exception ignored) {
                        // 样本无标签/坏 JSON 时按空处理
                    }
                }
            }
        }
        int chaptersPerVolume = d != null && d.chaptersPerVolume() != null ? d.chaptersPerVolume() : 10;
        int targetChapters = d != null && d.targetChapters() != null ? d.targetChapters() : 300;
        int volumes = Math.max(1, (int) Math.ceil((double) targetChapters / Math.max(chaptersPerVolume, 1)));
        // 世界约束由**用户勾选驱动**（cloneAssets.world）：勾选=新故事发生在样本世界内（同世界衍生）；
        // 不勾=AI 完全自由创作（新世界）。选择权在前端按钮，后端不硬编码。
        // 提示词文案在 PromptCatalog（node=derive_outline/phase=world），业务代码只组装参数。
        boolean worldCloned = vo.cloneAssets() != null && Boolean.TRUE.equals(vo.cloneAssets().world());
        String worldConstraint = "";
        if (worldCloned && vo.novelId() != null) {
            String worldDoc = "";
            for (CanonDocDO doc : canonData.listByNovel(vo.novelId())) {
                if (doc.getKind().equals("world") && doc.getContent() != null && !doc.getContent().isBlank()) {
                    worldDoc = truncate(doc.getContent(), 2200);
                    break;
                }
            }
            if (!worldDoc.isEmpty()) {
                worldConstraint = promptTemplates.format(LlmNode.DERIVE_OUTLINE, "world", worldDoc);
            }
        }
        String user = promptTemplates.format(LlmNode.DERIVE_OUTLINE, "user", volumes, title,
                vo.description() == null || vo.description().isBlank() ? "（无）" : vo.description().strip(),
                preset.getName() + (preset.getDescription() == null ? "" : "——" + preset.getDescription()),
                tags.isEmpty() ? "（未设，按文风预设与简介自定）" : String.join("、", tags),
                d == null || d.pov() == null ? "第三人称限知" : d.pov() + (d.povCharacter() == null || d.povCharacter().isBlank() ? "" : "（主视角：" + d.povCharacter() + "）"),
                d == null || d.pacingNote() == null || d.pacingNote().isBlank()
                        ? DeriveSupport.densityHint(d == null ? null : d.water())
                        : d.pacingNote().strip(),
                chaptersPerVolume, volumes);
        LlmPort.ChatRequest req = new LlmPort.ChatRequest(LlmNode.DERIVE_OUTLINE, null, null,
                List.of(LlmPort.Message.system(promptTemplates.get(LlmNode.DERIVE_OUTLINE, "system")),
                        LlmPort.Message.user(user)), LlmTemps.DERIVE_OUTLINE);
        LlmPort.ChatResult r = llm.chat(req);
        String outline = r.content() == null ? "" : r.content().strip();
        if (outline.isEmpty()) {
            throw new BizException(ErrorCode.LLM_OUTPUT_INVALID, "大纲生成为空（llm_call_log node=derive_outline 可回放），请重试");
        }
        if (vo.sampleId() != null) {
            outline = ensureOriginality(vo, outline);
        }
        return outline;
    }

    // ===== 衍生大纲原创性把关（书 9/10/11 悉达多换名复刻实证）=====

    /** 复刻判定后自动重写轮数上限（初始生成 + ≤N 轮重写，轮满仍复刻即失败）。 */
    private static final int ORIGINALITY_REWRITE_ROUNDS = 2;
    /** cloneSampleAssets 预填骨架大纲的固定头（activate 门禁据此识别未改写骨架）。 */
    private static final String SKELETON_OUTLINE_MARKER = "> 由样本《";

    record OriginalityVerdict(boolean copy, List<String> reasons) {}

    /**
     * 大纲复刻把关：对照样本书级骨架与原书人物名（★2+）评审，判复刻→带原因重写≤N轮→仍复刻抛错（任务 FAILED）。
     * 评审调用本身故障 fail-open 放行（与卷规划审校同口径）但必留 warn——判定结果绝不静默丢弃。
     */
    private String ensureOriginality(NovelCreateDTO vo, String outline) {
        String skeleton = sampleBookSkeleton(vo.sampleId());
        if (skeleton.isBlank()) {
            return outline; // 样本还没深度解析出书级骨架——没有可比对象，放行
        }
        List<String> names = sampleCharacterNames(vo.sampleId());
        String reasons = null;
        for (int attempt = 0; attempt <= ORIGINALITY_REWRITE_ROUNDS; attempt++) {
            if (attempt > 0) {
                outline = rewriteOutline(vo, outline, reasons);
            }
            OriginalityVerdict v = judgeOriginality(vo, skeleton, names, outline);
            if (v == null) {
                return outline; // 评审没跑成（基础设施故障），judge 内已留痕
            }
            if (!v.copy()) {
                return outline;
            }
            reasons = v.reasons().isEmpty() ? "（评审未给出具体依据）" : String.join("；", v.reasons());
            log.warn("衍生大纲复刻判定成立（重写前第 {}/{} 轮）：{}", attempt, ORIGINALITY_REWRITE_ROUNDS, reasons);
        }
        throw new BizException(ErrorCode.LLM_OUTPUT_INVALID,
                "衍生大纲与原书复刻度过高：自动重写 " + ORIGINALITY_REWRITE_ROUNDS + " 轮仍未通过原创性审校（末次依据："
                        + reasons + "）。建议：换掉书名/简介里的原书元素，或取消勾选「世界观」克隆后重试"
                        + "（llm_call_log node=derive_originality 可回放）");
    }

    /** 复刻评审（derive_originality）；调用/解析失败返回 null（fail-open 放行，warn 留痕）。 */
    private OriginalityVerdict judgeOriginality(NovelCreateDTO vo, String skeleton, List<String> names, String outline) {
        try {
            String user = promptTemplates.format(LlmNode.DERIVE_ORIGINALITY, "user",
                    skeleton, names.isEmpty() ? "（样本未解析出 ★2+ 人物卡）" : String.join("、", names),
                    truncate(outline, 3200));
            return llmJson.ask(new LlmPort.ChatRequest(LlmNode.DERIVE_ORIGINALITY, vo.novelId(), null,
                            List.of(LlmPort.Message.system(promptTemplates.get(LlmNode.DERIVE_ORIGINALITY, "system")),
                                    LlmPort.Message.user(user)),
                            LlmTemps.DERIVE_ORIGINALITY),
                    node -> new OriginalityVerdict(node.path("copy").asBoolean(false),
                            toStringList(node.path("reasons"))),
                    2);
        } catch (Exception e) {
            log.warn("衍生大纲复刻评审调用失败，本轮放行（评审没跑成≠判定通过）：{}", e.getMessage());
            return null;
        }
    }

    /** 复刻重写：derive_outline/rewrite 段 + 原 system，产出替换稿。 */
    private String rewriteOutline(NovelCreateDTO vo, String outline, String reasons) {
        String user = promptTemplates.format(LlmNode.DERIVE_OUTLINE, "rewrite", reasons, truncate(outline, 3200));
        LlmPort.ChatResult r = llm.chat(new LlmPort.ChatRequest(LlmNode.DERIVE_OUTLINE, vo.novelId(), null,
                List.of(LlmPort.Message.system(promptTemplates.get(LlmNode.DERIVE_OUTLINE, "system")),
                        LlmPort.Message.user(user)),
                LlmTemps.DERIVE_OUTLINE));
        String next = r.content() == null ? "" : r.content().strip();
        if (next.isEmpty()) {
            throw new BizException(ErrorCode.LLM_OUTPUT_INVALID, "复刻重写输出为空（llm_call_log node=derive_outline 可回放），请重试");
        }
        return next;
    }

    /** 样本书级剧情骨架（深度解析产物；未解析返回空串）。 */
    private String sampleBookSkeleton(long sampleId) {
        for (SamplePlotNodeDO n : plotData.listBySample(sampleId)) {
            if (n.getLevel().equals("book") && n.getSummary() != null && !n.getSummary().isBlank()) {
                return truncate(n.getSummary(), 1200);
            }
        }
        return "";
    }

    /** 原书主要人物名（★2+ 人物卡，去重，上限 20）。 */
    private List<String> sampleCharacterNames(long sampleId) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        for (SampleCardDO c : sampleCardData.listBySample(sampleId)) {
            if (c.getKind().equals("character") && c.getImportance() != null && c.getImportance() >= 2
                    && c.getName() != null && !c.getName().isBlank()) {
                names.add(c.getName().strip());
                if (names.size() >= 20) {
                    break;
                }
            }
        }
        return new ArrayList<>(names);
    }

    private List<String> toStringList(JsonNode arr) {
        List<String> out = new ArrayList<>();
        if (arr != null && arr.isArray()) {
            for (JsonNode n : arr) {
                String t = n.asText("").strip();
                if (!t.isBlank()) {
                    out.add(t);
                }
            }
        }
        return out;
    }

    private static String truncate(String s, int max) {
        return s == null ? "" : (s.length() <= max ? s : s.substring(0, max));
    }

    private List<String> parseAliases(String aliasesJson) {
        List<String> out = new ArrayList<>();
        try {
            JsonNode n = mapper.readTree(aliasesJson == null ? "[]" : aliasesJson);
            n.forEach(a -> out.add(a.asText()));
        } catch (Exception e) {
            log.warn("样本卡别名解析失败，按空别名处理：{}", e.getMessage());
        }
        return out;
    }

    /** 世界观卡去掉快速档抽样标注行（克隆进书的就是正文设定）。 */
    private String stripFastNote(String content) {
        return content.replaceFirst("(?s)^> 注：快速档抽样合成.*?\\n\\n", "");
    }

    private String requireTitle(String title) {
        if (title == null || title.isBlank()) {
            throw new BizException(ErrorCode.PARAM_ERROR, "书名必填");
        }
        String t = title.strip();
        if (t.length() > 256) {
            throw new BizException(ErrorCode.PARAM_ERROR, "书名过长（≤256 字）");
        }
        if (novelData.findIdByTitle(t) != null) {
            throw new BizException(ErrorCode.STATE_CONFLICT, "已有同名作品：" + t);
        }
        return t;
    }
}
