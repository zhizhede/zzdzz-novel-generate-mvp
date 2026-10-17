package com.zzdzz.novelgen.derive.web;

import com.zzdzz.novelgen.common.web.Result;
import com.zzdzz.novelgen.derive.service.DeriveDraftService;
import com.zzdzz.novelgen.derive.service.DerivePlanService;
import com.zzdzz.novelgen.derive.service.DeriveQcService;
import com.zzdzz.novelgen.derive.service.DeriveQueryService;
import com.zzdzz.novelgen.derive.service.DeriveSampleService;
import com.zzdzz.novelgen.derive.service.DeriveWriteService;
import com.zzdzz.novelgen.model.dto.derive.RunCreateDTO;
import com.zzdzz.novelgen.model.dto.derive.RunQueryDTO;
import com.zzdzz.novelgen.model.vo.derive.BeatVO;
import com.zzdzz.novelgen.model.vo.derive.DraftVO;
import com.zzdzz.novelgen.model.vo.derive.PlanVO;
import com.zzdzz.novelgen.model.vo.derive.QcReportVO;
import com.zzdzz.novelgen.model.vo.derive.QcVO;
import com.zzdzz.novelgen.model.vo.derive.RunChapterVO;
import com.zzdzz.novelgen.model.vo.derive.RunDetailVO;
import com.zzdzz.novelgen.model.vo.derive.RunListItemVO;
import com.zzdzz.novelgen.model.vo.derive.RunVO;
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
    private final DeriveDraftService draftService;
    private final DeriveWriteService writeService;
    private final DeriveQcService qcService;
    private final DeriveQueryService queryService;

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

    /** ⑥b生成具体稿（逐拍填实+章纲+成品书登记，run→DRAFTING）。 */
    @PostMapping("/{id}/draft")
    public Result<DraftVO> draft(@PathVariable long id) {
        return Result.success(draftService.draft(id));
    }

    /** ⑦写下一章正文（逐章调用断点续跑；全部完成后 run→QC）。 */
    @PostMapping("/{id}/write")
    public Result<WriteVO> write(@PathVariable long id) {
        return Result.success(writeService.writeNext(id));
    }

    /** ⑧质检（脚本 7 项硬判 + LLM 快检 4 项；PASS→PASSED，否则 REWORK/FAILED）。 */
    @PostMapping("/{id}/qc")
    public Result<QcVO> qc(@PathVariable long id) {
        return Result.success(qcService.qc(id));
    }
}
