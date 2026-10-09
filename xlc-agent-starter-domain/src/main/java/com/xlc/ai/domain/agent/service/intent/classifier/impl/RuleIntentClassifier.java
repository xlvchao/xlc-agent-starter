package com.xlc.ai.domain.agent.service.intent.classifier.impl;

import com.xlc.ai.domain.agent.model.valobj.intent.ConversationContextVO;
import com.xlc.ai.domain.agent.model.valobj.intent.IntentResultVO;
import com.xlc.ai.domain.agent.model.valobj.intent.IntentRuleVO;
import com.xlc.ai.domain.agent.model.valobj.intent.IntentType;
import com.xlc.ai.domain.agent.service.intent.IntentRegistry;
import com.xlc.ai.domain.agent.service.intent.classifier.IIntentClassifier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 规则意图分类器（第1层，< 1ms）
 *
 * 通过关键词匹配（最高 0.6）+ 正则匹配（+0.2）+ 上下文加权（+0.1）
 * 快速识别高置信度意图。置信度 ≥ 0.8 时直接返回，跳过 LLM 调用。
 *
 * 规则来源：由 {@code ai.agent.intent.intents} 配置动态加载，
 * 仅加载配置了 keywords 的业务意图；特殊意图（UNKNOWN/COMPOUND/CONTINUE/CHAT）不参与规则分类。
 *
 * 打分与裁决流程（{@link #classify} 实现）：
 *
 *   输入 message
 *     │
 *     ├─ 纯"继续"指令？ ──是──→ CONTINUE（有任务态 conf=0.95 / 无任务态 conf=0.4）
 *     │
 *     └─ 否 → 遍历每条规则打分：
 *              关键词命中 0.5+(hits-1)*0.1 ≤0.7
 *              正则命中   +0.2
 *              上下文加权 +0.1（最近意图含同意图）
 *              ──────────────────────────
 *              按得分降序排列
 *     │
 *     ├─ ≥2 个意图 ≥0.5 → COMPOUND（候选=全部命中意图）
 *     ├─ 仅 1 个命中     → 单一意图（候选=次优意图）
 *     └─ 无任何命中      → UNKNOWN(conf=0)
 *
 *
 * 设计要点：
 *
 *   "继续部署"不会被误判为 CONTINUE（去掉继续类词后仍剩"部署"，长度>4）。
 *   复合意图门槛 0.5，避免单一强信号被误报为 COMPOUND。
 *
 *
 * @author xlvchao
 */
@Slf4j
@Component
public class RuleIntentClassifier implements IIntentClassifier {

    /**
     * 当有 ≥2 个意图达到该置信度时，判定为复合指令
     */
    private static final double COMPOUND_THRESHOLD = 0.5;

    /**
     * "继续"类关键词集合：命中后且去词剩余内容 ≤4 才视为纯 CONTINUE 指令
     */
    private static final List<String> CONTINUE_KEYWORDS = List.of("继续", "continue", "接着", "往下", "go on");

    /**
     * 从配置加载的意图规则列表
     */
    private final IntentRegistry intentRegistry;

    public RuleIntentClassifier(IntentRegistry intentRegistry) {
        this.intentRegistry = intentRegistry;
        log.info("规则意图分类器初始化完成！");
    }


    @Override
    public IntentResultVO classify(String message, ConversationContextVO context) {
        String lowerMsg = message.toLowerCase();

        // ── 分类流程：CONTINUE 优先判定 → 逐规则打分 → 复合/单一/未知裁决 ──
        // 详见类级 javadoc 中的打分示意图。
        // CONTINUE：仅当消息本身就是一个"继续"指令（去掉继续类词后几乎无其他内容）时才命中，
        // 避免"继续部署""继续排查"这类带明确动作的复合指令被误判为 CONTINUE
        if (isContinueDirective(lowerMsg)) {
            if (context != null && context.getTaskState() != null
                    && !context.getTaskState().isCompleted()
                    && context.getTaskState().getRootIntent() != null) {
                return IntentResultVO.builder()
                        .intent(intentRegistry.getContinue())
                        .confidence(0.95)
                        .entities(Map.of("task", String.valueOf(context.getTaskState().getCurrentStepIndex())))
                        .candidateIntents(List.of(context.getTaskState().getRootIntent()))
                        .build();
            }
            // 没有进行中的任务态，CONTINUE 没有依据，交给主模型
            return IntentResultVO.builder()
                    .intent(intentRegistry.getContinue())
                    .confidence(0.4)
                    .entities(Map.of())
                    .build();
        }

        // 计算每个意图的得分
        List<Map.Entry<IntentType, Double>> scored = new ArrayList<>();
        Map<IntentType, Map<String, String>> intentEntities = new HashMap<>();
        for (IntentRuleVO rule : intentRegistry.getIntentRules()) {
            double score = 0.0;

            long hits = rule.getKeywords().stream()
                    .filter(lowerMsg::contains).count();
            if (hits > 0) {
                score += Math.min(0.7, 0.5 + (hits - 1) * 0.1);
            }

            boolean patternHit = rule.getPatterns().stream()
                    .anyMatch(p -> Pattern.matches(".*" + p + ".*", message));
            if (patternHit) score += 0.2;

            if (context != null && context.getRecentIntents() != null) {
                if (context.getRecentIntents().stream()
                        .anyMatch(h -> h.getIntent() == rule.getIntent())) {
                    score += 0.1;
                }
            }

            score = Math.min(1.0, score);
            if (score > 0) {
                scored.add(Map.entry(rule.getIntent(), score));
                intentEntities.put(rule.getIntent(), extractEntities(message, rule.getIntent()));
            }
        }

        scored.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));

        if (scored.isEmpty()) {
            return IntentResultVO.builder()
                    .intent(intentRegistry.getUnknown()).confidence(0.0).entities(Map.of()).build();
        }

        // 复合意图检测：≥2 个意图达到阈值 → COMPOUND
        long highConfidenceCount = scored.stream()
                .filter(e -> e.getValue() >= COMPOUND_THRESHOLD).count();
        if (highConfidenceCount >= 2) {
            List<IntentType> candidates = scored.stream()
                    .map(Map.Entry::getKey)
                    .collect(Collectors.toList());
            double compoundConfidence = Math.min(0.9, scored.get(0).getValue());
            return IntentResultVO.builder()
                    .intent(intentRegistry.getCompound())
                    .confidence(compoundConfidence)
                    .entities(intentEntities.getOrDefault(scored.get(0).getKey(), Map.of()))
                    .candidateIntents(candidates)
                    .build();
        }

        // 单一意图：返回主意图 + 候选（次优）
        IntentType topIntent = scored.get(0).getKey();
        double topScore = scored.get(0).getValue();
        List<IntentType> candidates = scored.size() > 1
                ? scored.subList(1, scored.size()).stream()
                .map(Map.Entry::getKey).collect(Collectors.toList())
                : List.of();

        return IntentResultVO.builder()
                .intent(topIntent)
                .confidence(topScore)
                .entities(intentEntities.getOrDefault(topIntent, Map.of()))
                .candidateIntents(candidates)
                .build();
    }

    /**
     * 判断消息是否是一个纯"继续"指令（而非包含"继续"二字的其他指令）。
     * 去掉继续类关键词和常见标点后，剩余内容长度 ≤ 4 视为纯继续指令。
     *
     * 案例：
     *
     *   "继续"               → 去词后为空，长度 0 ≤4 → true
     *   "接着往下"            → 去词后为空 → true
     *   "继续排查 nginx 502"  → 去词后剩"排查 nginx 502"，长度 > 4 → false（保留为业务意图）
     *   "请继续"              → 去词后剩"请"，长度 1 ≤4 → true（误放行，由 classify 任务态兜底）
     *
     * 说明：本方法只做"是否疑似纯继续指令"的初筛；是否真正命中 CONTINUE 仍由 classify
     * 结合任务态判定——无任务态时 conf 仅 0.4，最终交主模型处理。
     */
    private static boolean isContinueDirective(String lowerMsg) {
        String stripped = lowerMsg;
        for (String kw : CONTINUE_KEYWORDS) {
            stripped = stripped.replace(kw, "");
        }
        stripped = stripped.replaceAll("[ ,.，。！!？?]", "");
        if (stripped.length() > 4) {
            return false;
        }
        return CONTINUE_KEYWORDS.stream().anyMatch(lowerMsg::contains);
    }

    /**
     * 提取实体（服务名等）。
     *
     * 当前仅识别常见服务名（nginx/redis/mysql/docker 等）写入 entities.service，
     * 供 LLM 分类器与下游 Prompt 引用。匹配为大小写不敏感的子串包含。
     *
     * 案例："nginx 502 了" → {service: nginx}
     *
     * @param message 用户消息
     * @param intent  当前匹配的意图类型（用于日志，当前未用于实体过滤）
     * @return 实体 Map，key 为实体类型（如 "service"），value 为识别到的值
     */
    private Map<String, String> extractEntities(String message, IntentType intent) {
        Map<String, String> entities = new HashMap<>();
        List<String> services = List.of("nginx", "redis", "mysql", "postgres", "docker",
                "kafka", "rabbitmq", "elasticsearch", "tomcat", "spring");
        services.stream().filter(message.toLowerCase()::contains)
                .forEach(svc -> entities.put("service", svc));
        return entities;
    }
}
