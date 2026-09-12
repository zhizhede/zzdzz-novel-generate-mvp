package com.zzdzz.novelgen.service;

import com.zzdzz.novelgen.llm.LlmNode;
import com.zzdzz.novelgen.llm.LlmPort;
import com.zzdzz.novelgen.dao.SceneDAO;
import org.springframework.stereotype.Service;

import java.util.List;

/** 场景草稿生成：temperature 0.9（要味道），产物直接落库；失败带门禁意见定点重写。 */
@Service
public class SceneService {

    private final LlmPort llm;
    private final SceneDAO sceneDAO;

    public SceneService(LlmPort llm, SceneDAO sceneDAO) {
        this.llm = llm;
        this.sceneDAO = sceneDAO;
    }

    public String generate(long novelId, long chapterId, int chapterNo,
                           ContextPackerService.Pack pack, int sceneNo) {
        LlmPort.ChatResult r = llm.chat(new LlmPort.ChatRequest(
                LlmNode.SCENE_DRAFT, novelId, chapterId,
                List.of(LlmPort.Message.system(pack.system()), LlmPort.Message.user(pack.user())),
                0.9));
        String text = cleanDraft(r.content());
        sceneDAO.saveDraft(chapterId, sceneNo, text);
        return text;
    }

    public String revise(long novelId, long chapterId, long sceneId, int sceneNo,
                         String draft, String gateFeedback, ContextPackerService.Pack pack) {
        LlmPort.ChatResult r = llm.chat(new LlmPort.ChatRequest(
                LlmNode.SCENE_REVISE, novelId, chapterId,
                List.of(LlmPort.Message.system(pack.system()),
                        LlmPort.Message.user(pack.user() + "\n\n【你上一稿】\n" + draft
                                + "\n\n【门禁意见（只改被点名的问题，保持其余原样）】\n" + gateFeedback
                                + "\n\n只输出修订后的完整正文。")),
                0.8));
        String text = cleanDraft(r.content());
        sceneDAO.applyRevise(sceneId, text);
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
