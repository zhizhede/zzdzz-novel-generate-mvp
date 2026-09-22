package com.zzdzz.novelgen.controller;

import com.zzdzz.novelgen.common.web.Result;
import com.zzdzz.novelgen.model.vo.PresetCorpusVO;
import com.zzdzz.novelgen.model.vo.PresetDraftVO;
import com.zzdzz.novelgen.model.vo.PresetVO;
import com.zzdzz.novelgen.service.GenrePresetService;
import com.zzdzz.novelgen.service.data.PresetCorpusDataService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
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

    /** 应用到书（覆盖该书风格包的指纹/门禁/规则，前端二次确认）。 */
    @PostMapping("/{presetId}/apply/{novelId}")
    public Result<Void> apply(@PathVariable long presetId, @PathVariable long novelId) {
        presetService.applyToNovel(presetId, novelId);
        return Result.success();
    }

    public record CorpusCreateVO(String genre, String title, String content) {
    }

    public record ExtractVO(String genre) {
    }

    public record AdoptVO(String genre, String name, String description) {
    }
}
