package com.zzdzz.novelgen.derive.web;

import com.zzdzz.novelgen.common.web.Result;
import com.zzdzz.novelgen.derive.service.DeriveDraftService;
import com.zzdzz.novelgen.derive.service.DeriveAutoFlowService;
import com.zzdzz.novelgen.derive.service.DeriveBeatRouteService;
import com.zzdzz.novelgen.derive.service.DeriveGateService;
import com.zzdzz.novelgen.derive.service.DerivePlanService;
import com.zzdzz.novelgen.derive.service.DeriveQcService;
import com.zzdzz.novelgen.derive.service.DeriveQueryService;
import com.zzdzz.novelgen.derive.service.DeriveResetService;
import com.zzdzz.novelgen.derive.service.DeriveSampleService;
import com.zzdzz.novelgen.derive.service.DeriveStoryPlanService;
import com.zzdzz.novelgen.derive.service.DeriveVolumeService;
import com.zzdzz.novelgen.derive.service.DeriveWriteService;
import com.zzdzz.novelgen.derive.orchestration.DeriveRunOrchestrator;
import com.zzdzz.novelgen.model.dto.derive.RunCreateDTO;
import com.zzdzz.novelgen.model.dto.derive.RunQueryDTO;
import com.zzdzz.novelgen.model.dto.derive.PlanScopeConfirmDTO;
import com.zzdzz.novelgen.model.vo.derive.BeatVO;
import com.zzdzz.novelgen.model.vo.derive.BeatRouteVO;
import com.zzdzz.novelgen.model.vo.derive.AutoVO;
import com.zzdzz.novelgen.model.vo.derive.DraftProgressVO;
import com.zzdzz.novelgen.model.vo.derive.DraftVO;
import com.zzdzz.novelgen.model.vo.derive.GateVO;
import com.zzdzz.novelgen.model.vo.derive.LlmCallDetailVO;
import com.zzdzz.novelgen.model.vo.derive.LlmTrailVO;
import com.zzdzz.novelgen.model.vo.derive.PlanVO;
import com.zzdzz.novelgen.model.vo.derive.QcReportVO;
import com.zzdzz.novelgen.model.vo.derive.QcVO;
import com.zzdzz.novelgen.model.vo.derive.RunChapterVO;
import com.zzdzz.novelgen.model.vo.derive.RunDetailVO;
import com.zzdzz.novelgen.model.vo.derive.RunListItemVO;
import com.zzdzz.novelgen.model.vo.derive.RunResetVO;
import com.zzdzz.novelgen.model.vo.derive.RunVO;
import com.zzdzz.novelgen.model.vo.derive.StoryPlanDetailVO;
import com.zzdzz.novelgen.model.vo.derive.VolumePlanVO;
import com.zzdzz.novelgen.model.vo.derive.WriteLoopVO;
import com.zzdzz.novelgen.model.vo.derive.WriteVO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** ⑤采样 + ⑥a计划 + ⑥b具体稿 REST（写侧同步；LLM 节点耗时长，客户端留足超时）+ run 读端点（进度/结果展示）。 */
@RestController
@RequestMapping("/api/derive/runs")
@RequiredArgsConstructor
public class DeriveRunController {

    private final DeriveSampleService sampleService;
    private final DerivePlanService planService;
    private final DeriveStoryPlanService storyPlanService;
    private final DeriveBeatRouteService beatRouteService;
    private final DeriveDraftService draftService;
    private final DeriveWriteService writeService;
    private final DeriveQcService qcService;
    private final DeriveQueryService queryService;
    private final DeriveGateService gateService;
    private final DeriveAutoFlowService autoFlowService;
    private final DeriveResetService resetService;
    private final DeriveVolumeService volumeService;
    private final DeriveRunOrchestrator runOrchestrator;

    @PostMapping
    public Result<RunVO> create(@RequestBody RunCreateDTO dto) {
        return Result.success(sampleService.create(dto));
    }

    /** run 列表（带筛选：status/novelId/keyword/from/to/sort，语义见 RunQueryDTO）。 */
    @GetMapping
    public Result<List<RunListItemVO>> list(RunQueryDTO condition) {
        return Result.success(queryService.listRuns(condition));
    }

    /** run 详情：状态机 + ⑤参数 + 章/拍进度聚合 + 最新质检判定（进度展示用）。 */
    @GetMapping("/{id}")
    public Result<RunDetailVO> get(@PathVariable long id) {
        return Result.success(queryService.getRun(id));
    }

