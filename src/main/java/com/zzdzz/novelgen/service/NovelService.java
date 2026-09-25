package com.zzdzz.novelgen.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.zzdzz.novelgen.model.dto.CanonDocDTO;
import com.zzdzz.novelgen.model.dto.ImportedSampleDTO;
import com.zzdzz.novelgen.model.dto.MaterialCardDTO;
import com.zzdzz.novelgen.model.dto.NovelDTO;
import com.zzdzz.novelgen.model.dto.SampleCardDTO;
import com.zzdzz.novelgen.model.dto.SamplePlotNodeDTO;
import com.zzdzz.novelgen.model.dto.StylePackDTO;
import com.zzdzz.novelgen.model.enums.PlanMode;
import com.zzdzz.novelgen.model.vo.NovelCreateVO;
import com.zzdzz.novelgen.common.web.BizException;
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
import java.util.List;
import java.util.Map;

/** 作品查询、开书（预设克隆 + 样本资产克隆 + 衍生配置）与审批模式切换。 */
@Service
@Slf4j
@RequiredArgsConstructor
public class NovelService {

    private final NovelDataService novelData;
    private final StylePackDataService stylePackData;
    private final SampleCardDataService sampleCardData;
    private final SamplePlotNodeDataService plotData;
    private final ImportedSampleDataService sampleData;
    private final MaterialCardDataService cardData;
    private final CanonDocDataService canonData;
    private final com.zzdzz.novelgen.service.data.GenerationTaskDataService taskData;
    private final LlmPort llm;
    private final PromptTemplateService promptTemplates;
    private final ObjectMapper mapper;

    public List<NovelVO> list() {
        return novelData.listAlive().stream().map(n -> {
            DeriveSupport.Cfg c = DeriveSupport.parse(novelData.findDeriveConfig(n.getId()));
            return new NovelVO(n.getId(), n.getTitle(), n.getDescription(), n.getApprovalMode(),
                    n.getStatus(), novelData.chapterCount(n.getId()), n.getCreateTime(),
                    c.autoContinueOn(), c.targetChapters());
        }).toList();
    }

