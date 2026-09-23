package com.zzdzz.novelgen.controller;

import com.zzdzz.novelgen.common.web.Result;
import com.zzdzz.novelgen.model.vo.PresetCorpusVO;
import com.zzdzz.novelgen.model.vo.PresetDraftVO;
import com.zzdzz.novelgen.model.vo.PresetVO;
import com.zzdzz.novelgen.model.vo.SampleAnalyzeVO;
import com.zzdzz.novelgen.model.vo.SamplePresetVO;
import com.zzdzz.novelgen.service.GenrePresetService;
import com.zzdzz.novelgen.service.data.PresetCorpusDataService;
import lombok.RequiredArgsConstructor;
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

/** 阶段三·题材预设：品类语料导入（随时加品类/量级不限）→ 机械特征提取 → 预设 → 应用到书。 */
@RestController
@RequestMapping("/api/preset")
@RequiredArgsConstructor
public class PresetController {

    private final GenrePresetService presetService;

    @GetMapping("/genres")
    public Result<List<PresetCorpusDataService.GenreSummary>> genres() {
        return Result.success(presetService.genres());
    }

    @GetMapping("/corpus")
    public Result<List<PresetCorpusVO>> corpus(@RequestParam String genre) {
        return Result.success(presetService.listCorpus(genre));
    }

    @PostMapping("/corpus")
    public Result<Void> addCorpus(@RequestBody CorpusCreateVO vo) {
        presetService.addCorpus(vo.genre(), vo.title(), vo.content());
        return Result.success();
    }

    @DeleteMapping("/corpus/{id}")
    public Result<Void> deleteCorpus(@PathVariable long id) {
        presetService.deleteCorpus(id);
        return Result.success();
    }

    /** 机械特征提取草稿（零 LLM）：指纹带宽 + 章长预算带 + 置信提示，人工过目。 */
    @PostMapping("/extract")
    public Result<PresetDraftVO> extract(@RequestBody ExtractVO vo) {
        return Result.success(presetService.extractDraft(vo.genre()));
    }

    /** 采纳草稿为预设（幂等重跑提取）。 */
    @PostMapping("/adopt")
    public Result<Long> adopt(@RequestBody AdoptVO vo) {
        return Result.success(presetService.adopt(vo.genre(), vo.name(), vo.description()));
    }

    @GetMapping("/list")
    public Result<List<PresetVO>> presets() {
        return Result.success(presetService.listPresets());
    }

    /** 开书向导·导入小说分析（纯机械探针不落库）：切块→指纹→与现有品类相似度→复用/新建建议。 */
    @PostMapping("/analyze")
    public Result<SampleAnalyzeVO> analyze(@RequestBody AnalyzeVO vo) {
        return Result.success(presetService.analyze(vo.sampleName(), vo.text()));
    }

    /** 开书向导·由导入小说一键建品类：切块落语料 + 采纳为预设。 */
    @PostMapping("/from-sample")
    public Result<SamplePresetVO> fromSample(@RequestBody FromSampleVO vo) {
        return Result.success(presetService.createPresetFromSample(vo.genre(), vo.presetName(), vo.description(), vo.text()));
    }

    /** 素材库·导入小说专页：每本导入小说 + 完整分析快照。 */
    @GetMapping("/samples")
    public Result<List<com.zzdzz.novelgen.model.vo.ImportedSampleVO>> samples() {
        return Result.success(presetService.listSamples());
    }

    /** 应用到书（覆盖该书风格包的指纹/门禁/规则，前端二次确认）。 */
    @PostMapping("/{presetId}/apply/{novelId}")
    public Result<Void> apply(@PathVariable long presetId, @PathVariable long novelId) {
        presetService.applyToNovel(presetId, novelId);
        return Result.success();
    }

    // ===== 导入小说深度解析（样本资产化） =====

    private final com.zzdzz.novelgen.service.SampleParseService sampleParseService;

    /** 提交深度解析（FAST=抽样骨架 / FULL=全书完整），异步执行查进度。 */
    @PostMapping("/samples/{id}/parse")
    public Result<Long> parse(@PathVariable long id, @RequestBody ParseVO vo) {
        return Result.success(sampleParseService.submitParse(id, vo.mode()));
    }

    /** 断点续跑（FAILED/INTERRUPTED → 从缺口继续，已析章跳过）。 */
    @PostMapping("/samples/{id}/parse/resume")
    public Result<Long> parseResume(@PathVariable long id) {
        return Result.success(sampleParseService.resumeParse(id));
    }

    /** 解析任务状态 + 资产计数。 */
    @GetMapping("/samples/{id}/parse")
    public Result<com.zzdzz.novelgen.model.vo.SampleParseStatusVO> parseStatus(@PathVariable long id) {
        return Result.success(sampleParseService.status(id));
    }

    /** 解析资产总览：剧情结构树 + 结构化资产卡（素材库资产浏览页数据）。 */
    @GetMapping("/samples/{id}/assets")
    public Result<com.zzdzz.novelgen.model.vo.SampleAssetsVO> assets(@PathVariable long id) {
        return Result.success(sampleParseService.assets(id));
    }

    /** 资产卡人工纠偏（摘要/正文/重要度）。 */
    @PutMapping("/cards/{id}")
    public Result<Void> updateCard(@PathVariable long id, @RequestBody CardUpdateVO vo) {
        sampleParseService.updateCard(id, vo.summary(), vo.contentMd(), vo.importance());
        return Result.success();
    }

    /** 资产卡人工删除（软删，重新解析会重建）。 */
    @DeleteMapping("/cards/{id}")
    public Result<Void> deleteCard(@PathVariable long id) {
        sampleParseService.deleteCard(id);
        return Result.success();
    }

    /** 开书向导「AI 帮我定」：依据样本结构画像推荐衍生参数。 */
    @PostMapping("/samples/{id}/recommend-params")
    public Result<com.zzdzz.novelgen.model.vo.SampleParamsVO> recommendParams(@PathVariable long id) {
        return Result.success(sampleParseService.recommendParams(id));
    }

    /** 资产卡人工新建（AI 漏抽补录）。 */
    @PostMapping("/samples/{id}/cards")
    public Result<Long> createCard(@PathVariable long id, @RequestBody CardCreateVO vo) {
        return Result.success(sampleParseService.createCard(id, vo.kind(), vo.name(),
                vo.aliases(), vo.summary(), vo.contentMd(), vo.importance()));
    }

    public record ParseVO(String mode) {
    }

    public record CardUpdateVO(String summary, String contentMd, Integer importance) {
    }

    public record CardCreateVO(String kind, String name, String aliases, String summary,
                               String contentMd, Integer importance) {
    }

    public record CorpusCreateVO(String genre, String title, String content) {
    }

    public record ExtractVO(String genre) {
    }

    public record AdoptVO(String genre, String name, String description) {
    }

    public record AnalyzeVO(String sampleName, String text) {
    }

    public record FromSampleVO(String genre, String presetName, String description, String text) {
    }
}
