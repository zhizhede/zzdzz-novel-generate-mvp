package com.zzdzz.novelgen.derive.genre;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzdzz.novelgen.derive.plan.PlanContracts;
import org.springframework.stereotype.Component;

/** 言情 codec：只编码/解码 RomanceContract，禁止解析成 StoryContract。 */
@Component
public class RomanceCodec implements GenreContractCodec<RomanceContract> {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public String genre() {
        return "ROMANCE";
    }

    @Override
    public JsonNode encode(RomanceContract contract) {
        if (contract == null) {
            throw new IllegalArgumentException("RomanceContract 不能为空");
        }
        return MAPPER.valueToTree(contract);
    }

    @Override
    public RomanceContract decode(JsonNode payload, PlanContracts.ScopeContract scope) {
        if (payload == null || !payload.isObject()) {
            throw new IllegalArgumentException("言情合同 payload 必须是 JSON 对象");
        }
        try {
            RomanceContract contract = MAPPER.treeToValue(payload, RomanceContract.class);
            int version = contract.version() > 0 ? contract.version() : 1;
            return new RomanceContract(version, scope, contract.cast(),
                    contract.primaryRelationship(), contract.stages(), contract.forbidden());
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("言情合同解析失败：" + e.getMessage(), e);
        }
    }
}
