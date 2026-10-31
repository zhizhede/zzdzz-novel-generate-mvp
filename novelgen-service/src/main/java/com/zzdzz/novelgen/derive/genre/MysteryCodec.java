package com.zzdzz.novelgen.derive.genre;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzdzz.novelgen.derive.contract.StoryContract;
import com.zzdzz.novelgen.derive.contract.StoryContractParser;
import com.zzdzz.novelgen.derive.plan.PlanContracts;
import org.springframework.stereotype.Component;

/** 悬疑 codec：兼容旧 StoryContract JSON 形状。 */
@Component
public class MysteryCodec implements GenreContractCodec<MysteryContract> {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public String genre() {
        return "MYSTERY";
    }

    @Override
    public JsonNode encode(MysteryContract contract) {
        if (contract == null || contract.story() == null) {
            throw new IllegalArgumentException("MysteryContract.story 不能为空");
        }
        return MAPPER.valueToTree(contract.story());
    }

    @Override
    public MysteryContract decode(JsonNode payload, PlanContracts.ScopeContract scope) {
        if (payload == null || !payload.isObject()) {
            throw new IllegalArgumentException("悬疑合同 payload 必须是 JSON 对象");
        }
        return new MysteryContract(StoryContractParser.parse(payload, scope));
    }
}
