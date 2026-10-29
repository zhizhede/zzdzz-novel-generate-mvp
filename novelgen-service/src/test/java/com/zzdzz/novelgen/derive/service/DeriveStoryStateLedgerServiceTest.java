package com.zzdzz.novelgen.derive.service;

import com.zzdzz.novelgen.model.entity.derive.DeriveCharacterProfileDO;
import com.zzdzz.novelgen.model.entity.derive.DerivePressureEventDO;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;

class DeriveStoryStateLedgerServiceTest {
    @Test void replaysArcState() {
        var profile = new DeriveCharacterProfileDO();
        profile.setCharacterKey("C1");
        profile.setArcState("START");
        var event = new DerivePressureEventDO();
        event.setEventKey("E");
        event.setChapterNo(1);
        event.setSeq(1);
        event.setCharacterChanges("[{\"characterKey\":\"C1\",\"arcState\":\"PAYOFF\"}]");
        var ledger = new DeriveStoryStateLedgerService().simulate(List.of(profile), List.of(), List.of(), List.of(), List.of(event));
        assertEquals("PAYOFF", ledger.after().get(0).characterArc().get("C1"));
    }
}