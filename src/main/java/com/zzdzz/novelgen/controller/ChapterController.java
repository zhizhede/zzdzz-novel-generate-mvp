package com.zzdzz.novelgen.controller;

import com.zzdzz.novelgen.common.web.Result;
import com.zzdzz.novelgen.model.vo.ChapterDetailVO;
import com.zzdzz.novelgen.model.vo.ChapterListItemVO;
import com.zzdzz.novelgen.service.ChapterQueryService;
import com.zzdzz.novelgen.service.ChapterPipelineService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 章查询与人工审批。 */
@RestController
@RequestMapping("/api")
public class ChapterController {

    private final ChapterQueryService chapterQueryService;
    private final ChapterPipelineService pipelineService;

    public ChapterController(ChapterQueryService chapterQueryService,
                             ChapterPipelineService pipelineService) {
        this.chapterQueryService = chapterQueryService;
        this.pipelineService = pipelineService;
    }

    @GetMapping("/novels/{novelId}/chapters")
    public Result<List<ChapterListItemVO>> list(@PathVariable long novelId) {
        return Result.ok(chapterQueryService.listByNovel(novelId));
    }

    @GetMapping("/chapters/{id}")
    public Result<ChapterDetailVO> detail(@PathVariable long id) {
        return Result.ok(chapterQueryService.detail(id));
    }

    @PostMapping("/chapters/{id}/approve")
    public Result<Void> approve(@PathVariable long id) {
        pipelineService.approve(id);
        return Result.ok();
    }
}
