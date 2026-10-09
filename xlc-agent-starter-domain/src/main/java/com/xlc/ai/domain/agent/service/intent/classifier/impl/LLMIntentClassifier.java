package com.xlc.ai.domain.agent.service.intent.classifier.impl;

import com.xlc.ai.domain.agent.model.valobj.intent.ConversationContextVO;
import com.xlc.ai.domain.agent.model.valobj.intent.IntentResultVO;
import com.xlc.ai.domain.agent.model.valobj.intent.IntentType;
import com.xlc.ai.domain.agent.service.intent.IntentService;
import com.xlc.ai.domain.agent.service.intent.IntentRegistry;
import com.xlc.ai.domain.agent.service.intent.classifier.IIntentClassifier;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * LLM 意图分类器（第2层，100~500ms）
 * 
 * 当规则分类器置信度不足时，调用独立 LLM（temperature=0.1）进行意图分类。
 * 
 * 复用智能体装配链路中 Agent 自己的 API 配置
 * （{@link OpenAiApi} + 模型名），由 {@link IntentService} 在分类前通过
 * {@link #configure(OpenAiApi, String)} 注入。构建的 ChatModel 独立于 Agent
 * 的主 ChatModel：无工具回调、temperature=0.1，保证意图识别的确定性与隔离性。
 *
 * 调用与解析流程：
 * 
 *   IntentService.classify(conf 不足)
 *        ↓
 *   LLMIntentClassifier.classify
 *        ├─ 组装上下文（最近意图 + 进行中任务态）
 *        ├─ 渲染 CLASSIFY_PROMPT_TEMPLATE（含意图清单 + few-shot 示例）
 *        ├─ chatModel.call(prompt)  → 原始 JSON 文本
 *        └─ parseResponse → IntentResultVO
 *              ├─ 提取首个 {...} JSON
 *              ├─ 解析 intent/confidence/entities/candidates
 *              └─ 解析失败 → UNKNOWN(conf=0)
 * 
 *
 * 隔离性说明：此 ChatModel 不挂任何 ToolCallback，纯文本往返，避免意图识别
 * 意外触发工具执行；temperature=0.1 保证同一输入多次分类结果稳定。
 *
 * 案例：输入 "服务器好像有点慢，帮我瞧瞧"
 * 
 *   规则层命中关键词"慢"不足 → < 0.8 下沉
 *   LLM 返回 {"intent":"DIAGNOSE","confidence":0.7,"entities":{},"candidates":["MONITOR"]}
 *   → IntentResultVO{ intent=DIAGNOSE, conf=0.7, candidates=[MONITOR] }
 * 
 *
 * @author xlvchao
 */
@Slf4j
@Component
public class LLMIntentClassifier implements IIntentClassifier {

    /** 独立 ChatModel 实例（无工具回调、temperature=0.1），由 configure 注入后惰性构建 */
    private volatile ChatModel chatModel;

    /** Agent 装配链路传入的 OpenAiApi（与 Runner 共用一套配置） */
    private volatile OpenAiApi openAiApi;

    /** Agent 配置的模型名称（如 "gpt-4"、"claude-3"） */
    private volatile String modelName;

    /** JSON 解析器，用于解析 LLM 返回的 JSON 响应 */
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 意图类型注册表，用于把 LLM 返回的字符串解析为 IntentType */
    private final IntentRegistry intentRegistry;

    public LLMIntentClassifier(IntentRegistry intentRegistry) {
        this.intentRegistry = intentRegistry;
    }

    /**
     * 由 IntentService 在分类前注入 Agent 的 API 配置。
     * 仅在配置变化时重建 ChatModel，避免每次分类都构建。
     * 
     * 案例：
     * 
     *   第1次调用 configure(api, "gpt-4")
     *   -> 创建 ChatModel，model="gpt-4", temperature=0.1
     *
     *   第2次调用 configure(api, "gpt-4")  // 配置相同
     *   -> 不重建，复用已有 ChatModel
     *
     *   第3次调用 configure(api, "claude-3")  // 模型变化
     *   -> 重建 ChatModel，model="claude-3", temperature=0.1
     * 
     *
     * @param openAiApi  Agent 装配链路构建的 OpenAiApi
     * @param modelName  Agent 配置的模型名
     */
    public synchronized void configure(OpenAiApi openAiApi, String modelName) {
        if (openAiApi == null || modelName == null || modelName.isBlank()) {
            return;
        }
        // 配置未变则不重建
        if (openAiApi.equals(this.openAiApi) && modelName.equals(this.modelName) && this.chatModel != null) {
            return;
        }
        this.openAiApi = openAiApi;
        this.modelName = modelName;
        this.chatModel = OpenAiChatModel.builder()
                .openAiApi(openAiApi)
                .defaultOptions(OpenAiChatOptions.builder()
                        .model(modelName)
                        .build())
                .build();
    }


    /**
     * 使用 LLM 进行意图分类，解析原始响应为 IntentResultVO。
     * 
     * 流程：
     * 
     *   1. 组装上下文：最近意图历史 + 任务态描述
     *   2. 渲染 Prompt：注入上下文 + 用户消息
     *   3. 调用 LLM：获取 JSON 响应
     *   4. 解析响应：提取 intent/confidence/entities/candidates
     *   5. 失败降级：解析失败 → UNKNOWN(conf=0)
     * 
     * 
     * 案例 1：正常分类
     * 
     *   message = "nginx 502了，帮我看看"
     *
     *   LLM 返回：
     *   {"intent":"DIAGNOSE","confidence":0.95,"entities":{"service":"nginx","error":"502"},"candidates":["MONITOR"]}
     *
     *   解析结果：
     *   IntentResultVO {
     *     intent=DIAGNOSE,
     *     confidence=0.95,
     *     entities={service=nginx, error=502},
     *     candidates=[MONITOR]
     *   }
     * 
     * 
     * 案例 2：复合意图
     * 
     *   message = "看下 nginx 502 是不是因为我刚改了 redis 配置导致连接池打满"
     *
     *   LLM 返回：
     *   {"intent":"COMPOUND","confidence":0.85,"entities":{"service":"nginx","error":"502"},"candidates":["DIAGNOSE","MONITOR","CONFIGURE"]}
     *
     *   解析结果：
     *   IntentResultVO {
     *     intent=COMPOUND,
     *     confidence=0.85,
     *     candidates=[DIAGNOSE, MONITOR, CONFIGURE]
     *   }
     * 
     * 
     * 案例 3：解析失败（降级 UNKNOWN）
     * 
     *   LLM 返回乱码或无效 JSON：
     *   "I think this is a monitoring task..."
     *
     *   解析失败 → 返回：
     *   IntentResultVO { intent=UNKNOWN, confidence=0.0 }
     * 
     *
     * @param message 用户原始消息
     * @param context 会话上下文（最近意图历史、任务态），可为 null
     * @return 意图识别结果，解析失败时返回 UNKNOWN(conf=0)
     */
    @Override
    public IntentResultVO classify(String message, ConversationContextVO context) {
        // 1) 组装上下文描述：最近意图历史 + 进行中任务态，让 LLM 具备连贯性
        String recentIntents = "";
        if (context != null && context.getRecentIntents() != null) {
            recentIntents = context.getRecentIntents().stream()
                    .map(h -> h.getIntent().name())
                    .collect(Collectors.joining(", "));
        }
        String taskDesc = "无";
        if (context != null && context.getTaskState() != null
                && !context.getTaskState().isCompleted()
                && context.getTaskState().getRootIntent() != null) {
            taskDesc = "进行中任务: " + context.getTaskState().getRootIntent()
                    + ", 当前步骤: " + context.getTaskState().getCurrentStepIndex();
        }

        String contextDesc = (recentIntents.isEmpty() ? "" : "最近意图: " + recentIntents)
                + (taskDesc.equals("无") ? "" : (recentIntents.isEmpty() ? "" : " | ") + taskDesc);
        if (contextDesc.isEmpty()) {
            contextDesc = "无";
        }
        String prompt = intentRegistry.getLlmIntentClassifyPrompt()
                .replace("{{CONTEXT}}", contextDesc)
                .replace("{{MESSAGE}}", message);

        // 2) 调用 LLM：chatModel 未注入或调用异常都降级为 UNKNOWN，保证不阻塞主流程
        if (chatModel == null) {
            // Agent 尚未装配完成或未注入配置，无法走 LLM 分类
            return IntentResultVO.builder()
                    .intent(intentRegistry.getUnknown()).confidence(0.0).entities(Map.of()).build();
        }
        try {
            String response = chatModel.call(prompt);
            return parseResponse(response);
        } catch (Exception e) {
            return IntentResultVO.builder()
                    .intent(intentRegistry.getUnknown()).confidence(0.0).entities(Map.of()).build();
        }
    }

    /**
     * 从 LLM 原始文本中提取首个 JSON 对象并解析；任一环节失败均降级 UNKNOWN，
     * 含解释性文字或咒语等非 JSON 输出不会导致分类器抛异常。
     * 
     * 解析步骤：
     *   正则提取首个 {...} JSON 片段
     *   解析为 Map，提取 intent/confidence/entities/candidates
     *   将 candidates 列表转换为 IntentType 列表（过滤无效值）
     *   任何异常 → 返回 UNKNOWN(conf=0)
     *
     * @param response LLM 返回的原始文本
     * @return 解析后的 IntentResultVO，失败时返回 UNKNOWN
     */
    @SuppressWarnings("unchecked")
    private IntentResultVO parseResponse(String response) {
        try {
            // 提取 JSON 部分
            String json = response.replaceAll("(?s).*?(\\{.*}).*", "$1");
            Map<String, Object> parsed = objectMapper.readValue(json, Map.class);

            IntentType intent = intentRegistry.getIntentType(String.valueOf(parsed.get("intent")));
            if (intent == null) {
                return IntentResultVO.builder()
                        .intent(intentRegistry.getUnknown()).confidence(0.0)
                        .entities(Map.of()).rawResponse(response).build();
            }
            double confidence = parsed.containsKey("confidence")
                    ? Double.parseDouble(String.valueOf(parsed.get("confidence"))) : 0.5;
            Map<String, String> entities = parsed.containsKey("entities")
                    ? (Map<String, String>) parsed.get("entities") : Map.of();

            List<IntentType> candidates = List.of();
            if (parsed.containsKey("candidates")) {
                Object cands = parsed.get("candidates");
                if (cands instanceof List<?> list) {
                    candidates = list.stream()
                            .filter(o -> o instanceof String)
                            .map(o -> intentRegistry.getIntentType((String) o))
                            .filter(java.util.Objects::nonNull)
                            .distinct()
                            .collect(Collectors.toList());
                }
            }

            return IntentResultVO.builder()
                    .intent(intent).confidence(confidence)
                    .entities(entities).rawResponse(response)
                    .candidateIntents(candidates).build();
        } catch (Exception e) {
            return IntentResultVO.builder()
                    .intent(intentRegistry.getUnknown()).confidence(0.0)
                    .entities(Map.of()).rawResponse(response).build();
        }
    }
}
