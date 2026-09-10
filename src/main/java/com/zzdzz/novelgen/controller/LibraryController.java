package com.zzdzz.novelgen.controller;

import com.zzdzz.novelgen.common.web.Result;
import com.zzdzz.novelgen.model.entity.CanonDocDO;
import com.zzdzz.novelgen.model.entity.ForeshadowDO;
import com.zzdzz.novelgen.service.LibraryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 素材库：正典文档 / 伏笔账本 / 风格包。 */
@RestController
@RequestMapping("/api")
public class LibraryController {

    private final LibraryService libraryService;

    public LibraryController(LibraryService libraryService) {
        this.libraryService = libraryService;
    }

    @GetMapping("/novels/{novelId}/canon")
    public Result<List<CanonDocDO>> canonList(@PathVariable long novelId) {
        return Result.ok(libraryService.listCanon(novelId));
    }

    @GetMapping("/canon/{id}")
    public Result<CanonDocDO> canonDoc(@PathVariable long id) {
        return Result.ok(libraryService.canonDoc(id));
    }

    @PutMapping("/canon/{id}")
    public Result<Void> updateCanon(@PathVariable long id, @RequestBody Map<String, String> body) {
        libraryService.updateCanon(id, body.get("content"));
        return Result.ok();
    }

    @GetMapping("/novels/{novelId}/foreshadows")
    public Result<List<ForeshadowDO>> foreshadows(@PathVariable long novelId) {
        return Result.ok(libraryService.listForeshadows(novelId));
    }

    @GetMapping("/novels/{novelId}/style")
    public Result<LibraryService.StylePackVO> style(@PathVariable long novelId) {
        return Result.ok(libraryService.styleByNovel(novelId));
    }
}
