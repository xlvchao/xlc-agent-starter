package com.xlc.ai.domain.agent.service.context;

import com.xlc.ai.domain.agent.model.valobj.prompt.MilestoneVO;
import com.xlc.ai.domain.agent.model.valobj.prompt.PromptContextVO;
import com.xlc.ai.domain.agent.service.IChatContextService;
import com.xlc.ai.domain.agent.model.valobj.enums.ContextPlacement;
import com.xlc.ai.domain.agent.service.context.provider.ContextProvider;
import com.xlc.ai.domain.agent.service.context.reducer.impl.HybridReducer;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.xlc.ai.domain.agent.service.context.provider.impl.ToolResultProvider;

/**
 * 上下文管理领域服务（context 包的聚合核心）
 * 
 * 功能：ReAct 对话的"上下文中枢"，对上（PromptService/Case层）提供三个能力：
 * 
 *   buildPromptContext()  聚合所有 Provider 的输出 --> PromptContextVO
 *   trimHistory()         在 token 预算内裁剪消息历史（默认 8000）
 *   pushToolResult()      接收工具执行结果，供生成工具摘要
 *   clearSessionContext() 清理指定会话的上下文缓存
 * 
 * 整体运行过程（一次 ReAct 循环中的调用时序）：
 * 
 *   ReActAiCallNode.doApply()
 *        |
 *        | (1) trimHistory(history, 8000)
 *        |        |
 *        |        v
 *        |     HybridReducer = PriorityReducer ∩ SlidingWindowReducer + 保底2条
 *        |        |
 *        |        v
 *        |     裁剪后的历史回写 DynamicContext
 *        |
 *        | (2) PromptService.buildEnrichedMessage(...)
 *        |        |
 *        |        v
 *        |     buildPromptContext(sessionId, userId, terminalSessionId, history)
 *        |        |
 *        |        +--> for provider in providers(按order排序, 跳过disabled):
 *        |        |        TerminalState(10)  {osInfo, currentUser, currentDirectory}
 *        |        |        Task(20)           {taskDescription}
 *        |        |        Milestone(30)      {milestoneVOS}
 *        |        |        ToolResult(40)     {toolResultSummary}
 *        |        |     finalCtx.putAll(...)   合并所有键值对
 *        |        |
 *        |        v
 *        |     PromptContextVO --> DynamicPromptBuilder --> 消息前缀
 *        |
 *        | (3) 工具执行后 pushToolResult(sessionId, toolName, result)
 *        |        |
 *        |        v
 *        |     ToolResultProvider.pushResult() 缓存 + 使摘要失效
 *        |     （下一轮 (2) 时新摘要进入 Prompt）
 * 
 * 装配机制：@Resource List<ContextProvider> 由 Spring 按类型自动收集
 * 全部 Provider 实现，@PostConstruct 时按 getOrder() 排序——新增 Provider
 * 只需加 @Component，无需修改本类。
 *
 * @author xlvchao
 */
@Service
public class ChatContextService implements IChatContextService {

    private static final int DEFAULT_MAX_CONTEXT_TOKENS = 8000;

    private final List<ContextProvider> providers;
    
    @Resource
    private HybridReducer hybridReducer;
    
    @Resource
    private ToolResultProvider toolResultProvider;

    public ChatContextService(List<ContextProvider> providers) {
        this.providers = providers;
        this.providers.sort(Comparator.comparingInt(ContextProvider::getOrder));
    }

    /**
     * 聚合所有已启用的 ContextProvider，构建最终给 PromptService 使用的 PromptContextVO。
     *
     * 执行过程：
     * 
     *   1. Spring 已经把全部 ContextProvider 注入到 providers
     *   2. 构造函数中已按 getOrder() 从小到大排序
     *   3. 这里依次调用 provider.provide(...)
     *   4. 每个 provider 返回一个 Map，上下文字段通过 putAll 合并到 finalCtx
     *   5. 最后把 finalCtx 映射为 PromptContextVO
     * 
     *
     * 案例：
     * 
     *   当前 providers 返回结果：
     *
     *   TerminalStateProvider ->
     *   {
     *     osInfo=Linux ubuntu 22.04,
     *     currentUser=root,
     *     currentDirectory=/var/log/nginx
     *   }
     *
     *   TaskProvider ->
     *   {
     *     taskDescription=排查 nginx 502 错误
     *   }
     *
     *   MilestoneProvider ->
     *   {
     *     milestoneVOS=[用户要求查看 error.log, 工具执行出现 permission denied]
     *   }
     *
     *   ToolResultProvider ->
     *   {
     *     toolResultSummary=最近一次 tail error.log 输出包含 connect() failed
     *   }
     *
     *   最终聚合结果：
     *   PromptContextVO {
     *     osInfo=Linux ubuntu 22.04,
     *     currentUser=root,
     *     currentDirectory=/var/log/nginx,
     *     taskDescription=排查 nginx 502 错误,
     *     milestoneVOS=[...],
     *     toolResultSummary=最近一次 tail error.log 输出包含 connect() failed
     *   }
     * 
     *
     * 这样 PromptService 后面就不需要再分别调用多个 provider，
     * 它只需要消费一个聚合好的 PromptContextVO 即可。
     *
     * @param sessionId 当前会话 ID
     * @param userId 用户 ID
     * @param messageHistory 当前业务侧消息历史
     * @return 聚合完成的 PromptContextVO
     */
    @Override
    @SuppressWarnings("unchecked")
    public PromptContextVO buildPromptContext(String sessionId, String userId, List<Map<String, Object>> messageHistory) {
        Map<String, Object> finalCtx = new HashMap<>();
        Map<String, Object> stableContext = new HashMap<>();
        Map<String, Object> ephemeralContext = new HashMap<>();

        for (ContextProvider provider : providers) {
            if (!provider.enabled()) continue;
            // 每个 Provider 只负责自己那一小块上下文，统一在这里汇总收口。
            Map<String, Object> ctx = provider.provide(sessionId, userId, messageHistory);
            if (ctx != null) {
                finalCtx.putAll(ctx);
                if (provider.getPlacement() == ContextPlacement.STABLE_PREFIX) {
                    stableContext.putAll(ctx);
                } else {
                    ephemeralContext.putAll(ctx);
                }
            }
        }

        return PromptContextVO.builder()
                .osInfo((String) finalCtx.get("osInfo"))
                .milestoneVOS((List<MilestoneVO>) finalCtx.get("milestoneVOS"))
                .toolResultSummary((String) finalCtx.get("toolResultSummary"))
                // 长期记忆摘要：由 LongTermMemoryProvider(order=25) 召回并注入，
                // 经 DynamicPromptBuilder 渲染为 [长期记忆] 段落拼到用户消息前面。
                .longTermMemorySummary((String) finalCtx.get("longTermMemorySummary"))
                .taskDescription((String) finalCtx.get("taskDescription"))
                .stableContext(stableContext)
                .ephemeralContext(ephemeralContext)
                .build();
    }

