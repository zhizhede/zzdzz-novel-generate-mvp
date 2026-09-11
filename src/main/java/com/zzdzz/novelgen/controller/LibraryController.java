package com.zzdzz.novelgen.controller;

import com.zzdzz.novelgen.common.web.Result;
import com.zzdzz.novelgen.dao.DigestDAO;
import com.zzdzz.novelgen.dao.WorldStateDAO;
import com.zzdzz.novelgen.model.entity.CanonDocDO;
import com.zzdzz.novelgen.model.entity.ForeshadowDO;
import com.zzdzz.novelgen.service.DigestService;
import com.zzdzz.novelgen.service.LibraryService;
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

/** 素材库：正典文档（增删改）/ 伏笔账本（修正）/ 事实账（修正）/ 风格包（规则正文修订）/ 世界状态账（查看纠偏回填）。 */
@RestController
@RequestMapping("/api")
public class LibraryController {

    private final LibraryService libraryService;
    private final DigestService digestService;

    public LibraryController(LibraryService libraryService, DigestService digestService) {
        this.libraryService = libraryService;
        this.digestService = digestService;
    }

    // ===== 正典 =====

    @GetMapping("/novels/{novelId}/canon")
    public Result<List<CanonDocDO>> canonList(@PathVariable long novelId) {
        return Result.ok(libraryService.listCanon(novelId));
    }

    @GetMapping("/canon/{id}")
    public Result<CanonDocDO> canonDoc(@PathVariable long id) {
        return Result.ok(libraryService.canonDoc(id));
    }

    @PostMapping("/novels/{novelId}/canon")
    public Result<Void> createCanon(@PathVariable long novelId, @RequestBody Map<String, String> body) {
        libraryService.createCanon(novelId, body.get("kind"), body.get("name"), body.get("content"));
        return Result.ok();
    }

    @PutMapping("/canon/{id}")
    public Result<Void> updateCanon(@PathVariable long id, @RequestBody Map<String, String> body) {
        libraryService.updateCanon(id, body.get("content"));
        return Result.ok();
    }

    @DeleteMapping("/canon/{id}")
    public Result<Void> deleteCanon(@PathVariable long id) {
        libraryService.deleteCanon(id);
        return Result.ok();
    }

    // ===== 伏笔 =====

    @GetMapping("/novels/{novelId}/foreshadows")
    public Result<List<ForeshadowDO>> foreshadows(@PathVariable long novelId) {
        return Result.ok(libraryService.listForeshadows(novelId));
    }

    @PutMapping("/foreshadows/{id}")
    public Result<Void> updateForeshadow(@PathVariable long id, @RequestBody Map<String, Object> body) {
        Integer plantedIn = body.get("plantedIn") instanceof Number n ? n.intValue() : null;
        Integer recoveredIn = body.get("recoveredIn") instanceof Number n ? n.intValue() : null;
        libraryService.updateForeshadow(id, (String) body.get("content"),
                plantedIn, recoveredIn, (String) body.get("status"));
        return Result.ok();
    }

    // ===== 事实账 =====

    @GetMapping("/novels/{novelId}/digests")
    public Result<List<DigestDAO.DigestItem>> digests(@PathVariable long novelId) {
        return Result.ok(libraryService.listDigests(novelId));
    }

    @PutMapping("/digests/{id}")
    public Result<Void> updateDigest(@PathVariable long id, @RequestBody Map<String, String> body) {
        libraryService.updateDigest(id, body.get("contentMd"), body.get("facts"));
        return Result.ok();
    }

    // ===== 风格包 =====

    @GetMapping("/novels/{novelId}/style")
    public Result<LibraryService.StylePackVO> style(@PathVariable long novelId) {
        return Result.ok(libraryService.styleByNovel(novelId));
    }

    @PutMapping("/novels/{novelId}/style")
    public Result<Void> updateStyle(@PathVariable long novelId, @RequestBody Map<String, String> body) {
        libraryService.updateStyleRules(novelId, body.get("rulesMd"));
        return Result.ok();
    }

    @PutMapping("/novels/{novelId}/gate-config")
    public Result<Void> updateGateConfig(@PathVariable long novelId, @RequestBody Map<String, String> body) {
        libraryService.updateGateConfig(novelId, body.get("gateConfig"));
        return Result.ok();
    }

    // ===== 世界状态账 =====

    @GetMapping("/novels/{novelId}/world-states")
    public Result<List<WorldStateDAO.StateRow>> worldStates(@PathVariable long novelId,
                                                            @RequestParam(defaultValue = "50") int limit) {
        return Result.ok(libraryService.listWorldStates(novelId, limit));
    }

    /** 人工纠偏某章快照：body.state 为 JSON 字符串。 */
    @PutMapping("/novels/{novelId}/world-states/{chapterNo}")
    public Result<Void> saveWorldState(@PathVariable long novelId, @PathVariable int chapterNo,
                                       @RequestBody Map<String, String> body) {
        libraryService.saveWorldState(novelId, chapterNo, body.get("state"));
        return Result.ok();
    }

    /** 存量回填：对已有正文但无快照的章做一次轻量状态抽取（同步，约 30-60 秒/章）。 */
    @PostMapping("/novels/{novelId}/world-states/backfill/{chapterNo}")
    public Result<Void> backfillWorldState(@PathVariable long novelId, @PathVariable int chapterNo) {
        digestService.backfillState(novelId, chapterNo);
        return Result.ok();
    }
}