    /** 成品章进度列表（章号升序；includeText=true 附正文；status 可筛）。 */
    @GetMapping("/{id}/chapters")
    public Result<List<RunChapterVO>> chapters(@PathVariable long id,
                                               @RequestParam(defaultValue = "false") boolean includeText,
                                               @RequestParam(required = false) String status) {
        return Result.success(queryService.listRunChapters(id, includeText, status));
    }

    /** 清空⑥及之后的规划/正文数据；保留⑤采样、焊接和参数，回到 PLANNING。 */
    @PostMapping("/{id}/clear-planning")
    public Result<RunResetVO> clearPlanning(@PathVariable long id) {
        return Result.success(resetService.clearPlanning(id));
    }

    /** run 的卷级规划：卷边界、本卷事件、局部闭合与下一卷钩子。 */
    @GetMapping("/{id}/volumes")
    public Result<List<VolumePlanVO>> volumes(@PathVariable long id) {
        return Result.success(volumeService.list(id));
    }

    /** 质检历史（轮次降序；verdict 可筛 PASS/REWORK/FAIL）。 */
    @GetMapping("/{id}/qc-reports")
    public Result<List<QcReportVO>> qcReports(@PathVariable long id,
                                              @RequestParam(required = false) String verdict) {
        return Result.success(queryService.listQcReports(id, verdict));
    }

    /** 拍实例进度（seq 升序；status/chapterNo 可筛）。 */
    @GetMapping("/{id}/beats")
    public Result<List<BeatVO>> beats(@PathVariable long id,
                                      @RequestParam(required = false) String status,
                                      @RequestParam(required = false) Integer chapterNo) {
        return Result.success(queryService.listBeats(id, status, chapterNo));
    }

    /** ⑥a章节计划（run 必须处于 PLANNING 且未计划过）。 */
    @PostMapping("/{id}/plan")
    public Result<PlanVO> plan(@PathVariable long id) {
        return Result.success(planService.plan(id));
    }

    /** 模糊规模确认：确定卷数并允许/禁止扩卷。 */
    @PostMapping("/{id}/plan/confirm-scope")
    public Result<PlanVO> confirmScope(@PathVariable long id,
                                       @RequestBody PlanScopeConfirmDTO dto) {
        return Result.success(storyPlanService.confirmScope(
                id, dto.volumeCount(), dto.allowExpansion()));
    }

    /** 故事脊柱、卷合同、角色、线程、章槽完整读模型。 */
    @GetMapping("/{id}/story-plan")
    public Result<StoryPlanDetailVO> storyPlan(@PathVariable long id) {
        return Result.success(queryService.storyPlan(id));
    }

    /** 单卷拍路由。 */
    @GetMapping("/{id}/volumes/{volumeNo}/beats")
    public Result<List<BeatVO>> volumeBeats(@PathVariable long id,
                                            @PathVariable int volumeNo) {
        return Result.success(queryService.listVolumeBeats(id, volumeNo));
    }

    /** 只重做目标卷拍路由；已有正文卷由 service 拒绝。 */
    @PostMapping("/{id}/volumes/{volumeNo}/replan")
    public Result<BeatRouteVO> replanVolume(@PathVariable long id,
                                            @PathVariable int volumeNo) {
        return Result.success(beatRouteService.replanVolume(id, volumeNo));
    }

    /** ⑥b生成具体稿（v3.7 幂等单元制：每次只推进 HEAD/BATCH 一窗/RESET 回炉清稿/DONE，循环至 allDone）。 */
    @PostMapping("/{id}/draft")
    public Result<DraftVO> draft(@PathVariable long id) {
        return Result.success(draftService.draft(id));
    }

    /** ⑥b服务端进度（透明化轮询：phase/输出帧数/耗时/空闲毫秒——idle 持续增大=模型卡流）。 */
    @GetMapping("/{id}/draft-progress")
    public Result<DraftProgressVO> draftProgress(@PathVariable long id) {
        return Result.success(draftService.progress(id));
    }

    /** 长任务实时活动（⑥b/闸/⑦/⑧ 共用，运行过程卡片轮询）。 */
    @GetMapping("/{id}/activity")
    public Result<DraftProgressVO> activity(@PathVariable long id) {
        return Result.success(draftService.progress(id));
    }

    /** run 的 LLM 调用时间线（最新在前；node/tokens/耗时/成败——「到底在干什么」的账本）。 */
    @GetMapping("/{id}/llm-trail")
    public Result<List<LlmTrailVO>> llmTrail(@PathVariable long id) {
        return Result.success(queryService.listLlmTrail(id));
    }

