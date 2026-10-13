package com.zzdzz.novelgen.derive.web;

import com.zzdzz.novelgen.common.web.Result;
import com.zzdzz.novelgen.derive.service.DerivePlanService;
import com.zzdzz.novelgen.derive.service.DeriveSampleService;
import com.zzdzz.novelgen.model.dto.derive.RunCreateDTO;
import com.zzdzz.novelgen.model.vo.derive.PlanVO;
import com.zzdzz.novelgen.model.vo.derive.RunVO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** ⑤约束采样 + ⑥a章节计划 REST（同步；⑥a 含 LLM 分章与脚本校验重试）。 */
@RestController
@RequestMapping("/api/derive/runs")
@RequiredArgsConstructor
public class DeriveRunController {

    private final DeriveSampleService sampleService;
    private final DerivePlanService planService;

    @PostMapping
    public Result<RunVO> create(@RequestBody RunCreateDTO dto) {
        return Result.success(sampleService.create(dto));
    }

    /** ⑥a章节计划（run 必须处于 PLANNING 且未计划过）。 */
    @PostMapping("/{id}/plan")
    public Result<PlanVO> plan(@PathVariable long id) {
        return Result.success(planService.plan(id));
    }
}
