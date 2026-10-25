package com.zzdzz.novelgen.derive.service;

import com.zzdzz.novelgen.derive.contract.ScaffoldContract;
import com.zzdzz.novelgen.model.entity.derive.RunDO;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeriveScaffoldServiceTest {

    private static RunDO run(int min, int max) {
        RunDO run = new RunDO();
        run.setParamWordsMin(min);
        run.setParamWordsMax(max);
        return run;
    }

    @Test
    void scaffoldUsesSystemChapterCountAndStableSeqs() {
        ScaffoldContract scaffold = new DeriveScaffoldService()
                .buildScaffold(run(12000, 18000), 18, 1);

        assertEquals(18, scaffold.chapterCount());
        assertEquals(18, scaffold.beats().size());
        assertEquals(1, scaffold.beats().get(0).tempSeq());
        assertEquals(18, scaffold.beats().get(17).tempSeq());
        assertTrue(scaffold.facts().stream().allMatch(f -> f.factKey().startsWith("F-")));
    }

    @Test
    void scaffoldSplitsVolumesWithoutOverlap() {
        ScaffoldContract scaffold = new DeriveScaffoldService()
                .buildScaffold(run(24000, 30000), 24, 2);

        List<ScaffoldContract.VolumeRange> ranges = scaffold.volumeRanges();
        assertEquals(2, ranges.size());
        assertEquals(1, ranges.get(0).fromChapter());
        assertEquals(ranges.get(0).toChapter() + 1, ranges.get(1).fromChapter());
        assertEquals(scaffold.chapterCount(), ranges.get(1).toChapter());
    }

    @Test
    void scaffoldCanMergeMultipleBeatsIntoSystemChapterCount() {
        ScaffoldContract scaffold = new DeriveScaffoldService()
                .buildScaffold(run(12000, 18000), 26, 1, 18);

        assertEquals(18, scaffold.chapterCount());
        assertEquals(26, scaffold.beats().size());
        assertEquals(1, scaffold.beats().get(0).chapterNo());
        assertEquals(18, scaffold.beats().get(25).chapterNo());
    }}