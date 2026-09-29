package com.zzdzz.novelgen.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证推理输出的思考/正文拆分（不发起真实调用）：M3 内联 think 块 + DeepSeek 式 reasoning_content 字段 */
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

    /**
     * 契约变更（2026-09-29）：content 为空时**不再**把 reasoning_content 当正文。
     * 旧行为是为了救 M3 的纯思考响应，但对 DeepSeek 这类思考在独立字段的模型，
     * 一旦输出被截断就会把思考写进稿件。空就是空，由调用方按空内容处置。
     */
    @Test
    void content为空时不回退思考字段_正文为空() throws Exception {
        String json = """
        {
          "choices": [{"message": {"content": "", "reasoning_content": "只有思考没有正文"}}]
        }
        """;
        var response = mapper.readTree(json);

        assertThat(MiniMaxClient.extractContent(response)).isEmpty();
        assertThat(MiniMaxClient.extractReasoning(response)).isEqualTo("只有思考没有正文");
    }

    /** 缓存命中 token：MiniMax 报嵌套字段、DeepSeek 报平铺字段，两种都要认（否则命中价恒为 0）。 */
    @Test
    void 缓存命中token两种字段名都认() throws Exception {
        var minimax = mapper.readTree("{\"prompt_tokens_details\": {\"cached_tokens\": 128}}");
        var deepseek = mapper.readTree("{\"prompt_tokens\": 900, \"prompt_cache_hit_tokens\": 640}");
        var neither = mapper.readTree("{\"prompt_tokens\": 900}");

        assertThat(MiniMaxClient.cachedTokensOf(minimax)).isEqualTo(128);
        assertThat(MiniMaxClient.cachedTokensOf(deepseek)).isEqualTo(640);
        assertThat(MiniMaxClient.cachedTokensOf(neither)).isZero();
    }
}
