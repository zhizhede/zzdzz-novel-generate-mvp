package com.zzdzz.novelgen.common.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Result 通道分工与序列化形状基线：success 响应 JSON 必须与旧 ok() 形状逐字段一致（前端零感知）。 */
class ResultTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void successShapeUnchanged() throws Exception {
        JsonNode n = mapper.readTree(mapper.writeValueAsString(Result.success("x")));
        assertThat(n.size()).isEqualTo(3);
        assertThat(n.get("code").asText()).isEqualTo("00000");
        assertThat(n.get("message").asText()).isEqualTo("ok");
        assertThat(n.get("data").asText()).isEqualTo("x");
        assertThat(n.has("detail")).isFalse();
    }

    @Test
    void voidSuccessKeepsNullDataNoDetail() throws Exception {
        JsonNode n = mapper.readTree(mapper.writeValueAsString(Result.success()));
        assertThat(n.size()).isEqualTo(3);
        assertThat(n.hasNonNull("data")).isFalse();
        assertThat(n.has("detail")).isFalse();
    }

    @Test
    void failWithDetailSerializesIt() throws Exception {
        Result<Void> r = Result.fail(ErrorCode.STATE_CONFLICT, "任务状态为 DONE，不能取消",
                Map.of("actualStatus", "DONE"));
        JsonNode n = mapper.readTree(mapper.writeValueAsString(r));
        assertThat(n.get("code").asText()).isEqualTo("A0006");
        assertThat(n.get("detail").get("actualStatus").asText()).isEqualTo("DONE");
        assertThat(n.hasNonNull("data")).isFalse();
    }

    @Test
    void failWithoutDetailOmitsField() throws Exception {
        JsonNode n = mapper.readTree(mapper.writeValueAsString(
                Result.fail(ErrorCode.PARAM_ERROR, "参数不合法")));
        assertThat(n.has("detail")).isFalse();
    }

    /** 修法一：fail 泛型化后可直接出现在任意 Result&lt;T&gt; 返回位置（编译期即本用例的断言）。 */
    @Test
    void genericFailFitsAnyResultContext() {
        Result<String> typed = Result.fail(ErrorCode.NOT_FOUND, "章不存在");
        assertThat(typed.data()).isNull();
        assertThat(typed.detail()).isNull();
    }
}
