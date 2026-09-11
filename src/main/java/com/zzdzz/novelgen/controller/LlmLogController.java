package com.zzdzz.novelgen.controller;

import com.zzdzz.novelgen.common.web.Result;
import com.zzdzz.novelgen.model.vo.LlmLogDetailVO;
import com.zzdzz.novelgen.model.vo.LlmLogVO;
import com.zzdzz.novelgen.model.vo.LlmTotalsVO;
import com.zzdzz.novelgen.model.vo.PageVO;
import com.zzdzz.novelgen.model.vo.PipelineEventVO;
import com.zzdzz.novelgen.service.LlmLogService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** LLM 调用台账：token 用量 / 耗时 / 模型；think 与正文在详情接口分区返回。另含管线事件流水。 */
@RestController
@RequestMapping("/api/llm-logs")
public class LlmLogController {

    private final LlmLogService llmLogService;

    public LlmLogController(LlmLogService llmLogService) {
        this.llmLogService = llmLogService;
    }

    @GetMapping
    public Result<PageVO<LlmLogVO>> page(@RequestParam(required = false) Long novelId,
                                         @RequestParam(required = false) Long chapterId,
                                         @RequestParam(defaultValue = "1") int page,
                                         @RequestParam(defaultValue = "20") int size) {
        return Result.ok(llmLogService.page(novelId, chapterId, page, size));
    }

    @GetMapping("/totals")
    public Result<LlmTotalsVO> totals(@RequestParam(required = false) Long novelId,
                                      @RequestParam(required = false) Long chapterId) {
        return Result.ok(llmLogService.totals(novelId, chapterId));
    }

    /** 事件流水：生成履历回放（章纲→场景→门禁→修订→审校→落账），payload 含失败原因与轮次。 */
    @GetMapping("/events")
    public Result<List<PipelineEventVO>> events(@RequestParam Long novelId,
                                                @RequestParam(required = false) Integer chapterNo,
                                                @RequestParam(defaultValue = "200") int limit) {
        return Result.ok(llmLogService.events(novelId, chapterNo, limit));
    }

    @GetMapping("/{id}")
    public Result<LlmLogDetailVO> detail(@PathVariable long id) {
        return Result.ok(llmLogService.detail(id));
    }
}