    /** 单次调用全文：发出去的提示词 / 返回正文 / 思考 / 本轮不合规原因。 */
    @GetMapping("/llm-calls/{callId}")
    public Result<LlmCallDetailVO> llmCall(@PathVariable long callId) {
        return Result.success(queryService.getLlmCall(callId));
    }

    /** ⑦写下一章正文（逐章调用断点续跑；全部完成后 run→QC）。 */
    @PostMapping("/{id}/write")
    public Result<WriteVO> write(@PathVariable long id) {
        return Result.success(writeService.writeNext(id));
    }

    /** compiler-v1 正文闭环：一次只推进 DRAFT/FINAL_AUDIT/COMPLETE 中的一个阶段。 */
    @PostMapping("/{id}/pipeline/advance")
    public Result<AutoVO> pipelineAdvance(@PathVariable long id) {
        return Result.success(runOrchestrator.advance(id));
    }

    /** compiler-v1 正文闭环状态：只从持久 stage ledger 读取。 */
    @GetMapping("/{id}/pipeline/status")
    public Result<AutoVO> pipelineStatus(@PathVariable long id) {
        return Result.success(runOrchestrator.status(id));
    }

    /** 取消 compiler-v1 未终态阶段；已写入的正文和 stage 记录保留。 */
    @PostMapping("/{id}/pipeline/cancel")
    public Result<AutoVO> pipelineCancel(@PathVariable long id) {
        return Result.success(runOrchestrator.cancel(id));
    }

    /** ⑦服务端连跑启动（点一次→服务器逐章写到完，关页面不断；同 run 单飞）。 */
    @PostMapping("/{id}/write-loop")
    public Result<WriteLoopVO> writeLoopStart(@PathVariable long id) {
        return Result.success(writeService.startLoop(id));
    }

    /** ⑦服务端连跑停止请求（当前章写完即停）。 */
    @PostMapping("/{id}/write-loop/stop")
    public Result<WriteLoopVO> writeLoopStop(@PathVariable long id) {
        return Result.success(writeService.stopLoop(id));
    }

    /** ⑦服务端连跑状态（running/allDone/已写章数/当前章心跳/lastError——刷新页面也能看）。 */
    @GetMapping("/{id}/write-progress")
    public Result<WriteLoopVO> writeProgress(@PathVariable long id) {
        return Result.success(writeService.loopStatus(id));
    }

    /** ⑧质检（脚本 7 项硬判 + LLM 快检 4 项；PASS→PASSED，否则 REWORK/FAILED）。 */
    @PostMapping("/{id}/qc")
    public Result<QcVO> qc(@PathVariable long id) {
        return Result.success(qcService.qc(id));
    }

    /** 全流程无人值守启动（从当前状态自动推进 ⑥a→⑥b→闸→⑦→⑧ 直到终态；重试按各步默认口径）。
     *  direct=true（直通初稿）：跳过 P6/QC 的 LLM 审稿；确定性审计、计划、预算和脚本问题
     *  降级为报告，不阻断正文产出。 */
    @PostMapping("/{id}/auto")
    public Result<AutoVO> autoStart(@PathVariable long id,
                                    @RequestParam(defaultValue = "false") boolean direct) {
        return Result.success(autoFlowService.start(id, direct));
    }

    /** 全流程停止请求（当前步完成后收尾，断点保留）。 */
    @PostMapping("/{id}/auto/stop")
    public Result<AutoVO> autoStop(@PathVariable long id) {
        return Result.success(autoFlowService.stop(id));
    }

    /** 全流程无条件强停（不等当前步返回：打断主控线程与在飞 LLM 调用，立即返回）。 */
    @PostMapping("/{id}/auto/kill")
    public Result<AutoVO> autoKill(@PathVariable long id) {
        return Result.success(autoFlowService.kill(id));
    }

    /** 全流程状态（stage/steps/lastError——刷新、换页都能看）。 */
    @GetMapping("/{id}/auto-progress")
    public Result<AutoVO> autoProgress(@PathVariable long id) {
        return Result.success(autoFlowService.status(id));
    }

    /** P6 立项闸（⑥b 完成后、⑦ 前；GO 放行写正文，REWORK 回 ⑥b 重做，REJECT 熔断）。 */
    @PostMapping("/{id}/gate")
    public Result<GateVO> gate(@PathVariable long id) {
        return Result.success(gateService.gate(id));
    }
}
