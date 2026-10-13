package com.zzdzz.novelgen.derive.web;

import com.zzdzz.novelgen.common.web.Result;
import com.zzdzz.novelgen.derive.service.DeriveDraftService;
import com.zzdzz.novelgen.derive.service.DerivePlanService;
import com.zzdzz.novelgen.derive.service.DeriveQcService;
import com.zzdzz.novelgen.derive.service.DeriveSampleService;
import com.zzdzz.novelgen.derive.service.DeriveWriteService;
import com.zzdzz.novelgen.model.dto.derive.RunCreateDTO;
import com.zzdzz.novelgen.model.vo.derive.DraftVO;
import com.zzdzz.novelgen.model.vo.derive.PlanVO;
import com.zzdzz.novelgen.model.vo.derive.QcVO;
import com.zzdzz.novelgen.model.vo.derive.RunVO;
import com.zzdzz.novelgen.model.vo.derive.WriteVO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** ⑤采样 + ⑥a计划 + ⑥b具体稿 REST（同步；LLM 节点耗时长，客户端留足超时）。 */
@RestController
@RequestMapping("/api/derive/runs")
@RequiredArgsConstructor
public class DeriveRunController {

    private final DeriveSampleService sampleService;
    private final DerivePlanService planService;
    private final DeriveDraftService draftService;
    private final DeriveWriteService writeService;
    private final DeriveQcService qcService;

    @PostMapping
    public Result<RunVO> create(@RequestBody RunCreateDTO dto) {
        return Result.success(sampleService.create(dto));
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
