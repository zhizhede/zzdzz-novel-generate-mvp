package com.zzdzz.novelgen.service;

import lombok.RequiredArgsConstructor;
import com.zzdzz.novelgen.llm.LlmTemps;
import com.zzdzz.novelgen.llm.LlmNode;
import com.zzdzz.novelgen.llm.LlmPort;
import com.zzdzz.novelgen.service.data.SceneDataService;
import org.springframework.stereotype.Service;

import java.util.List;

/** 场景草稿生成：temperature 0.9（要味道），产物直接落库；失败带门禁意见定点重写。 */
@Service
@RequiredArgsConstructor
public class SceneService {

    private final LlmPort llm;
    private final SceneDataService sceneData;
    private final PromptTemplateService promptTemplates;


    public String generate(long novelId, long chapterId, int chapterNo,
                           ContextPackerService.Pack pack, int sceneNo) {
        return generate(novelId, chapterId, chapterNo, pack, sceneNo, null);
    }

    /** onDelta 非空走流式（LLM_PORT.chatStream，长文本实时增量），空走阻塞 chat——开关由调用方裁决。 */
    public String generate(long novelId, long chapterId, int chapterNo,
                           ContextPackerService.Pack pack, int sceneNo, LlmPort.StreamDelta onDelta) {
        LlmPort.ChatRequest req = new LlmPort.ChatRequest(
                LlmNode.SCENE_DRAFT, novelId, chapterId,
                List.of(LlmPort.Message.system(pack.system()), LlmPort.Message.user(pack.user())),
                LlmTemps.SCENE_DRAFT);
        LlmPort.ChatResult r = onDelta == null ? llm.chat(req) : llm.chatStream(req, onDelta);
        String text = cleanDraft(r.content());
        sceneData.saveDraft(chapterId, sceneNo, text);
        return text;
    }

    public String revise(long novelId, long chapterId, long sceneId, int sceneNo,
                         String draft, String gateFeedback, ContextPackerService.Pack pack) {
        return revise(novelId, chapterId, sceneId, sceneNo, draft, gateFeedback, pack, null);
    }

    /** onDelta 非空走流式（门禁重写同样实时可见），空走阻塞 chat。 */
    public String revise(long novelId, long chapterId, long sceneId, int sceneNo,
                         String draft, String gateFeedback, ContextPackerService.Pack pack,
                         LlmPort.StreamDelta onDelta) {
        // 门禁重写 user = 场景包 + 上一稿 + 门禁意见（scene_revise/user，{key} 拼接段，库值优先）
        String user = promptTemplates.getSection(LlmNode.SCENE_REVISE, "user",
                java.util.Map.of("scene_user", pack.user(),
                        "draft", draft == null ? "" : draft,
                        "gate_feedback", gateFeedback == null ? "" : gateFeedback));
        LlmPort.ChatRequest req = new LlmPort.ChatRequest(
                LlmNode.SCENE_REVISE, novelId, chapterId,
                List.of(LlmPort.Message.system(pack.system()),
                        LlmPort.Message.user(user)),
                LlmTemps.SCENE_REVISE);
        LlmPort.ChatResult r = onDelta == null ? llm.chat(req) : llm.chatStream(req, onDelta);
        String text = cleanDraft(r.content());
        sceneData.applyRevise(sceneId, text);
        return text;
    }

    /** 模型偶发把「### 1.3 场景标题」这类 markdown 结构写进正文，入库与门禁前剥掉。 */
    static String cleanDraft(String raw) {
        if (raw == null || raw.isBlank()) return raw;
        StringBuilder sb = new StringBuilder();
        for (String line : raw.split("\n", -1)) {
            if (line.strip().startsWith("#")) continue;
            sb.append(line).append('\n');
        }
        return sb.toString().strip();
    }
}
