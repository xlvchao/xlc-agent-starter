package com.xlc.ai.cases.react.node;

import com.xlc.ai.core.dto.ChatRequestDTO;
import com.xlc.ai.core.dto.ReActResultDTO;
import com.xlc.ai.cases.react.AbstractAIAgentReActSupport;
import com.xlc.ai.cases.react.factory.DefaultReActFactory;
import com.xlc.ai.domain.agent.model.entity.ChatMessageEntity;
import com.xlc.ai.domain.agent.service.ILongTermMemoryService;
import com.xlc.ai.core.framework.StrategyHandler;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ReAct Root Node（根节点）
 *
 * 职责：
 * 1. 从 ChatRequestDTO 提取会话参数
 * 2. 初始化 DynamicContext
 * 3. 路由到 ReActAiCallNode
 *
 * 节点链：
 * ReActRootNode → ReActAiCallNode → ReActToolCallNode → ReActLoopDecisionNode → ReActUserFeedbackNode
 *
 * @author xlvchao
 */
@Slf4j
@Component("reactRootNode")
public class ReActRootNode extends AbstractAIAgentReActSupport {

    @Resource
    private ILongTermMemoryService longTermMemoryService;

    private static final int DEFAULT_MAX_STEPS = 50;
    private static final int DEFAULT_MAX_TOOL_CALLS = 200;
    private static final int DEFAULT_MAX_TOOL_CALLS_PER_ROUND = 10;

    @Override
    protected ReActResultDTO doApply(ChatRequestDTO requestParameter, DefaultReActFactory.DynamicContext dynamicContext) throws Exception {
        log.info("ReActRootNode - 初始化上下文");

        // 1. 提取会话参数
        String sessionId = requestParameter.getSessionId();
        String userId = requestParameter.getUserId();
        String agentId = requestParameter.getAgentId();
        String message = requestParameter.getMessage();

        // 3. 初始化上下文
        dynamicContext.setSessionId(sessionId);
        dynamicContext.setUserId(userId);
        dynamicContext.setAgentId(agentId);

        // 记录首轮最干净的原始任务，防止在长对话或前缀注入后被污染
        dynamicContext.setOriginalUserTask(message);

        // 冷启动恢复：从 DB 加载最近 50 条历史消息，恢复对话上下文。
        // 这样即使服务重启，用户之前排查的上下文也能接上，而不是从空开始。
        List<Map<String, Object>> history = new ArrayList<>();
        // 冷启动恢复：委托领域服务从 DB 加载最近 50 条历史消息，恢复对话上下文，
        // case 层不再直接调用仓储层。
        List<ChatMessageEntity> recentMessages = longTermMemoryService.getRecentMessages(sessionId, 50);
        for (ChatMessageEntity msg : recentMessages) {
            Map<String, Object> map = new HashMap<>();
            map.put("role", msg.getRole());
            map.put("content", msg.getContent() != null ? msg.getContent() : "");
            // tool 消息需要补全 tool_call_id 和 name，供 ADK 框架正确关联
            if ("tool".equals(msg.getRole()) && msg.getToolCallId() != null) {
                map.put("tool_call_id", msg.getToolCallId());
                map.put("name", msg.getToolName());
            }
            history.add(map);
        }
        dynamicContext.setMessageHistory(history);
        dynamicContext.setCurrentToolCalls(new ArrayList<>());
        dynamicContext.setCurrentToolResults(new ArrayList<>());
        dynamicContext.setCurrentStep(new AtomicInteger(0));
        dynamicContext.setMaxSteps(DEFAULT_MAX_STEPS);
        dynamicContext.setMaxToolCalls(DEFAULT_MAX_TOOL_CALLS);
        dynamicContext.setMaxToolCallsPerRound(DEFAULT_MAX_TOOL_CALLS_PER_ROUND);

        // 4. 初始化结果 DTO
        ReActResultDTO result = ReActResultDTO.builder()
                .totalSteps(0)
                .totalToolCalls(0)
                .maxStepsReached(false)
                .userStopped(false)
                .idleTimeout(false)
                .build();
        dynamicContext.setResult(result);

        // 5. 追加用户消息到历史
        dynamicContext.appendUserMessage(message);

        log.info("ReActRootNode - 初始化完成 sessionId={}, userId={}, agentId={}", sessionId, userId, agentId);

        // 6. 路由到 AI 调用节点
        return router(requestParameter, dynamicContext);
    }

    @Override
    public StrategyHandler<ChatRequestDTO, DefaultReActFactory.DynamicContext, ReActResultDTO> get(
            ChatRequestDTO requestParameter,
            DefaultReActFactory.DynamicContext dynamicContext) throws Exception {
        return getBean("reactAiCallNode");
    }

}
