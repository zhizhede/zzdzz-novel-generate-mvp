package com.zzdzz.novelgen.runner;

import com.zzdzz.novelgen.service.ChapterPipelineService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** 自动连跑入口：参数解析与结果日志，编排逻辑在 ChapterPipelineService。 */
@Component
@ConditionalOnProperty(name = "pipeline.enabled", havingValue = "true")
public class AutoRunRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AutoRunRunner.class);

    private final ChapterPipelineService pipeline;
    private final String novelTitle;
    private final int from;
    private final int to;

    public AutoRunRunner(ChapterPipelineService pipeline,
                         @Value("${pipeline.novel:夜班守则}") String novelTitle,
                         @Value("${pipeline.from:1}") int from,
                         @Value("${pipeline.to:2}") int to) {
        this.pipeline = pipeline;
        this.novelTitle = novelTitle;
        this.from = from;
        this.to = to;
    }

    @Override
    public void run(ApplicationArguments args) {
        pipeline.runChapters(novelTitle, from, to);
    }
}
