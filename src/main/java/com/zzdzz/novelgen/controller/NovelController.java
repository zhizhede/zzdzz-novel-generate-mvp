package com.zzdzz.novelgen.controller;

import lombok.RequiredArgsConstructor;
import com.zzdzz.novelgen.common.web.AuthInterceptor;
import com.zzdzz.novelgen.common.web.Result;
import com.zzdzz.novelgen.model.vo.ApprovalModeVO;
import com.zzdzz.novelgen.model.vo.NovelCreateVO;
import com.zzdzz.novelgen.model.vo.NovelVO;
import com.zzdzz.novelgen.service.NovelService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 作品列表、开书与审批模式切换（auto 直过 / manual 人工）。 */
@RestController
@RequestMapping("/api/novels")
@RequiredArgsConstructor
public class NovelController {

    private final NovelService novelService;


    @GetMapping
    public Result<List<NovelVO>> list() {
        return Result.success(novelService.list());
    }

    /** 开书：书名 + 品类预设 → 克隆预设为本书风格包。 */
    @PostMapping
    public Result<NovelVO> create(@RequestBody NovelCreateVO dto, HttpServletRequest request) {
        Long userId = (Long) request.getAttribute(AuthInterceptor.ATTR_USER_ID);
        return Result.success(novelService.create(dto.title(), dto.description(), dto.presetId(), userId));
    }

    @PutMapping("/{id}/approval-mode")
    public Result<Void> setApprovalMode(@PathVariable long id, @RequestBody ApprovalModeVO dto) {
        novelService.setApprovalMode(id, dto.mode());
        return Result.success();
    }
}
