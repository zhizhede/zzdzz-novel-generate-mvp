package com.zzdzz.novelgen.derive.web;

import com.zzdzz.novelgen.common.web.Result;
import com.zzdzz.novelgen.derive.service.DeriveSampleService;
import com.zzdzz.novelgen.model.dto.derive.RunCreateDTO;
import com.zzdzz.novelgen.model.vo.derive.RunVO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** ⑤约束采样 REST（同步：5.1+5.2 纯脚本 + K≥2 时一次焊接 LLM）。 */
@RestController
@RequestMapping("/api/derive/runs")
@RequiredArgsConstructor
public class DeriveRunController {

    private final DeriveSampleService sampleService;

    @PostMapping
    public Result<RunVO> create(@RequestBody RunCreateDTO dto) {
        return Result.success(sampleService.create(dto));
    }
}
