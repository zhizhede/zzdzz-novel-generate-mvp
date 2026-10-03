package com.zzdzz.novelgen.controller;

import com.zzdzz.novelgen.common.web.Result;
import com.zzdzz.novelgen.model.dto.StyleFingerprintQueryDTO;
import com.zzdzz.novelgen.model.vo.StyleFingerprintVO;
import com.zzdzz.novelgen.service.StyleFingerprintService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 文风指纹库：三来源（导入样本 / 品类预设 / 书籍风格包）统一列表 + 条件查询（只读）。 */
@RestController
@RequestMapping("/api/style-fingerprints")
@RequiredArgsConstructor
public class StyleFingerprintController {

    private final StyleFingerprintService styleFingerprintService;

    /** 指纹列表（筛选条件见 StyleFingerprintQueryDTO；全空 = 全量按提取时间倒序）。 */
    @GetMapping
    public Result<List<StyleFingerprintVO>> list(StyleFingerprintQueryDTO condition) {
        return Result.success(styleFingerprintService.query(condition));
    }
}
