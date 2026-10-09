package com.xlc.ai.cases.react.node;

import com.xlc.ai.core.dto.ChatRequestDTO;
import com.xlc.ai.core.dto.ReActResultDTO;
import com.xlc.ai.cases.react.AbstractAIAgentReActSupport;
import com.xlc.ai.cases.react.factory.DefaultReActFactory;
import com.xlc.ai.core.framework.StrategyHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * ReAct 循环决策节点
 *
 * 职责：
 * 1. 检查终止条件（错误、最大步数、用户停止、最大工具调用次数）
 * 2. 在 ADK 主循环模式下，作为结束节点收口，统一路由到 ReActUserFeedbackNode
 *
 * @author xlvchao
 */
@Slf4j
@Component("reactLoopDecisionNode")
public class ReActLoopDecisionNode extends AbstractAIAgentReActSupport {

    @Override
    protected ReActResultDTO doApply(ChatRequestDTO requestParameter, DefaultReActFactory.DynamicContext dynamicContext) throws Exception {
        log.info("ReActLoopDecisionNode - 检查结束条件，当前步数: {}/{}",
                dynamicContext.getStep(), dynamicContext.getMaxSteps());

        // 1. 检查是否已有终止原因
        String stopReason = dynamicContext.getStopReason();
        if (stopReason != null) {
            log.info("已设置终止原因: {}", stopReason);
            return router(requestParameter, dynamicContext);
        }

        // 2. 检查最大步数
        if (dynamicContext.getStep() >= dynamicContext.getMaxSteps()) {
            log.info("达到最大步数: {}, 终止处理", dynamicContext.getMaxSteps());
            dynamicContext.setStopReason("max_steps");
            dynamicContext.getResult().setMaxStepsReached(true);
            return router(requestParameter, dynamicContext);
        }

        // 3. 检查最大工具调用次数 (使用一致的计数器)
        if (dynamicContext.getTotalToolCallCount().get() >= dynamicContext.getMaxToolCalls()) {
            log.info("达到最大工具调用次数: {}, 终止处理",
                    dynamicContext.getTotalToolCallCount().get());
            dynamicContext.setStopReason("max_tool_calls");
            return router(requestParameter, dynamicContext);
        }

        // 4. 检查 assistant 消息是否包含终止指令
        String assistantContent = dynamicContext.getAssistantContent() != null
                ? dynamicContext.getAssistantContent().toString()
                : "";

        if (containsFinishCommand(assistantContent)) {
            log.info("AI 返回 finish 指令，终止处理");
            dynamicContext.setStopReason("finish");
            return router(requestParameter, dynamicContext);
        }

        // 5. 检查错误
        if (dynamicContext.getErrorMessage() != null) {
            log.info("发生错误: {}, 终止处理", dynamicContext.getErrorMessage());
            dynamicContext.setStopReason("error");
            return router(requestParameter, dynamicContext);
        }

        // 6. 无异常且 ADK 自动执行结束，视为正常 completed
        log.info("本轮 ADK 执行正常结束");
        dynamicContext.setStopReason("completed");
        return router(requestParameter, dynamicContext);
    }

    @Override
    public StrategyHandler<ChatRequestDTO, DefaultReActFactory.DynamicContext, ReActResultDTO> get(
            ChatRequestDTO requestParameter, DefaultReActFactory.DynamicContext dynamicContext) throws Exception {

        String stopReason = dynamicContext.getStopReason();

        if (stopReason != null) {
            switch (stopReason) {
                case "user_stop":
                    dynamicContext.getResult().setUserStopped(true);
                    break;
                case "idle_timeout":
                    dynamicContext.getResult().setIdleTimeout(true);
                    break;
                case "max_steps":
                    dynamicContext.getResult().setMaxStepsReached(true);
                    break;
                default:
                    break;
            }
        }

        // 在当前架构下，ADK 包办了工具链循环。到达此处意味着一次 runAsync 已跑完，直接输出结果
        return getBean("reactUserFeedbackNode");
    }

    // ═══════════════════════════════════════════════════════════════
    //  辅助方法
    // ═══════════════════════════════════════════════════════════════

    private boolean containsFinishCommand(String content) {
        if (content == null || content.isEmpty()) {
            return false;
        }
        String lowerContent = content.toLowerCase();
        return lowerContent.contains("<finish>")
                || lowerContent.contains("[finish]")
                || lowerContent.contains("action: finish");
    }
}