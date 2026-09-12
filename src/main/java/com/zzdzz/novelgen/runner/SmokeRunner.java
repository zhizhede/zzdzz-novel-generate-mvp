package com.zzdzz.novelgen.runner;

import com.zzdzz.novelgen.llm.LlmNode;
import com.zzdzz.novelgen.llm.LlmPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * M0 冒烟：读取 docs/style 下的规则与范例（私有资产，不入库不入源码），
 * 真实调用 MiniMax 生成一段手搓风格续写，打印正文与 token 用量。
 * 运行：--spring.profiles.active=local --spring.main.web-application-type=none --smoke.enabled=true
 */
@Component
@ConditionalOnProperty(name = "smoke.enabled", havingValue = "true")
public class SmokeRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SmokeRunner.class);

    private final LlmPort llm;
    private final String styleDir;

    public SmokeRunner(LlmPort llm, @Value("${novelgen.style-dir}") String styleDir) {
        this.llm = llm;
        this.styleDir = styleDir;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        String rules = extractRules();
        String exemplar = extractExemplar();
        log.info("风格规则 {} 字，范例 E01 {} 字", rules.length(), exemplar.length());

        String system = rules + "\n\n【风格范例（逐字原文，严格模仿其分行节奏与口吻）】\n" + exemplar;
        String user = "任务：续写《人类、法师、地下城》。场景：早饭后，塞拉斯和坎德尔一起出门前往冒险家协会，"
                + "路上坎德尔提到最近向导委托变多，感觉魔物又要溢出。写到协会门口为止。"
                + "要求 300–500 字，只输出正文，不要任何解释。";

        LlmPort.ChatResult result = llm.chat(new LlmPort.ChatRequest(
                LlmNode.SMOKE, null, null,
                List.of(LlmPort.Message.system(system), LlmPort.Message.user(user)),
                0.9));

        String content = result.content();
        System.out.println("\n================ AI 思考过程（think，与正文分区展示）================");
        System.out.println(result.reasoning() == null ? "（无思考输出）" : result.reasoning());
        System.out.println("\n================ 正文 ================");
        System.out.println(content);
        System.out.println("================ 用量与风格速检 ================");
        System.out.printf("tokens: prompt=%d completion=%d total=%d, llm_call_log id=%d%n",
                result.usage().promptTokens(), result.usage().completionTokens(),
                result.usage().totalTokens(), result.callLogId());
        System.out.printf("速检: 行数=%d, 行均字数=%.1f, 顿号=%d, 「=%d, 感叹号=%d%n",
                content.lines().count(),
                content.lines().mapToInt(String::length).average().orElse(0),
                count(content, "、"), count(content, "「"), count(content, "！"));
    }

    /** 取蒸馏报告 §六 prompt 片段（第一个含【文风硬规则】的代码块） */
    private String extractRules() throws Exception {
        String text = Files.readString(Path.of(styleDir, "写手风格蒸馏.md"));
        int marker = text.indexOf("【文风硬规则】");
        int start = text.lastIndexOf("```", marker);
        int end = text.indexOf("```", marker);
        if (marker < 0 || start < 0 || end < 0) {
            throw new IllegalStateException("蒸馏报告中未找到硬规则代码块");
        }
        return text.substring(start + 3, end).strip();
    }

    /** 取 exemplars.md 的 E01 范例段（### E01 到 ### E02 之间） */
    private String extractExemplar() throws Exception {
        String text = Files.readString(Path.of(styleDir, "exemplars.md"));
        int start = text.indexOf("### E01");
        int end = text.indexOf("### E02");
        if (start < 0 || end < 0) {
            throw new IllegalStateException("exemplars.md 中未找到 E01/E02 分节");
        }
        return text.substring(start, end).strip();
    }

    private int count(String text, String needle) {
        int n = 0, idx = 0;
        while ((idx = text.indexOf(needle, idx)) >= 0) { n++; idx += needle.length(); }
        return n;
    }
}
