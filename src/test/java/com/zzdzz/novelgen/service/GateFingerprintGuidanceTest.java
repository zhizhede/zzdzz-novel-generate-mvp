package com.zzdzz.novelgen.service;

import com.zzdzz.novelgen.service.data.ChapterDataService;
import com.zzdzz.novelgen.service.data.GateReportDataService;
import com.zzdzz.novelgen.service.data.StylePackDataService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 指纹量化目标转写作口径：与 fingerprintChecks 同一套判定数学——
 * abs_max 优先、稀疏指标（基线<3）只设上限、其余 ±tolerance；无指纹返回 null。
 */
class GateFingerprintGuidanceTest {

    private StylePackDataService stylePackData;
    private GateService service;

    private static final String FINGERPRINT = """
            {"baseline": {
              "line_avg_len": {"value": 100.04, "abs_max": 179.18, "tolerance": 0.32},
              "dash_per1k": {"value": 0.73, "abs_max": 3.63, "tolerance": 1.5},
              "ellipsis_per1k": {"value": 0.0, "abs_max": 0.15, "tolerance": 0.15},
              "exclam_per1k": {"value": 3.64, "abs_max": 7.06, "tolerance": 0.76},
              "tic_haiyou_per1k": {"value": 0.0, "abs_max": 0.69, "tolerance": 0.37}
            }}""";

    @BeforeEach
    void setUp() {
        stylePackData = mock(StylePackDataService.class);
        service = new GateService(stylePackData, mock(GateReportDataService.class),
                mock(ChapterDataService.class), mock(TuningService.class));
    }

    @Test
    void guidanceMirrorsGateMath() {
        when(stylePackData.findFingerprintByNovel(12L)).thenReturn(FINGERPRINT);
        String g = service.fingerprintGuidance(12L);
        // line_avg_len：基线 100、tolerance 0.32 → 下界 68；abs_max 179.18 优先 → 上界 179
        assertThat(g).contains("每行平均长度（字） 68–179（目标 100）");
        // dash：基线 0.73 < 3 稀疏 → 只设上限，abs_max 3.63
        assertThat(g).contains("破折号每千字 ≤3.6");
        // ellipsis：上限 0.15 → 保留两位小数（显示比门禁更严是安全方向）
        assertThat(g).contains("省略号每千字 ≤0.15");
        // exclam：基线 3.64 ≥ 3 非稀疏 → 双边 3.64×(1-0.76)=0.87 起（生成稿 0 会被打回的门禁口径一致）
        assertThat(g).contains("感叹号每千字 0.87–7.1（目标 3.6）");
        // 行均基线 100 ≥ 40 → 长句取向指令；省略号上限 0.15 → 禁用；口头禅上限来自 abs_max
        assertThat(g).contains("严禁一句一段的碎句排版");
        assertThat(g).contains("- 禁用省略号");
        assertThat(g).contains("口头禅「haiyou」能删则删（每千字 ≤0.69）");
    }

    @Test
    void noFingerprintReturnsNull() {
        when(stylePackData.findFingerprintByNovel(1L)).thenReturn(null);
        assertThat(service.fingerprintGuidance(1L)).isNull();
    }

    @Test
    void brokenFingerprintReturnsNull() {
        when(stylePackData.findFingerprintByNovel(1L)).thenReturn("not-json");
        assertThat(service.fingerprintGuidance(1L)).isNull();
    }
}
