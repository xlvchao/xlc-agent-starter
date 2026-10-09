package com.xlc.ai.cases.react.factory;

import com.xlc.ai.core.dto.ReActResultDTO;
import com.xlc.ai.domain.agent.model.valobj.intent.IntentResultVO;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ReAct 动态上下文
 *
 * 参考 DefaultArmoryFactory.DynamicContext，为 ReAct 执行链路提供状态存储：
 * - 会话信息（sessionId, userId, agentId）
 * - 消息历史（messages + toolResults）
 * - ReAct 循环状态（步数、工具调用计数）
 * - SSE 发射器
 * - 工具定义（ToolCallback[]）
 * - ADK Runner / Session
 *
 * ReAct 循环数据流：
 * ReActRootNode         → 初始化上下文，绑定 SSE emitter
 * ReActAiCallNode       → 构建 AI 请求，追加用户消息到 history
 * ReActToolCallNode     → 解析 tool_calls，执行工具，追加结果到 history
 * ReActLoopDecisionNode → 判断是否继续（max_steps / finish / 无工具调用）
 * ReActUserFeedbackNode → 发送最终结果，完成 SSE
 *
 * @author xlvchao
 */
public class DefaultReActFactory {

    /**
     * 动态上下文
     */
    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class DynamicContext {

        // ══════════════════════════════════════════════════════════
        //  会话基本信息
        // ══════════════════════════════════════════════════════════

        /** 对话会话 ID */
        private String sessionId;

        /** 用户 ID */
        private String userId;

        /** 智能体 ID */
        private String agentId;

        /** SSE 事件发射器 */
        private ResponseBodyEmitter emitter;

        // ══════════════════════════════════════════════════════════
        //  消息历史
        //  每轮 ReAct 循环结束后，将 toolCalls + toolResults 追加到这里
        // ══════════════════════════════════════════════════════════

        /**
         * 原始用户任务描述（防止在多轮交互中被前缀污染）
         */
        private String originalUserTask;

        /**
         * 消息历史
         * 格式：{ role: "user"/"assistant"/"tool", content: "...", tool_call_id?: "..." }
         */
        @Builder.Default
        private List<Map<String, Object>> messageHistory = new ArrayList<>();

        /**
         * 当前轮次的工具调用列表（缓冲）
         */
        @Builder.Default
        private List<Map<String, Object>> currentToolCalls = new ArrayList<>();

        /**
         * 当前轮次的工具执行结果列表（缓冲）
         */
        @Builder.Default
        private List<Map<String, Object>> currentToolResults = new ArrayList<>();

        /**
         * 整个会话中实际执行的工具调用记录（供最终结果展示）
         */
        @Builder.Default
        private List<Map<String, Object>> executedToolCalls = new ArrayList<>();

        // ══════════════════════════════════════════════════════════
        //  ReAct 循环状态
        // ══════════════════════════════════════════════════════════

        /** 当前步数 */
        @Builder.Default
        private AtomicInteger currentStep = new AtomicInteger(0);

        /** 最大步数 */
        private int maxSteps;

        /** 最大工具调用次数（总计） */
        private int maxToolCalls;

        /** 每轮最大工具调用次数 */
        private int maxToolCallsPerRound;

        /** 总工具调用次数 (作为真值源) */
        @Builder.Default
        private AtomicInteger totalToolCallCount = new AtomicInteger(0);

        /** 当前轮工具调用次数 */
        @Builder.Default
        private AtomicInteger roundToolCallCount = new AtomicInteger(0);

        // ══════════════════════════════════════════════════════════
        //  AI 响应缓冲（用于累积流式文本）
        // ══════════════════════════════════════════════════════════

        /** 累积的文本响应 */
        @Builder.Default
        private StringBuilder assistantContent = new StringBuilder();

        /** 累积的 reasoning_content */
        @Builder.Default
        private StringBuilder assistantReasoning = new StringBuilder();

        /** 上一轮次收到的 reasoning_content（需要回传给 API） */
        private String lastReasoningContent;

        // ══════════════════════════════════════════════════════════
        //  中断状态
        // ══════════════════════════════════════════════════════════

        /** 中断原因：user_stop / idle_timeout / max_steps */
        private String stopReason;

        /** 错误消息（如有） */
        private String errorMessage;

        // ══════════════════════════════════════════════════════════
        //  结果对象（供 ReActUserFeedbackNode 使用）
        // ══════════════════════════════════════════════════════════

        /** 最终结果 DTO */
        private ReActResultDTO result;

        // ══════════════════════════════════════════════════════════
        //  工具定义（参考 WaLiCode ai.ts）
        // ══════════════════════════════════════════════════════════

        /**
         * 工具回调列表（从 InstallService 装配链路获取）
         */
        private org.springframework.ai.tool.ToolCallback[] toolCallbacks;

        /**
         * 是否使用 Anthropic 格式（tool_call_id vs tool_use_id）
         */
        private boolean useAnthropicFormat;

        // ══════════════════════════════════════════════════════════
        //  上下文记忆（Phase 1: 动态 Prompt 构建）
        // ══════════════════════════════════════════════════════════

        /** 最近执行的命令记录（用于注入到动态 Prompt 中） */
        @Builder.Default
        private List<String> recentCommands = new ArrayList<>();

        // ══════════════════════════════════════════════════════════
        //  意图状态（Phase 3: 意图识别系统）
        // ══════════════════════════════════════════════════════════

        /** 当前意图名称（IntentType 的 name()，注入 Prompt 前缀用） */
        private String currentIntent;

        /** 当前意图识别完整结果（供反馈回路 reportFeedback 读取与重分类回写） */
        private IntentResultVO currentIntentResult;

        // ══════════════════════════════════════════════════════════
        //  辅助方法
        // ══════════════════════════════════════════════════════════

        public void incrementStep() {
            currentStep.incrementAndGet();
        }

        public int getStep() {
            return currentStep.get();
        }

        public void incrementTotalToolCalls() {
            totalToolCallCount.incrementAndGet();
        }

        public void incrementRoundToolCalls() {
            roundToolCallCount.incrementAndGet();
        }

        public void resetRoundToolCalls() {
            roundToolCallCount.set(0);
        }

        public void resetRoundBuffers() {
            currentToolCalls.clear();
            currentToolResults.clear();
            assistantContent.setLength(0);
            assistantReasoning.setLength(0);
        }

        public void appendMessage(Map<String, Object> message) {
            messageHistory.add(message);
        }

        public void appendUserMessage(String content) {
            appendMessage(Map.of("role", "user", "content", content));
        }

        public void appendAssistantMessage(String content) {
            appendMessage(Map.of("role", "assistant", "content", content));
        }

        public void appendToolMessage(String toolCallId, String content) {
            Map<String, Object> msg = useAnthropicFormat
                    ? Map.of("type", "tool_result", "tool_use_id", toolCallId, "content", content)
                    : Map.of("role", "tool", "tool_call_id", toolCallId, "content", content);
            messageHistory.add(msg);
        }

        public void addRecentCommand(String command) {
            if (command == null || command.trim().isEmpty()) return;
            recentCommands.add(command.trim());
            while (recentCommands.size() > 20) {
                recentCommands.remove(0);
            }
        }

    }

}
