package com.xlc.ai.cases.react.node;

import com.xlc.ai.core.dto.ChatRequestDTO;
import com.xlc.ai.core.dto.ReActResultDTO;
import com.xlc.ai.cases.react.AbstractAIAgentReActSupport;
import com.xlc.ai.cases.react.factory.DefaultReActFactory;
import com.xlc.ai.domain.agent.service.IChatContextService;
import com.xlc.ai.domain.agent.service.ILongTermMemoryService;
import com.xlc.ai.domain.agent.service.IPromptService;
import com.xlc.ai.domain.agent.service.install.matter.tools.SampleAdkTool;
import com.xlc.ai.core.framework.StrategyHandler;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

import jakarta.annotation.Resource;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ReAct 工具执行节点
 *
 * 职责：
 * 1. 从上下文中获取 AI 返回的工具调用列表（由 ReActAiCallNode 设置）
 * 2. 检查工具是否已被 ADK 自动执行（FunctionResponse 已存在）
 * 3. 整理自动执行的工具事件并记录补偿日志
 * 4. 路由：到 ReActLoopDecisionNode 检查终止条件
 *
 * 注意：在当前 ADK 自动执行模式下，此节点不承担真实的循环发起职责。
 *
 * @author xlvchao
 */
@Slf4j
@Component("reactToolCallNode")
public class ReActToolCallNode extends AbstractAIAgentReActSupport {

    @Resource
    private SampleAdkTool sampleAdkTool;

    @Resource
    private IPromptService promptService;

    @Resource
    private IChatContextService chatContextService;

    @Resource
    private ILongTermMemoryService longTermMemoryService;

    @Override
    protected ReActResultDTO doApply(ChatRequestDTO requestParameter, DefaultReActFactory.DynamicContext dynamicContext) throws Exception {
        List<Map<String, Object>> toolCalls = dynamicContext.getCurrentToolCalls();
        List<Map<String, Object>> toolResults = dynamicContext.getCurrentToolResults();

        if (toolCalls == null || toolCalls.isEmpty()) {
            log.info("ReActToolCallNode - 无工具调用，跳过");
            return router(requestParameter, dynamicContext);
        }

        log.info("ReActToolCallNode - 处理 {} 个工具调用，已有 {} 个结果",
                toolCalls.size(), toolResults != null ? toolResults.size() : 0);

        ResponseBodyEmitter emitter = dynamicContext.getEmitter();
        boolean adkAutoExecuted = toolResults != null && !toolResults.isEmpty();

        if (adkAutoExecuted) {
            log.info("工具已被 ADK 自动执行，记录执行痕迹");
            handleAdkToolResults(dynamicContext, toolCalls, toolResults);
        } else {
            log.info("工具未执行，手动执行");
            handleManualToolExecution(dynamicContext, toolCalls, emitter);
        }

        return router(requestParameter, dynamicContext);
    }

    @Override
    public StrategyHandler<ChatRequestDTO, DefaultReActFactory.DynamicContext, ReActResultDTO> get(
            ChatRequestDTO requestParameter,
            DefaultReActFactory.DynamicContext dynamicContext) throws Exception {

        // 在 ADK 自动执行主循环架构下，ReActToolCallNode 只是观测和整理节点，直接路由到 ReActLoopDecisionNode 判断收尾条件。
        log.info("工具观测节点处理完成，路由到 ReActLoopDecisionNode 检查终止条件");
        return getBean("reactLoopDecisionNode");
    }

    // ═══════════════════════════════════════════════════════════════
    //  ADK 自动执行模式
    // ═══════════════════════════════════════════════════════════════
    private void handleAdkToolResults(DefaultReActFactory.DynamicContext dynamicContext,
                                       List<Map<String, Object>> toolCalls,
                                       List<Map<String, Object>> toolResults) {

        Map<String, Map<String, Object>> resultMap = new HashMap<>();
        for (Map<String, Object> result : toolResults) {
            String id = (String) result.get("id");
            if (id != null) {
                resultMap.put(id, result);
            }
        }

        for (Map<String, Object> toolCall : toolCalls) {
            String toolCallId = (String) toolCall.get("id");
            String toolName = (String) toolCall.get("name");

            Map<String, Object> matchedResult = resultMap.get(toolCallId);
            if (matchedResult != null) {
                String content = (String) matchedResult.get("content");
                log.info("ADK 工具结果记录完毕: id={}, name={}, result_length={}",
                        toolCallId, toolName, content != null ? content.length() : 0);

                // 补全 ADK 自动执行模式下缺失的 messageHistory 写入
                dynamicContext.appendToolMessage(toolCallId, content);
                // 长期记忆提取（工具观察）：从工具输出中自动识别环境信息、软件版本、失败信号等，
                // 提取为 ENVIRONMENT_FACT / SOFTWARE_FACT / TROUBLESHOOTING_CASE 记忆。
                longTermMemoryService.recordToolObservation(
                        dynamicContext.getUserId(),
                        dynamicContext.getSessionId(),
                        toolName,
                        content,
                        !isFailureContent(content)
                );
            } else {
                log.warn("未找到工具结果记录: id={}, name={}", toolCallId, toolName);
            }
        }
        
        // 保留 toolCalls 供后续节点(ReActUserFeedbackNode)输出
    }

