package com.zzdzz.novelgen.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证 MiniMax-M3 推理输出的 think 块与正文拆分（不发起真实调用） */
class MiniMaxClientExtractTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void content内嵌think块时正文与思考分开提取() throws Exception {
        String json = """
        {
          "choices": [{
            "message": {
              "content": "<think>先想想妹妹是谁。坎德尔是红发，对上了。</think>「走吧」塞拉斯站起来。"
            }
          }],
          "usage": {"prompt_tokens": 100, "completion_tokens": 200, "total_tokens": 300}
        }
        """;
        var response = mapper.readTree(json);

        assertThat(MiniMaxClient.extractContent(response)).isEqualTo("「走吧」塞拉斯站起来。");
        assertThat(MiniMaxClient.extractReasoning(response)).isEqualTo("先想想妹妹是谁。坎德尔是红发，对上了。");
    }

    @Test
    void 无think块时正文原样_思考为null() throws Exception {
        String json = """
        {
          "choices": [{"message": {"content": "「早」塞拉斯打着哈欠。"}}],
          "usage": {"prompt_tokens": 10, "completion_tokens": 5, "total_tokens": 15}
        }
        """;
        var response = mapper.readTree(json);

        assertThat(MiniMaxClient.extractContent(response)).isEqualTo("「早」塞拉斯打着哈欠。");
        assertThat(MiniMaxClient.extractReasoning(response)).isNull();
    }

    @Test
    void content为空时回退reasoning_content字段() throws Exception {
        String json = """
        {
          "choices": [{"message": {"content": "", "reasoning_content": "只有思考没有正文"}}]
        }
        """;
        var response = mapper.readTree(json);

        assertThat(MiniMaxClient.extractContent(response)).isEqualTo("只有思考没有正文");
    }
}
