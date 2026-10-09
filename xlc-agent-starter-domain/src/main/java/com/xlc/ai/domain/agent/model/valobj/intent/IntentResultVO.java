package com.xlc.ai.domain.agent.model.valobj.intent;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * 意图识别结果
 *
 * 由 {@code RuleIntentClassifier} / {@code LLMIntentClassifier} 产生，
 * 经 {@code IntentService} 级联裁决后返回给 {@code ReActAiCallNode}。一条结果同时承载
 * 主意图与候选意图，使下游在低置信度时可以"参考而非被迫接受"。
 *
 * 字段在流转中的用途：
 * 
 *   IntentService.classify()
 *      └─ IntentResultVO ─┬─ intent/confidence   → ReActAiCallNode 决定是否硬路由
 *                         ├─ candidateIntents    → 主模型拆解 COMPOUND / 低置信度参考
 *                         ├─ entities            → 可注入 Prompt（如 service=nginx）
 *                         └─ reclassified        → 反馈回路标记，避免二次重分类
 *
 *   ReActAiCallNode 拿到结果后：
 *      └─ dynamicContext.setCurrentIntent(intent.name())     → 注入 Prompt 前缀
 *      └─ dynamicContext.setCurrentIntentResult(this)        → 反馈回路读取
 * 
 *
 * 案例：
 * 
 *   输入 "nginx 502 了帮我看看"
 *   → IntentResultVO{ intent=DIAGNOSE, confidence=0.9,
 *                     entities={service=nginx}, candidateIntents=[MONITOR],
 *                     reclassified=false }
 *
 *   反馈回路：工具结果 "command not found" 且原 conf<0.7
 *   → IntentResultVO{ intent=候选[0], confidence=原*0.8, reclassified=true }
 * 
 *
 * @author xlvchao
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class IntentResultVO {

    /** 意图类型 */
    private IntentType intent;

    /** 置信度 0.0 ~ 1.0 */
    private double confidence;

    /** 识别出的实体（服务名、配置项等） */
    private Map<String, String> entities;

    /** LLM 原始响应（调试用） */
    private String rawResponse;

    /**
     * 候选意图（按置信度降序，不含主意图本身）
     * 
     * 当主意图置信度不足时，下游主模型可参考候选意图自行判断，
     * 而不是被迫接受一个低置信度的硬选结果。
     */
    @Builder.Default
    private List<IntentType> candidateIntents = List.of();

    /** 是否由反馈回路触发重分类得到（调试/可观测用） */
    @Builder.Default
    private boolean reclassified = false;

}