    /**
     * 按 token 预算裁剪业务侧消息历史。
     *
     * 当前实际使用的是 HybridReducer，它内部会组合：
     * 
     *   PriorityReducer：保证关键错误、关键路径、tool 调用入口尽量不丢
     *   SlidingWindowReducer：保证最近一段连续上下文尽量不丢
     *   最近 2 个完整消息组保底：保证当前轮上下文不被裁断
     * 
     *
     * 案例：
     * 
     *   原始历史：
     *   1. system: 你是运维助手
     *   2. user: 查看 /etc/nginx/nginx.conf
     *   3. assistant: 很长的解释...
     *   4. assistant(tool_calls)
     *   5. tool: permission denied
     *   6. user: 改查 /var/log/nginx/error.log
     *
     *   假设 tokenBudget 不够保留全部消息。
     *
     *   经过 HybridReducer 后，可能得到：
     *   1. system: 你是运维助手
     *   2. user: 查看 /etc/nginx/nginx.conf
     *   4. assistant(tool_calls)
     *   5. tool: permission denied
     *   6. user: 改查 /var/log/nginx/error.log
     * 
     *
     * 也就是说，它不是简单按条数截断，而是尽量兼顾：
     * 重要性、时效性、工具调用链完整性。
     *
     * @param history 原始消息历史
     * @param tokenBudget token 预算；小于等于 0 时回退到默认值 8000
     * @return 裁剪后的消息历史
     */
    @Override
    public List<Map<String, Object>> trimHistory(List<Map<String, Object>> history, int tokenBudget) {
        if (history == null || history.isEmpty()) return Collections.emptyList();
        // 混合裁剪：既保关键消息，也保最近连续上下文。
        return hybridReducer.reduce(history, tokenBudget > 0 ? tokenBudget : DEFAULT_MAX_CONTEXT_TOKENS);
    }
    
    /**
     * 推送一条工具执行结果到会话级缓存。
     *
     * 这个方法本身非常轻，它只是把动作转发给 ToolResultProvider，
     * 但在整条 ReAct 链路里很关键，因为它决定了“本轮工具执行结果”能不能进入下一轮 Prompt。
     *
     * 案例：
     * 
     *   本轮 AI 调用了 tail_log 工具，执行结果为：
     *   "[error] connect() failed (111: Connection refused)"
     *
     *   调用：
     *   pushToolResult("session-001", "tail_log",
     *       "[error] connect() failed (111: Connection refused)")
     *
     *   效果：
     *   1. ToolResultProvider 把这条结果写入 session-001 的结果缓存
     *   2. 原有工具摘要失效
     *   3. 下一轮 buildPromptContext() 时，会重新生成新的 toolResultSummary
     * 
     *
     * @param sessionId 当前会话 ID
     * @param toolName 工具名称
     * @param result 工具执行结果
     */
    @Override
    public void pushToolResult(String sessionId, String toolName, String result) {
        toolResultProvider.pushResult(sessionId, toolName, result);
    }

    /**
     * 清理指定会话的上下文缓存。
     *
     * 当前主要清理的是 ToolResultProvider 内部维护的会话级工具结果缓存，
     * 用于避免一次对话结束后，上一轮工具执行结果继续污染下一轮上下文。
     *
     * 案例：
     * 
     *   session-001 在本轮执行中累计了：
     *   - ls /var/log 结果
     *   - tail error.log 结果
     *   - cat nginx.conf 结果
     *
     *   当本轮完成，调用：
     *   clearSessionContext("session-001")
     *
     *   效果：
     *   ToolResultProvider.clear("session-001")
     *   -> 清空该会话的工具结果缓存和对应摘要缓存
     * 
     *
     * 这样下一次新的会话轮次开始时，不会继续沿用上一次已经失效的工具执行结果。
     *
     * @param sessionId 当前会话 ID
     */
    @Override
    public void clearSessionContext(String sessionId) {
        if (sessionId != null) {
            toolResultProvider.clear(sessionId);
        }
    }

}
