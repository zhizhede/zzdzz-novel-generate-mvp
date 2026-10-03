package com.zzdzz.novelgen.controller;

import com.zzdzz.novelgen.common.web.Result;
import com.zzdzz.novelgen.model.dto.PlanAssetQueryDTO;
import com.zzdzz.novelgen.model.vo.PlanAssetVO;
import com.zzdzz.novelgen.service.PlanAssetService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 规划资产库：大纲 / 卷纲 / 章纲三层统一列表 + 条件查询（只读，页面 /plans）。 */
@RestController
@RequestMapping("/api/plan-assets")
@RequiredArgsConstructor
public class PlanAssetController {

    private final PlanAssetService planAssetService;

    /** 三层列表（筛选条件见 PlanAssetQueryDTO；level 缺省 CHAPTER，全空条件 = 该层全量按书+章号）。 */
    @GetMapping
    public Result<List<PlanAssetVO>> list(PlanAssetQueryDTO condition) {
        return Result.success(planAssetService.query(condition));
    }
}
