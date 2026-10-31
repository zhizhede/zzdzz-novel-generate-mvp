package com.zzdzz.novelgen.derive.genre;

import com.fasterxml.jackson.databind.JsonNode;
import com.zzdzz.novelgen.derive.plan.PlanContracts;

/** 类型合同 JSON 编解码器；payload 由 derive_genre_contract 持久化。 */
public interface GenreContractCodec<C extends GenreContract> {

    String genre();

    JsonNode encode(C contract);

    C decode(JsonNode payload, PlanContracts.ScopeContract scope);
}
