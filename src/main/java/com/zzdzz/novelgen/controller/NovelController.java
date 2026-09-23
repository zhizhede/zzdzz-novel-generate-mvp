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

    /** 开书：书名 + 品类预设 → 克隆预设为本书风格包；可选样本资产克隆与衍生配置（P2）。 */
    @PostMapping
    public Result<NovelVO> create(@RequestBody NovelCreateVO dto, HttpServletRequest request) {
        Long userId = (Long) request.getAttribute(AuthInterceptor.ATTR_USER_ID);
        return Result.success(novelService.create(dto, userId));
    }

    @PutMapping("/{id}/approval-mode")
    public Result<Void> setApprovalMode(@PathVariable long id, @RequestBody ApprovalModeVO dto) {
        novelService.setApprovalMode(id, dto.mode());
        return Result.success();
    }

    /** 开书向导「AI 生成大纲」：基本信息+衍生设定 → 大纲草稿 markdown（纯生成不落库，llm_call_log node=derive_outline 记账）。 */
    @PostMapping("/outline-draft")
    public Result<String> outlineDraft(@RequestBody NovelCreateVO dto) {
        return Result.success(novelService.draftOutline(dto));
    }

    /** 衍生配置全量读（开书后改参/老书启用无人续跑的回显口）。 */
    @GetMapping("/{id}/derive-config")
    public Result<com.zzdzz.novelgen.service.NovelService.DeriveConfigFullVO> deriveConfig(@PathVariable long id) {
        return Result.success(novelService.deriveConfig(id));
    }

    /** 衍生配置编辑（书全生命周期可改；开无人续跑同时强制规划模式 auto）。 */
    @PutMapping("/{id}/derive-config")
    public Result<com.zzdzz.novelgen.service.NovelService.DeriveConfigFullVO> updateDeriveConfig(
            @PathVariable long id, @RequestBody NovelCreateVO vo) {
        return Result.success(novelService.updateDeriveConfig(id, vo.deriveConfig()));
    }
}
