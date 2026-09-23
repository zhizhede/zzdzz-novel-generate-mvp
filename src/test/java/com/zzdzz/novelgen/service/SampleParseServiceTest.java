package com.zzdzz.novelgen.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 样本解析纯函数基线：章切分识别/回退/卷界标 + 超长分段。 */
class SampleParseServiceTest {

    private static String chapter(String title, String body) {
        return title + "\n" + body + "\n";
    }

    @Test
    void splitsByChapterMarkers() {
        StringBuilder sb = new StringBuilder();
        sb.append(chapter("第一章 茅场晶彦", "正文一".repeat(200)));
        sb.append(chapter("第2章 开始", "正文二".repeat(200)));
        sb.append(chapter("第十二章 汉字数词", "正文三".repeat(200)));
        sb.append("尾声\n最终的话\n".repeat(1));
        List<SampleParseService.ChapterSeg> segs = SampleParseService.splitChapters(sb.toString());
        assertThat(segs).hasSize(4);
        assertThat(segs.get(0).title()).isEqualTo("第一章 茅场晶彦");
        assertThat(segs.get(1).title()).isEqualTo("第2章 开始");
        assertThat(segs.get(2).title()).isEqualTo("第十二章 汉字数词");
        assertThat(segs.get(3).title()).isEqualTo("尾声");
        assertThat(segs).allMatch(s -> !s.pseudo());
    }

    @Test
    void volumeLinesMarkVolumeBoundaryOnly() {
        StringBuilder sb = new StringBuilder();
        sb.append(chapter("第一章 开端", "正文".repeat(300)));
        sb.append("第二卷 艾因葛朗特攻略战\n");
        sb.append(chapter("第二章 进入第二卷", "正文".repeat(300)));
        sb.append(chapter("第三章 卷内继续", "正文".repeat(300)));
        List<SampleParseService.ChapterSeg> segs = SampleParseService.splitChapters(sb.toString());
        assertThat(segs).hasSize(3);
        assertThat(segs.get(0).volumeSeq()).isZero();
        assertThat(segs.get(1).volumeSeq()).isEqualTo(1);
        assertThat(segs.get(2).volumeSeq()).isEqualTo(1);
    }

    @Test
    void fallsBackToPseudoChaptersWhenNoMarkers() {
        String text = "没有章标题的一整段正文。".repeat(1500);
        List<SampleParseService.ChapterSeg> segs = SampleParseService.splitChapters(text);
        assertThat(segs.size()).isGreaterThan(3);
        assertThat(segs).allMatch(SampleParseService.ChapterSeg::pseudo);
        assertThat(segs).allMatch(s -> s.title() == null);
        int total = segs.stream().mapToInt(s -> s.text().length()).sum();
        assertThat(total).isGreaterThanOrEqualTo(text.length() - 5);
    }

    @Test
    void sparseMarkersFallBackToPseudo() {
        // 只有一个章标记且正文占大头 → 章覆盖不足，回退伪章
        String text = chapter("第一章 孤章", "大量正文".repeat(1000));
        List<SampleParseService.ChapterSeg> segs = SampleParseService.splitChapters(text);
        assertThat(segs).allMatch(SampleParseService.ChapterSeg::pseudo);
    }

    @Test
    void sentenceStartingWithDiIsNotChapter() {
        String text = chapter("第一章 标题", "第二天他去第三章的地图寻找线索。".repeat(100));
        List<SampleParseService.ChapterSeg> segs = SampleParseService.splitChapters(text);
        // 单章 + 正文含"第三章"字样：正文行以"第二天"开头不匹配章标题；只 1 章 <3 → 回退伪章（伪章不误切）
        assertThat(segs).allMatch(SampleParseService.ChapterSeg::pseudo);
    }

    @Test
    void splitBySizeCutsAtNewlineBoundary() {
        String text = "A行\n".repeat(6000) + "B尾巴";
        List<String> parts = SampleParseService.splitBySize(text, 10000);
        assertThat(parts.size()).isGreaterThan(1);
        for (String p : parts) {
            assertThat(p.length()).isLessThanOrEqualTo(10000);
        }
        assertThat(String.join("", parts)).isEqualTo(text);
        assertThat(parts).allMatch(p -> p.endsWith("\n") || p.endsWith("B尾巴"));
    }

    @Test
    void splitBySizeShortTextSinglePart() {
        assertThat(SampleParseService.splitBySize("短文本", 10000)).containsExactly("短文本");
    }
}
