package com.zzdzz.novelgen.derive.service;

import com.zzdzz.novelgen.model.entity.derive.BeatInstanceDO;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 输出层章契约回归：每拍必须有正文原句证据，闭合末章必须交收束表。 */
class DeriveWriteContractTest {

    private static BeatInstanceDO beat(int seq) {
        BeatInstanceDO b = new BeatInstanceDO();
        b.setSeq(seq);
        return b;
    }

    private static String output(String contract) {
        return "艾琳打开字条，看见上面写着莉安的名字。\n"
                + "<!-- 本章18字，挂拍：1 -->\n"
                + contract;
    }

    @Test
    void contractEvidenceMustComeFromBody() {
        String ok = output("<!-- 拍兑现契约：{\"beats\":[{\"seq\":1,\"evidence\":\"艾琳打开字条\"}],"
                + "\"open_threads\":[],\"closed_threads\":[]} -->");
        assertNull(DeriveWriteService.validate(ok, 1, 100, "艾琳", false,
                List.of(beat(1)), false));
    }

    @Test
    void missingContractIsRejectedWhenBeatsExist() {
        String raw = "艾琳打开字条，看见上面写着莉安的名字。\n<!-- 本章18字，挂拍：1 -->";
        String issue = DeriveWriteService.validate(raw, 1, 100, "艾琳", false,
                List.of(beat(1)), false);
        assertNotNull(issue);
        assertTrue(issue.contains("拍兑现契约"));
    }

    @Test
    void contractBeatSetMustMatchPlannedBeats() {
        String bad = output("<!-- 拍兑现契约：{\"beats\":[{\"seq\":1,\"evidence\":\"艾琳打开字条\"},"
                + "{\"seq\":2,\"evidence\":\"莉安\"}],\"open_threads\":[],\"closed_threads\":[]} -->");
        String issue = DeriveWriteService.validate(bad, 1, 100, "艾琳", false,
                List.of(beat(1)), false);
        assertNotNull(issue);
        assertTrue(issue.contains("非法或重复 seq: 2"));
    }

    @Test
    void closedFinalChapterRequiresClosureTable() {
        String raw = output("<!-- 拍兑现契约：{\"beats\":[{\"seq\":1,\"evidence\":\"艾琳打开字条\"}],"
                + "\"open_threads\":[],\"closed_threads\":[]} -->");
        String issue = DeriveWriteService.validate(raw, 1, 100, "艾琳", false,
                List.of(beat(1)), true);
        assertNotNull(issue);
        assertTrue(issue.contains("closure.resolved_threads"));
    }
}
