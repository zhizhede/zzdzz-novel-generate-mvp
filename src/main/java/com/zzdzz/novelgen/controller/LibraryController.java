package com.zzdzz.novelgen.controller;

import lombok.RequiredArgsConstructor;
import com.zzdzz.novelgen.common.web.Result;
import com.zzdzz.novelgen.service.data.DigestDataService;
import com.zzdzz.novelgen.service.data.WorldStateDataService;
import com.zzdzz.novelgen.model.entity.CanonDocDO;
import com.zzdzz.novelgen.model.entity.ForeshadowDO;
import com.zzdzz.novelgen.model.entity.MaterialCardDO;
import com.zzdzz.novelgen.model.vo.PromptDetailVO;
import com.zzdzz.novelgen.model.vo.PromptTemplateVO;
import com.zzdzz.novelgen.service.DigestService;
import com.zzdzz.novelgen.service.GateService;
import com.zzdzz.novelgen.service.LibraryService;
import com.zzdzz.novelgen.service.LlmNodeConfigService;
import com.zzdzz.novelgen.service.MaterialCardService;
import com.zzdzz.novelgen.service.PromptTemplateService;
import com.zzdzz.novelgen.service.TuningService;
import com.zzdzz.novelgen.service.EmbeddingService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 素材库：正典文档（增删改）/ 素材卡（增删改）/ 伏笔账本（修正）/ 事实账（修正）/ 风格包（规则正文修订）/ 世界状态账（查看纠偏回填）/ 调参（平台级行为参数）。 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class LibraryController {

    private final LibraryService libraryService;
    private final DigestService digestService;
    private final MaterialCardService cardService;
    private final LlmNodeConfigService nodeConfigService;
    private final TuningService tuningService;
    private final EmbeddingService embeddingService;
    private final PromptTemplateService promptService;
    private final GateService gateService;


    // ===== 提示词注册表（平台级只读；阶段二开放从库读取与编辑） =====

    @GetMapping("/prompts")
    public Result<List<PromptTemplateVO>> prompts() {
        return Result.success(promptService.list());
    }

    @GetMapping("/prompts/{id}")
    public Result<PromptDetailVO> promptDetail(@PathVariable long id) {
        return Result.success(promptService.detail(id));
    }

    /** 人工编辑模板（置 custom；占位符序列须与代码目录一致）。 */
    @PutMapping("/prompts/{id}")
    public Result<PromptDetailVO> updatePrompt(@PathVariable long id, @RequestBody PromptUpdateDTO dto) {
        return Result.success(promptService.updateContent(id, dto.content()));
    }

    /** 重置回代码目录版本（清 custom）。 */
    @PostMapping("/prompts/{id}/reset")
    public Result<PromptDetailVO> resetPrompt(@PathVariable long id) {
        return Result.success(promptService.reset(id));
    }

    // ===== 调参（平台级行为参数，改后 30s 内生效） =====

    @GetMapping("/tuning")
    public Result<List<com.zzdzz.novelgen.model.entity.TuningDO>> tuning() {
        return Result.success(tuningService.list());
    }

    @PutMapping("/tuning/{key}")
    public Result<Void> updateTuning(@PathVariable String key, @RequestBody TuningUpdateDTO dto) {
        tuningService.update(key, String.valueOf(dto.value()));
        return Result.success();
    }

    // ===== 模型路由（平台级，所有作品共用） =====

    @GetMapping("/llm-nodes")
    public Result<List<LlmNodeConfigService.NodeVO>> llmNodes() {
        return Result.success(nodeConfigService.list());
    }

    @PostMapping("/llm-nodes")
    public Result<Void> createLlmNode(@RequestBody LlmNodeCreateDTO dto) {
        nodeConfigService.create(dto.node(), dto.model(), dto.temperature(), dto.maxTokens(),
                dto.extraJson(), dto.enabled(), dto.remark());
        return Result.success();
    }

    @PutMapping("/llm-nodes/{id}")
    public Result<Void> updateLlmNode(@PathVariable long id, @RequestBody LlmNodeUpdateDTO dto) {
        nodeConfigService.update(id, dto.model(), dto.temperature(), dto.maxTokens(),
                dto.extraJson(), dto.enabled(), dto.remark());
        return Result.success();
    }

    @DeleteMapping("/llm-nodes/{id}")
    public Result<Void> deleteLlmNode(@PathVariable long id) {
        nodeConfigService.delete(id);
        return Result.success();
    }

    @GetMapping("/llm-prices")
    public Result<List<com.zzdzz.novelgen.model.entity.LlmModelPriceDO>> llmPrices() {
        return Result.success(nodeConfigService.prices());
    }

    @PutMapping("/llm-prices/{id}")
    public Result<Void> updateLlmPrice(@PathVariable long id, @RequestBody LlmPriceUpdateDTO dto) {
        nodeConfigService.updatePrice(id,
                dto.idleInputHit(), dto.idleInputMiss(), dto.idleOutput(),
                dto.peakInputHit(), dto.peakInputMiss(), dto.peakOutput(),
                dto.peakStartHour() != null ? dto.peakStartHour() : 14,
                dto.peakEndHour() != null ? dto.peakEndHour() : 18,
                dto.remark());
        return Result.success();
    }

    // ===== 向量索引（RAG 语义检索） =====

    @GetMapping("/novels/{novelId}/embeddings/status")
    public Result<Map<String, Object>> embeddingStatus(@PathVariable long novelId) {
        return Result.success(Map.of("indexed", embeddingService.countByNovel(novelId),
                "enabled", embeddingService.enabled()));
    }

    /** 手动回填：补嵌缺失的事实账摘要与素材卡（惰性索引也会在生成时自动补）。 */
    @PostMapping("/novels/{novelId}/embeddings/backfill")
    public Result<Map<String, Object>> embeddingBackfill(@PathVariable long novelId) {
        int added = embeddingService.backfillNovel(novelId);
        return Result.success(Map.of("added", added, "indexed", embeddingService.countByNovel(novelId)));
    }

    // ===== 素材卡 =====

    @GetMapping("/novels/{novelId}/cards")
    public Result<List<MaterialCardDO>> cards(@PathVariable long novelId,
                                              @RequestParam(required = false) String kind) {
        return Result.success(cardService.list(novelId, kind));
    }

    @GetMapping("/cards/{id}")
    public Result<MaterialCardDO> card(@PathVariable long id) {
        return Result.success(cardService.get(id));
    }

    @PostMapping("/novels/{novelId}/cards")
    public Result<Void> createCard(@PathVariable long novelId, @RequestBody CardSaveDTO dto) {
        cardService.create(novelId, dto.kind(), dto.name(), dto.aliases(), dto.summary(), dto.contentMd(),
                dto.pinned(), dto.status(), dto.sourceChapter());
        return Result.success();
    }

    @PutMapping("/cards/{id}")
    public Result<Void> updateCard(@PathVariable long id, @RequestBody CardSaveDTO dto) {
        cardService.update(id, dto.name(), dto.aliases(), dto.summary(), dto.contentMd(),
                dto.pinned(), dto.status(), dto.sourceChapter());
        return Result.success();
    }

    @DeleteMapping("/cards/{id}")
    public Result<Void> deleteCard(@PathVariable long id) {
        cardService.delete(id);
        return Result.success();
    }

    // ===== 正典 =====

    @GetMapping("/novels/{novelId}/canon")
    public Result<List<CanonDocDO>> canonList(@PathVariable long novelId) {
        return Result.success(libraryService.listCanon(novelId));
    }

    @GetMapping("/canon/{id}")
    public Result<CanonDocDO> canonDoc(@PathVariable long id) {
        return Result.success(libraryService.canonDoc(id));
    }

    @PostMapping("/novels/{novelId}/canon")
    public Result<Void> createCanon(@PathVariable long novelId, @RequestBody CanonCreateDTO dto) {
        libraryService.createCanon(novelId, dto.kind(), dto.name(), dto.content());
        return Result.success();
    }

    @PutMapping("/canon/{id}")
    public Result<Void> updateCanon(@PathVariable long id, @RequestBody CanonUpdateDTO dto) {
        libraryService.updateCanon(id, dto.content());
        return Result.success();
    }

    @DeleteMapping("/canon/{id}")
    public Result<Void> deleteCanon(@PathVariable long id) {
        libraryService.deleteCanon(id);
        return Result.success();
    }

    // ===== 伏笔 =====

    @GetMapping("/novels/{novelId}/foreshadows")
    public Result<List<ForeshadowDO>> foreshadows(@PathVariable long novelId) {
        return Result.success(libraryService.listForeshadows(novelId));
    }

    @PutMapping("/foreshadows/{id}")
    public Result<Void> updateForeshadow(@PathVariable long id, @RequestBody ForeshadowUpdateDTO dto) {
        libraryService.updateForeshadow(id, dto.content(), dto.plantedIn(), dto.recoveredIn(), dto.status());
        return Result.success();
    }

    // ===== 事实账 =====

    @GetMapping("/novels/{novelId}/digests")
    public Result<List<DigestDataService.DigestItem>> digests(@PathVariable long novelId) {
        return Result.success(libraryService.listDigests(novelId));
    }

    @PutMapping("/digests/{id}")
    public Result<Void> updateDigest(@PathVariable long id, @RequestBody DigestUpdateDTO dto) {
        libraryService.updateDigest(id, dto.contentMd(), dto.facts());
        return Result.success();
    }

    // ===== 风格包 =====

    @GetMapping("/novels/{novelId}/style")
    public Result<LibraryService.StylePackVO> style(@PathVariable long novelId) {
        return Result.success(libraryService.styleByNovel(novelId));
    }

    @PutMapping("/novels/{novelId}/style")
    public Result<Void> updateStyle(@PathVariable long novelId, @RequestBody StyleUpdateDTO dto) {
        libraryService.updateStyleRules(novelId, dto.rulesMd());
        return Result.success();
    }

    @PutMapping("/novels/{novelId}/gate-config")
    public Result<Void> updateGateConfig(@PathVariable long novelId, @RequestBody GateConfigUpdateDTO dto) {
        libraryService.updateGateConfig(novelId, dto.gateConfig());
        return Result.success();
    }

    /** 原始门禁配置 JSON（前端合并评审标准后保存）。 */
    @GetMapping("/novels/{novelId}/gate-config")
    public Result<String> gateConfig(@PathVariable long novelId) {
        return Result.success(libraryService.gateConfigJson(novelId));
    }

    /** 有效评审标准五项（gate_config > tuning > 代码默认），工作台/风格包调参面板回显。 */
    @GetMapping("/novels/{novelId}/reader-standards")
    public Result<Map<String, Double>> readerStandards(@PathVariable long novelId) {
        return Result.success(gateService.readerStandards(novelId));
    }

    // ===== 世界状态账 =====

    @GetMapping("/novels/{novelId}/world-states")
    public Result<List<WorldStateDataService.StateRow>> worldStates(@PathVariable long novelId,
                                                            @RequestParam(defaultValue = "50") int limit) {
        return Result.success(libraryService.listWorldStates(novelId, limit));
    }

    /** 人工纠偏某章快照：body.state 为 JSON 字符串。 */
    @PutMapping("/novels/{novelId}/world-states/{chapterNo}")
    public Result<Void> saveWorldState(@PathVariable long novelId, @PathVariable int chapterNo,
                                       @RequestBody WorldStateSaveDTO dto) {
        libraryService.saveWorldState(novelId, chapterNo, dto.state());
        return Result.success();
    }

    /** 存量回填：对已有正文但无快照的章做一次轻量状态抽取（同步，约 30-60 秒/章）。 */
    @PostMapping("/novels/{novelId}/world-states/backfill/{chapterNo}")
    public Result<Void> backfillWorldState(@PathVariable long novelId, @PathVariable int chapterNo) {
        digestService.backfillState(novelId, chapterNo);
        return Result.success();
    }

    // ===== 请求 DTO（字段名与前端 payload 逐字对齐；后缀规约）=====

    public record PromptUpdateDTO(String content) {}

    /** tuning 值库存为字符串，数字/文本都可能，绑定保持 Object。 */
    public record TuningUpdateDTO(Object value) {}

    public record LlmNodeCreateDTO(String node, String model, Double temperature, Integer maxTokens,
                                   String extraJson, Boolean enabled, String remark) {}

    public record LlmNodeUpdateDTO(String model, Double temperature, Integer maxTokens,
                                   String extraJson, Boolean enabled, String remark) {}

    public record LlmPriceUpdateDTO(java.math.BigDecimal idleInputHit, java.math.BigDecimal idleInputMiss,
                                     java.math.BigDecimal idleOutput, java.math.BigDecimal peakInputHit,
                                     java.math.BigDecimal peakInputMiss, java.math.BigDecimal peakOutput,
                                     Integer peakStartHour, Integer peakEndHour, String remark) {}

    /** 素材卡新增/更新共用；缺省 aliases 归一为空表。 */
    public record CardSaveDTO(String kind, String name, List<String> aliases, String summary, String contentMd,
                              Boolean pinned, String status, Integer sourceChapter) {
        public CardSaveDTO {
            if (aliases == null) aliases = List.of();
        }
    }

    public record CanonCreateDTO(String kind, String name, String content) {}

    public record CanonUpdateDTO(String content) {}

    public record ForeshadowUpdateDTO(String content, Integer plantedIn, Integer recoveredIn, String status) {}

    public record DigestUpdateDTO(String contentMd, String facts) {}

    public record StyleUpdateDTO(String rulesMd) {}

    public record GateConfigUpdateDTO(String gateConfig) {}

    public record WorldStateSaveDTO(String state) {}
}
