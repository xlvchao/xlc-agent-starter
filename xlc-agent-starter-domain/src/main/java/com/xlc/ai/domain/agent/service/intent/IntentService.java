package com.xlc.ai.domain.agent.service.intent;

import com.xlc.ai.domain.agent.model.valobj.intent.ConversationContextVO;
import com.xlc.ai.domain.agent.model.valobj.intent.IntentResultVO;
import com.xlc.ai.domain.agent.model.valobj.intent.IntentType;
import com.xlc.ai.domain.agent.model.valobj.intent.TaskStateVO;
import com.xlc.ai.domain.agent.service.intent.classifier.impl.LLMIntentClassifier;
import com.xlc.ai.domain.agent.service.intent.classifier.impl.RuleIntentClassifier;
import org.springframework.ai.openai.api.OpenAiApi;
import com.xlc.ai.domain.agent.service.IIntentService;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 意图识别服务（调度入口）
 * 
 * 三层级联策略：
 *   规则分类（< 1ms）：置信度 ≥ 0.8 直接返回
 *   LLM 分类（100~500ms）：置信度 ≥ 0.5 采用，否则回退规则结果
 *   反馈回路：下游执行失败时触发重分类（最多重试一次，避免循环）
 * 带 LRU 缓存（200 条目，5 分钟过期），避免重复分类。
 * 
 * 可靠性增强：
 * 
 *   低置信度不再硬选，而是带上候选意图交给下游主模型参考
 *   UNKNOWN 语义为"放弃分类、全交主模型"，不再带低置信度硬走 ReAct
 *   连续失败 ≥ 阈值后，跳过规则层直接让主模型兜底
 * 
 *
 * 整体流转示意图：
 * 
 *   ReActAiCallNode.doApply
 *      │  configure(agentApi, model)   ← 复用 Agent 配置，不单独建模型
 *      │  classify(sessionId, userId, msg)
 *      ▼
 *   IntentService.classify
 *      ├─ 命中缓存(5min)？ → 直接返回
 *      ├─ 连续失败≥2？      → 跳规则层，LLM 兜底（门槛放宽到 0.3）
 *      ├─ 第1层 规则        → conf≥0.8 采信
 *      ├─ 第2层 LLM         → conf≥0.5 采信，否则回退规则结果
 *      └─ recordAndCache    → 写 ContextTracker + LRU 缓存
 *      ▼
 *   ReActAiCallNode: 注入意图标签到 Prompt；执行工具后调用
 *      │  reportFeedback(sessionId, lastIntent, success, toolResult)
 *      ▼
 *   反馈回路：失败 && conf<0.7 && 结果像意图走偏 → 用候选意图重分类（仅一次）
 * 
 *
 * 案例（端到端）：
 * 
 *   用户 "查下 redis 是不是挂了"
 *     classify → 规则命中"挂了" conf=0.6 → 下沉 LLM → DIAGNOSE conf=0.8
 *     注入 Prompt 前缀 [用户意图] 诊断问题
 *     工具执行 ssh "redis-cli ping" → 返回 PONG（成功）
 *     reportFeedback(success=true) → 维持 DIAGNOSE
 *
 *   用户 "看下 nginx 配置"（误判为 CONFIGURE conf=0.55）
 *     工具执行 cat nginx.conf → "No such file"（失败）
 *     reportFeedback → 候选[MONITOR] 递补 → 重分类 MONITOR conf=0.44，标记 reclassified
 * 
 *
 * @author xlvchao
 */
@Service
public class IntentService implements IIntentService {

    /**
     * 连续失败达到该次数后，后续分类跳过规则层、放宽 LLM 门槛
     */
    private static final int FAILURE_FALLBACK_THRESHOLD = 2;
    /**
     * 反馈回路触发重分类的置信度门槛：原意图低于此值才考虑重分类
     */
    private static final double FEEDBACK_RECLASSIFY_THRESHOLD = 0.7;

    @Resource
    private RuleIntentClassifier ruleClassifier;

    @Resource
    private LLMIntentClassifier llmClassifier;

    @Resource
    private ContextTracker contextTracker;

    @Resource
    private IntentRegistry intentRegistry;

