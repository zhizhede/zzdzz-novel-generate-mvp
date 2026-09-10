package com.zzdzz.novelgen.controller;

import com.zzdzz.novelgen.common.web.Result;
import com.zzdzz.novelgen.model.dto.ApprovalModeDTO;
import com.zzdzz.novelgen.model.vo.NovelVO;
import com.zzdzz.novelgen.service.NovelService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 作品列表与审批模式切换（auto 直过 / manual 人工）。 */
@RestController
@RequestMapping("/api/novels")
public class NovelController {

    private final NovelService novelService;

    public NovelController(NovelService novelService) {
        this.novelService = novelService;
    }

    @GetMapping
    public Result<List<NovelVO>> list() {
        return Result.ok(novelService.list());
    }

    @PutMapping("/{id}/approval-mode")
    public Result<Void> setApprovalMode(@PathVariable long id, @RequestBody ApprovalModeDTO dto) {
        novelService.setApprovalMode(id, dto.mode());
        return Result.ok();
    }
}