    /** 草稿书完成激活（draft → active；条件更新防重复激活）。 */
    public void activate(long novelId) {
        requireNovel(novelId);
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
                    new NovelCreateVO.DeriveConfigVO(cfg.water(), cfg.pov(), cfg.povCharacter(), cfg.pacingNote(),
                            cfg.chaptersPerVolume(), cfg.targetChapters(), false, cfg.priority(), cfg.tags()), novelId));
        }
        novelData.updateAutoState(novelId, "OFF", "书籍已删除，无人续跑已关闭");
        novelData.softDelete(novelId);
        log.info("书籍软删 novelId={}", novelId);
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
    public NovelVO create(NovelCreateVO vo, long userId) {
        String title = requireTitle(vo.title());
        Long presetId = vo.presetId();
        if (presetId == null) {
            throw new BizException(ErrorCode.PARAM_ERROR, "请选择品类预设（没有可用预设时，先到素材库·质量与风格·品类预设提取一个）");
        }
        StylePackDTO preset = stylePackData.getById(presetId);
        if (preset == null || !preset.isPreset()) {
            throw new BizException(ErrorCode.PARAM_ERROR, "所选预设不存在: " + presetId);
        }
        String gateConfig = stylePackData.findGateConfigById(presetId);
        NovelCreateVO.DeriveConfigVO derive = vo.deriveConfig();
        if (derive != null && derive.water() != null) {
            gateConfig = DeriveSupport.applyWaterGates(gateConfig, derive.water());
        }
        long packId = stylePackData.insertPack(title + "·风格", "开书克隆自预设：" + preset.getName(),
                preset.getRulesMd() == null ? "" : preset.getRulesMd(),
                preset.getFingerprint(), gateConfig);
        String bookStatus = Boolean.TRUE.equals(vo.draft()) ? "draft" : "active";
        long novelId = novelData.insert(userId, title, vo.description() == null ? "" : vo.description(), packId, "auto", bookStatus);
        if (derive != null) {
            novelData.updateDeriveConfig(novelId, deriveConfigJson(derive, vo.sampleId()));
            if (derive.autoContinue() != null && derive.autoContinue()) {
                novelData.updatePlanMode(novelId, PlanMode.AUTO.wire());
            }
        }
        if (vo.sampleId() != null) {
            cloneSampleAssets(novelId, vo.sampleId(), vo.cloneAssets());
        }
        NovelDTO n = novelData.getById(novelId);
        return new NovelVO(n.getId(), n.getTitle(), n.getDescription(), n.getApprovalMode(), n.getStatus(), 0,
                n.getCreateTime(), derive != null && derive.autoContinue() != null && derive.autoContinue(),
                derive == null ? null : derive.targetChapters());
    }

    /** 样本资产克隆：素材卡（★2+，★3 置常驻）/世界观文档/剧情骨架预填大纲（标注待改写）。 */
    private void cloneSampleAssets(long novelId, long sampleId, NovelCreateVO.CloneAssetsVO flags) {
        ImportedSampleDTO sample = sampleData.getById(sampleId);
        if (sample == null) {
            throw new BizException(ErrorCode.PARAM_ERROR, "所选导入样本不存在: " + sampleId);
        }
        boolean cloneCards = flags == null || flags.cards() == null || flags.cards();
        boolean cloneWorld = flags == null || flags.world() == null || flags.world();
        boolean cloneOutline = flags == null || flags.plotOutline() == null || flags.plotOutline();
        if (cloneCards) {
            int count = 0;
            for (SampleCardDTO c : sampleCardData.listBySample(sampleId)) {
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
            for (SampleCardDTO c : sampleCardData.listBySample(sampleId)) {
                if (c.getKind().equals("world") && c.getContentMd() != null && !c.getContentMd().isBlank()) {
                    canonData.insert(novelId, "world", "世界观", stripFastNote(c.getContentMd()));
                    break;
                }
            }
        }
        if (cloneOutline) {
            for (SamplePlotNodeDTO n : plotData.listBySample(sampleId)) {
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
    private String deriveConfigJson(NovelCreateVO.DeriveConfigVO d, Long sampleId) {
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
     * sourceSampleId 保留原值；开无人续跑同时强制规划模式 auto（与开书口径一致）。 */
    public DeriveConfigFullVO updateDeriveConfig(long novelId, NovelCreateVO.DeriveConfigVO d) {
        requireNovel(novelId);
        Long sourceSampleId = DeriveSupport.parse(novelData.findDeriveConfig(novelId)).sourceSampleId();
        novelData.updateDeriveConfig(novelId, deriveConfigJson(d, sourceSampleId));
        if (d.autoContinue() != null && d.autoContinue()) {
            novelData.updatePlanMode(novelId, PlanMode.AUTO.wire());
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
    public String draftOutline(NovelCreateVO vo) {
        // 只做非空校验，不做全站查重——草稿流程里书已落库，worker 重放会撞自己的名字
        if (vo.title() == null || vo.title().isBlank()) {
            throw new BizException(ErrorCode.PARAM_ERROR, "书名必填");
        }
        String title = vo.title().strip();
        NovelCreateVO.DeriveConfigVO d = vo.deriveConfig();
        StylePackDTO preset = vo.presetId() == null ? null : stylePackData.getById(vo.presetId());
        if (preset == null || !preset.isPreset()) {
            throw new BizException(ErrorCode.PARAM_ERROR, "请先在第一步选择品类预设（文风语境）");
        }
        // 样本仅贡献：类型标签回填 + （勾选预填时）canon 骨架。AI 生成本身不喂样本剧情——
        // 实训教训：剧情骨架进生成材料，模型会逐桥段复刻原书（换名不换故事），衍生变抄袭。
        String skeleton = "";
        java.util.List<String> tags = d != null && d.tags() != null ? d.tags() : new java.util.ArrayList<>();
        if (vo.sampleId() != null) {
            ImportedSampleDTO sample = sampleData.getById(vo.sampleId());
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
            for (CanonDocDTO doc : canonData.listByNovel(vo.novelId())) {
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
        return outline;
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
