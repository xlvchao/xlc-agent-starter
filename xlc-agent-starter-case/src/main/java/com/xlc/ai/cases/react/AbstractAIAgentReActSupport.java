package com.xlc.ai.cases.react;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xlc.ai.core.dto.ChatRequestDTO;
import com.xlc.ai.core.dto.ReActEventDTO;
import com.xlc.ai.core.dto.ReActResultDTO;
import com.xlc.ai.cases.react.factory.DefaultReActFactory;
import com.xlc.ai.core.framework.AbstractMultiThreadStrategyRouter;
import com.xlc.ai.core.framework.StrategyHandler;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

import javax.annotation.Resource;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

/**
 * ReAct 支撑类（抽象基类）
 *
 * 参考 mobile-claw-case 的 AbstractAutoAgentSupport 设计，
 * 封装 ReAct 循环的通用能力：
 * - 上下文管理（DynamicContext）
 * - SSE 事件发射
 * - 工具调用结果解析
 * - 响应格式化
 *
 * 节点路由链：
 * ReActRootNode → ReActAiCallNode → ReActToolCallNode → (ToolResultNode) → [循环或完成]
 *
 * @author xlvchao
 */
@Slf4j
public abstract class AbstractAIAgentReActSupport extends AbstractMultiThreadStrategyRouter<ChatRequestDTO, DefaultReActFactory.DynamicContext, ReActResultDTO> {

    @Getter
    @Setter
    protected StrategyHandler<ChatRequestDTO, DefaultReActFactory.DynamicContext, ReActResultDTO> defaultStrategyHandler = StrategyHandler.DEFAULT;

    @Resource
    protected ApplicationContext applicationContext;

    protected final ObjectMapper objectMapper = new ObjectMapper();


    @Override
    protected void multiThread(ChatRequestDTO requestParameter, DefaultReActFactory.DynamicContext dynamicContext) throws ExecutionException, InterruptedException, TimeoutException {
        // 暂无异步预加载需求
    }

    /**
     * 通用的 Bean 获取
     */
    protected <T> T getBean(String beanName) {
        return applicationContext.getBean(beanName, (Class<T>) Object.class);
    }




    // ═══════════════════════════════════════════════════════════════
    //  SSE 事件发射辅助
    // ═══════════════════════════════════════════════════════════════

    /**
     * 发送文本事件
     */
    protected void sendTextEvent(ResponseBodyEmitter emitter, String content, String fullText) {
        try {
            ReActEventDTO event = new ReActEventDTO();
            event.setEvent("text");
            event.setContent(content);
            event.setFullText(fullText);
            emitter.send(objectMapper.writeValueAsString(event) + "\n");
            log.info("发送文本事件 {}", event);
        } catch (Exception e) {
            log.warn("发送文本事件失败: {}", e.getMessage());
        }
    }

    /**
     * 发送工具调用事件
     */
    protected void sendToolCallEvent(ResponseBodyEmitter emitter, String toolCallId, String toolName, String status) {
        try {
            ReActEventDTO event = new ReActEventDTO();
            event.setEvent("tool_call");
            event.setToolCallId(toolCallId);
            event.setToolName(toolName);
            event.setStatus(status);
            emitter.send(objectMapper.writeValueAsString(event) + "\n");
            log.info("发送工具调用事件 {}", event);
        } catch (Exception e) {
            log.warn("发送工具调用事件失败: {}", e.getMessage());
        }
    }

    /**
     * 发送工具结果事件
     */
    protected void sendToolResultEvent(ResponseBodyEmitter emitter, String toolCallId, String content, String status) {
        try {
            ReActEventDTO event = new ReActEventDTO();
            event.setEvent("tool_result");
            event.setToolCallId(toolCallId);
            event.setContent(content);
            event.setStatus(status);
            emitter.send(objectMapper.writeValueAsString(event) + "\n");
            log.info("发送工具结果事件 {}", event);
        } catch (Exception e) {
            log.warn("发送工具结果事件失败: {}", e.getMessage());
        }
    }

    /**
     * 发送步数结束事件
     */
    protected void sendRoundEndEvent(ResponseBodyEmitter emitter, int currentStep, int maxSteps, boolean shouldContinue, int totalToolCalls) {
        try {
            ReActEventDTO.StepInfo stepInfo = new ReActEventDTO.StepInfo();
            stepInfo.setCurrentStep(currentStep);
            stepInfo.setMaxSteps(maxSteps);
            stepInfo.setShouldContinue(shouldContinue);
            stepInfo.setTotalToolCalls(totalToolCalls);

            ReActEventDTO event = new ReActEventDTO();
            event.setEvent("round_end");
            event.setStepInfo(stepInfo);
            emitter.send(objectMapper.writeValueAsString(event) + "\n");
            log.info("发送 round_end 事件 {}", event);
        } catch (Exception e) {
            log.warn("发送 round_end 事件失败: {}", e.getMessage());
        }
    }

    /**
     * 发送完成事件
     */
    protected void sendDoneEvent(ResponseBodyEmitter emitter, ReActResultDTO result) {
        try {
            ReActEventDTO event = new ReActEventDTO();
            event.setEvent("done");
            event.setContent(objectMapper.writeValueAsString(result));
            emitter.send(objectMapper.writeValueAsString(event) + "\n");
            log.info("发送 done 事件 {}", event);
        } catch (Exception e) {
            log.warn("发送 done 事件失败: {}", e.getMessage());
        }
    }

}
