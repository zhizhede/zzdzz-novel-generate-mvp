package com.zzdzz.novelgen.derive.story;

import com.zzdzz.novelgen.derive.contract.StoryContract;
import com.zzdzz.novelgen.model.entity.derive.RunDO;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 完整 StoryKernel 装配器：只装配数据库合同与证据，不调用 LLM、不做业务裁决。
 */
public class StoryKernelAssembler {

    /** 装配完整 kernel；证据索引缺失时使用空索引，旧 run 仍可读取。 */
    public StoryKernel assemble(RunDO run, StoryContract contract,
                                List<?> schedule,
                                List<?> volumes,
                                List<?> characters,
                                List<StoryThread> threads,
                                List<StoryObligation> obligations,
                                StoryEvidenceIndex evidenceIndex) {
        long runId = run == null || run.getId() == null ? 0L : run.getId();
        int version = contract == null ? 1 : contract.version();
        String status = run == null || run.getGenre() == null || run.getGenre().isBlank()
                ? "LEGACY" : "ACTIVE";
        List<StoryEntity> entities = new ArrayList<>();
        List<StoryConstraint> constraints = new ArrayList<>();
        if (contract != null) {
            for (StoryContract.CharacterSlot slot : contract.cast()) {
                Map<String, Object> attributes = new LinkedHashMap<>();
                attributes.put("role", slot.role());
                attributes.put("gender", slot.gender());
                attributes.put("identity", slot.identity());
                entities.add(new StoryEntity(slot.key(), "CHARACTER", slot.name(), attributes, 1));
            }
            int chapterCount = contract.scope() == null ? 0 : contract.scope().chapterCount();
            int index = 0;
            for (String forbidden : contract.forbidden()) {
                index++;
                constraints.add(new StoryConstraint("FORBIDDEN:" + index, "BOOK", 1,
                        chapterCount > 0 ? chapterCount : null, "FORBIDDEN", forbidden,
                        forbidden));
            }
        }
        StoryEvidenceIndex evidence = evidenceIndex == null
                ? StoryEvidenceIndex.builder().build() : evidenceIndex;
        List<StoryState> states = states(evidence);
        List<StoryEvent> events = new ArrayList<>();
        evidence.eventsByChapter().values().forEach(events::addAll);
        List<StoryClosure> closures = closures(obligations);
        return new StoryKernel(runId, version, status,
                contract == null ? null : contract.scope(), entities, List.of(), states,
                threads == null ? List.of() : threads, events,
                obligations == null ? List.of() : obligations, constraints, closures, evidence);
    }

    /** 兼容直接传证据列表的装配入口。 */
    public StoryKernel assemble(RunDO run, StoryContract contract,
                                List<?> schedule, List<?> volumes, List<?> characters,
                                List<StoryThread> threads, List<StoryObligation> obligations,
                                List<StoryObligationEvidence> evidence) {
        StoryEvidenceIndex.Builder builder = StoryEvidenceIndex.builder();
        if (evidence != null) {
            evidence.forEach(builder::addObligationEvidence);
        }
        return assemble(run, contract, schedule, volumes, characters, threads, obligations,
                builder.build());
    }

    private static List<StoryState> states(StoryEvidenceIndex evidence) {
        List<StoryState> states = new ArrayList<>();
        evidence.eventsByChapter().values().stream().flatMap(List::stream).forEach(event -> {
            for (Map<String, Object> effect : event.stateEffects()) {
                String entityKey = text(effect.get("entityKey"));
                String kind = text(effect.get("kind"));
                String value = text(effect.get("value"));
                if (!entityKey.isBlank() && !kind.isBlank() && !value.isBlank()) {
                    states.add(new StoryState(entityKey, kind, value, event.chapterNo(),
                            event.eventKey()));
                }
            }
        });
        return List.copyOf(states);
    }

    private static List<StoryClosure> closures(List<StoryObligation> obligations) {
        if (obligations == null) {
            return List.of();
        }
        List<StoryClosure> out = new ArrayList<>();
        for (StoryObligation obligation : obligations) {
            if ("LOCAL_CLOSURE".equals(obligation.kind())
                    || "FINAL_CLOSURE".equals(obligation.kind())) {
                out.add(new StoryClosure(obligation.obligationKey(), obligation.kind(),
                        obligation.targetKey(), obligation.chapterNo(), obligation.required(),
                        List.of(text(obligation.payload().get("proof")))));
            }
        }
        return List.copyOf(out);
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
