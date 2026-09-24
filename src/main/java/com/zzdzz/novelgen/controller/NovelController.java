package com.zzdzz.novelgen.controller;

import lombok.RequiredArgsConstructor;
import com.zzdzz.novelgen.common.web.AuthInterceptor;
import com.zzdzz.novelgen.common.web.Result;
import com.zzdzz.novelgen.model.vo.ApprovalModeVO;
import com.zzdzz.novelgen.model.vo.NovelCreateVO;
import com.zzdzz.novelgen.model.vo.NovelVO;
import com.zzdzz.novelgen.service.NovelService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.DeleteMapping;
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
    private final com.zzdzz.novelgen.service.OutlineDraftService outlineDraftService;


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

    /** 开书向导「AI 生成大纲」：异步提交，秒回任务 id（POST）——向导不阻塞，业务方可连续批量提交；GET 轮询状态与结果。 */
    @PostMapping("/outline-draft")
    public Result<Long> outlineDraft(@RequestBody NovelCreateVO dto) {
        return Result.success(outlineDraftService.submit(dto, dto.novelId()));
    }

    /** 草稿书完成激活（draft → active）。 */
    @PostMapping("/{id}/activate")
    public Result<Void> activate(@PathVariable long id) {
        novelService.activate(id);
        return Result.success();
    }

    /** 某书最新一份大纲任务（草稿恢复：进向导时取回生成结果/进度）。 */
    @GetMapping("/{id}/outline-draft/latest")
    public Result<com.zzdzz.novelgen.model.dto.OutlineDraftTaskDTO> latestOutlineDraft(@PathVariable long id) {
        return Result.success(outlineDraftService.latestByNovel(id));
    }

    @GetMapping("/outline-draft/{taskId}")
    public Result<com.zzdzz.novelgen.model.dto.OutlineDraftTaskDTO> outlineDraftStatus(@PathVariable long taskId) {
        return Result.success(outlineDraftService.status(taskId));
    }

    /** 编辑书籍基本信息（书名全站唯一）。 */
    @PutMapping("/{id}")
    public Result<Void> updateProfile(@PathVariable long id, @RequestBody NovelUpdateVO dto) {
        novelService.updateProfile(id, dto.title(), dto.description());
        return Result.success();
    }

    /** 删除书籍（软删）。有排队/运行中任务时拒绝；无人续跑自动关闭。 */
    @DeleteMapping("/{id}")
    public Result<Void> deleteNovel(@PathVariable long id) {
        novelService.deleteNovel(id);
        return Result.success();
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

    public record NovelUpdateVO(String title, String description) {
    }
}