    // ═══════════════════════════════════════════════════════════════
    //  手动执行模式（未来扩展）
    // ═══════════════════════════════════════════════════════════════

    private void handleManualToolExecution(DefaultReActFactory.DynamicContext dynamicContext,
                                            List<Map<String, Object>> toolCalls,
                                            ResponseBodyEmitter emitter) throws Exception {

        for (Map<String, Object> toolCall : toolCalls) {
            String toolCallId = (String) toolCall.get("id");
            String toolName = (String) toolCall.get("name");
            String argsStr = (String) toolCall.get("args");

            if (toolCallId == null || toolName == null) {
                continue;
            }

            sendToolCallEvent(emitter, toolCallId, toolName, "executing");

            String resultContent;
            String status = "success";
            try {
                resultContent = executeTool(toolName, argsStr);
            } catch (Exception e) {
                resultContent = "Error: " + e.getMessage();
                status = "error";
            }

            resultContent = truncateToolResponse(resultContent, 4000);

            Map<String, Object> toolResult = new HashMap<>();
            toolResult.put("id", toolCallId);
            toolResult.put("name", toolName);
            toolResult.put("content", resultContent);
            toolResult.put("status", status);
            dynamicContext.getCurrentToolResults().add(toolResult);

            dynamicContext.appendToolMessage(toolCallId, resultContent);
            promptService.detectAndRecordMilestone(dynamicContext.getSessionId(), "tool", resultContent);
            chatContextService.pushToolResult(dynamicContext.getSessionId(), toolName, resultContent);

            // 工具结果落库 + 长期记忆提取：委托领域服务完成"消息落库（role=tool, priority=HIGH）
            // + 环境/软件/失败信号记忆提取"闭环，case 层不再直接调用仓储层。
            longTermMemoryService.saveToolMessage(
                    dynamicContext.getUserId(),
                    dynamicContext.getSessionId(),
                    toolName,
                    toolCallId,
                    resultContent,
                    "success".equals(status) && !isFailureContent(resultContent)
            );

            sendToolResultEvent(emitter, toolCallId, resultContent, status);
        }
    }

    /**
     * 判断工具输出内容是否包含失败特征（error、failed、permission denied 等）。
     * 
     * 用于长期记忆提取时判断 success 参数：如果工具执行状态为 success 但内容包含失败信号，
     * 也会被 recordToolObservation 记录为 TROUBLESHOOTING_CASE。
     *
     * @param content 工具执行输出内容
     * @return true 表示内容包含失败特征
     */
    private boolean isFailureContent(String content) {
        if (content == null) {
            return false;
        }
        String normalized = content.toLowerCase();
        return normalized.contains("error")
                || normalized.contains("failed")
                || normalized.contains("not found")
                || normalized.contains("no such")
                || normalized.contains("permission denied")
                || normalized.contains("connection refused");
    }

    private String executeTool(String toolName, String args) throws Exception {
        if ("executeCommand".equals(toolName)) {
            String command = resolveCommandArgument(args);
            Map<String, Object> result = sampleAdkTool.executeCommand(command);
            return formatToolExecutionResult(result);
        }
        throw new UnsupportedOperationException("Unsupported tool: " + toolName);
    }

    private String resolveCommandArgument(String args) {
        if (args == null || args.isBlank()) {
            return "";
        }

        String trimmedArgs = args.trim();
        if (!trimmedArgs.startsWith("{")) {
            return trimmedArgs;
        }

        try {
            JsonNode root = objectMapper.readTree(trimmedArgs);
            JsonNode commandNode = root.get("command");
            if (commandNode != null && !commandNode.isNull()) {
                return commandNode.asText("");
            }
        } catch (Exception e) {
            log.warn("解析工具参数失败，按原始字符串执行: args={}", trimmedArgs, e);
        }

        return trimmedArgs;
    }

    private String formatToolExecutionResult(Map<String, Object> result) {
        if (result == null || result.isEmpty()) {
            return "";
        }

        String output = valueAsString(result.get("output"));
        String suggestion = valueAsString(result.get("suggestion"));
        boolean success = Boolean.TRUE.equals(result.get("success"));

        if (success || suggestion.isBlank()) {
            return output.isBlank() ? valueAsString(result) : output;
        }

        if (output.isBlank()) {
            return suggestion;
        }

        return output + "\nSuggestion: " + suggestion;
    }

    private String valueAsString(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof String text) {
            return text;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }

    private String truncateToolResponse(String response, int maxLength) {
        if (response == null || response.length() <= maxLength) {
            return response;
        }
        return response.substring(0, maxLength) + "\n...[Truncated]";
    }
}