    /**
     * LRU 缓存：基于 LinkedHashMap 的 access-order 模式，最大 200 条目。
     * 缓存键为 sessionId+hash(message)，有效期 5 分钟，避免同一消息短时间内重复分类。
     */
    private final Map<String, CacheEntry> cache = Collections.synchronizedMap(
            new LinkedHashMap<String, CacheEntry>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, CacheEntry> eldest) {
                    return size() > 200;
                }
            });

    /** LRU 缓存条意图识别结果和过期时间戳 */
    private static class CacheEntry {
        /** 缓存的意图识别结果 */
        IntentResultVO result;

        /** 过期时间戳（毫秒），超过此时间后缓存失效 */
        long expireTime;

        CacheEntry(IntentResultVO result, long expireTime) {
            this.result = result;
            this.expireTime = expireTime;
        }
    }

    /**
     * 意图分类主入口：三层级联策略 + LRU 缓存。
     * 
     * 分类策略按优先级执行：
     * <ol>
     *   缓存命中（5 分钟有效）→ 直接返回缓存结果
     *   连续失败 ≥2 → 跳过规则层，LLM 兜底（门槛放宽至 0.3）
     *   规则分类 → 置信度 ≥ 0.8 直接采用
     *   LLM 分类 → 置信度 ≥ 0.5 采用，否则回退规则结果
     * </ol>
     * 
     * 分类结果同时写入 {@link ContextTracker}（意图历史、失败计数）并缓存，
     * 避免短时间内相同消息重复调用分类器。
     *
     * @param sessionId 会话 ID，用于区分不同用户的意图历史
     * @param userId    用户 ID（当前未用于分类逻辑，预留扩展）
     * @param message   用户原始消息
     * @return 意图识别结果，包含 intent、confidence、entities、candidates 等字段
     */
    @Override
    public IntentResultVO classify(String sessionId, String userId, String message) {
        // 缓存键：会话 + 消息哈希，5 分钟内同一消息直接复用结果，避免重复分类开销
        String cacheKey = sessionId + ":" + hashMessage(message);

        CacheEntry cached = cache.get(cacheKey);
        if (cached != null && cached.expireTime > System.currentTimeMillis()) {
            return cached.result;
        }

        ConversationContextVO context = contextTracker.getContext(sessionId);
        int failures = contextTracker.getConsecutiveFailures(sessionId);

        IntentResultVO finalResult;

        // 连续失败次数过多：跳过规则层，直接 LLM 兜底，门槛放宽
        if (failures >= FAILURE_FALLBACK_THRESHOLD) {
            IntentResultVO llmResult = llmClassifier.classify(message, context);
            finalResult = llmResult.getConfidence() >= 0.3 ? llmResult : toUnknown(llmResult);
        } else {
            // 第1层：规则分类（< 1ms）
            IntentResultVO ruleResult = ruleClassifier.classify(message, context);
            if (ruleResult.getConfidence() >= 0.8) {
                finalResult = ruleResult;
            } else {
                // 第2层：LLM 分类（100-500ms）
                IntentResultVO llmResult = llmClassifier.classify(message, context);
                finalResult = llmResult.getConfidence() >= 0.5 ? llmResult : ruleResult;
            }
        }

        recordAndCache(sessionId, cacheKey, finalResult);
        return finalResult;
    }

    /**
     * 反馈回路：下游执行工具后回报结果。
     * 
     * 策略：
     * 
     *   成功：记录成功，返回 null（维持原意图）
     *   失败且原意图置信度 < 阈值：用候选意图递补；无候选则返回 UNKNOWN（全交主模型）
     *   失败但原意图置信度高：记录失败但不重分类，避免误判
     * 
     * 判定流程：
     * 
     *   reportFeedback(lastIntent, success, toolResult)
     *     ├─ lastIntent==null 或已 reclassified → 返回 null（不连锁重分类）
     *     ├─ success=true                       → 记录成功，返回 null
     *     ├─ success=false && conf≥0.7          → 仅记录失败，不重分类
     *     ├─ success=false && 结果不像意图走偏   → 返回 null
     *     └─ 满足重分类条件：
     *          ├─ 有候选 → 候选[0] 递补，conf*0.8，reclassified=true
     *          └─ 无候选 → UNKNOWN(conf=0)，全交主模型
     * 
     */
    @Override
    public IntentResultVO reportFeedback(String sessionId, IntentResultVO lastIntent,
                                         boolean success, String toolResult) {
        // 已重分类过的结果不再二次重分类，避免连锁递补
        if (lastIntent == null || lastIntent.isReclassified()) {
            return null;
        }

        // 仅在确认为失败时记录，避免一轮多工具结果重复累计
        if (!success) {
            contextTracker.recordFeedback(sessionId, false);
        }

        if (success) {
            return null;
        }

        // 高置信度意图不轻易重分类（可能只是工具偶发失败）
        if (lastIntent.getConfidence() >= FEEDBACK_RECLASSIFY_THRESHOLD) {
            return null;
        }

        // 工具结果明确提示"找不到/不存在" → 当前意图大概率走偏
        if (!looksLikeIntentMismatch(toolResult)) {
            return null;
        }

        // 用候选意图递补
        List<IntentType> candidates = lastIntent.getCandidateIntents();
        if (candidates != null && !candidates.isEmpty()) {
            IntentResultVO reclassified = IntentResultVO.builder()
                    .intent(candidates.get(0))
                    .confidence(lastIntent.getConfidence() * 0.8)
                    .entities(lastIntent.getEntities())
                    .candidateIntents(candidates.size() > 1
                            ? new ArrayList<>(candidates.subList(1, candidates.size()))
                            : List.of())
                    .rawResponse(lastIntent.getRawResponse())
                    .reclassified(true)
                    .build();
            contextTracker.updateContext(sessionId, reclassified);
            return reclassified;
        }

        // 无候选 → UNKNOWN，全交主模型
        IntentResultVO unknown = IntentResultVO.builder()
                .intent(intentRegistry.getUnknown())
                .confidence(0.0)
                .entities(lastIntent.getEntities())
                .candidateIntents(List.of())
                .reclassified(true)
                .build();
        contextTracker.updateContext(sessionId, unknown);
        return unknown;
    }

    /**
     * 工具结果是否暗示当前意图走偏（服务/文件/命令不存在类错误）。
     * 与 ReActAiCallNode 的失败判定保持一致的特征词集合。
     * 
     * 特征词同时覆盖中英文，命中即认为当前意图大概率选错（如 CONFIGURE 找不到配置文件）。
     * 该判定在两处复用：反馈回路重分类门控、ReActAiCallNode.handleIntentFeedback 成功判定。
     * 
     * 案例：
     * 
     *   toolResult = "No such file or directory: /etc/nginx/nginx.conf"
     *   -> contains("No such") = true
     *   -> 返回 true（意图走偏，触发重分类）
     *
     *   toolResult = "PONG"
     *   -> 不含任何特征词
     *   -> 返回 false（意图正确，维持原分类）
     *
     *   toolResult = "permission denied"
     *   -> contains("Permission denied") = true
     *   -> 返回 true（权限问题，可能是意图选错目标文件）
     *
     *   toolResult = "100 rows affected"
     *   -> 不含任何特征词
     *   -> 返回 false（执行成功，不重分类）
     * 
     */
    public static boolean looksLikeIntentMismatch(String toolResult) {
        return toolResult != null && (
                toolResult.contains("not found")
                        || toolResult.contains("未找到")
                        || toolResult.contains("不存在")
                        || toolResult.contains("No such")
                        || toolResult.contains("command not found")
                        || toolResult.contains("Permission denied")
                        || toolResult.contains("Connection refused"));
    }

    /**
     * 获取会话当前任务状态。
     * 
     * 委托给 {@link ContextTracker}，返回进行中任务的信息（如当前步骤、已完成状态等）。
     *
     * @param sessionId 会话 ID
     * @return 任务状态，若无任务态则返回 null
     */
    @Override
    public TaskStateVO getTaskState(String sessionId) {
        return contextTracker.getTaskState(sessionId);
    }

    @Override
    public IntentResultVO getLastIntentResult(String sessionId) {
        return contextTracker.getLastIntentResult(sessionId);
    }

    /**
     * 更新会话任务状态。
     * 
     * 通常由主流程在启动多步任务时调用，用于记录当前意图、步骤索引等信息，
     * 供后续 classify 和 reportFeedback 使用。
     *
     * @param sessionId   会话 ID
     * @param taskState   任务状态对象，可为 null（清除任务态）
     */
    @Override
    public void updateTaskState(String sessionId, TaskStateVO taskState) {
        contextTracker.setTaskState(sessionId, taskState);
    }

    /**
     * 注入 Agent 的 LLM 配置，供 {@link LLMIntentClassifier} 使用。
     * 
     * 必须在首次调用 {@link #classify} 之前执行，否则 LLM 分类器将降级为 UNKNOWN。
     * 通常由外部装配链路（如 {@code ReActAiCallNode.configure}）调用。
     *
     * @param openAiApi  Agent 装配链路构建的 OpenAiApi 实例
     * @param modelName  Agent 配置的模型名（如 "gpt-4"、"claude-3"）
     */
    @Override
    public void configure(OpenAiApi openAiApi, String modelName) {
        llmClassifier.configure(openAiApi, modelName);
    }

    /**
     * 将分类结果写入上下文追踪器，并放入 LRU 缓存。
     * 
     * 缓存键为 {@code sessionId:hash(message)}，有效期 5 分钟，
     * 超过 200 条时自动淘汰最久未使用的条目。
     *
     * @param sessionId 会话 ID
     * @param cacheKey  缓存键
     * @param result    分类结果
     */
    private void recordAndCache(String sessionId, String cacheKey, IntentResultVO result) {
        contextTracker.updateContext(sessionId, result);
        // 缓存 5 分钟
        cache.put(cacheKey, new CacheEntry(result, System.currentTimeMillis() + 5 * 60 * 1000));
    }

    /**
     * 将消息转为缓存键用的哈希值（十六进制字符串）。
     * 
     * 使用 {@link String#hashCode()} 的十六进制表示，非加密哈希，
     * 仅用于缓存键拼接，不与消息原文一一对应。
     *
     * @param message 用户消息
     * @return 十六进制哈希字符串
     */
    private String hashMessage(String message) {
        return Integer.toHexString(message.hashCode());
    }

    /**
     * 将分类结果降级为 UNKNOWN，用于连续失败后的兜底。
     * 
     * 保留原始结果的 entities 和 candidateIntents，方便主模型获取更多上下文。
     *
     * @param source 原始分类结果，可为 null
     * @return UNKNOWN 类型的 IntentResultVO
     */
    private IntentResultVO toUnknown(IntentResultVO source) {
        return IntentResultVO.builder()
                .intent(intentRegistry.getUnknown())
                .confidence(0.0)
                .entities(source != null ? source.getEntities() : Map.of())
                .candidateIntents(source != null ? source.getCandidateIntents() : List.of())
                .rawResponse(source != null ? source.getRawResponse() : null)
                .build();
    }
}
