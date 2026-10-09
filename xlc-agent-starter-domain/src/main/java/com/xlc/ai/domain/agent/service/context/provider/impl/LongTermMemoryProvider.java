package com.xlc.ai.domain.agent.service.context.provider.impl;

import com.xlc.ai.domain.agent.service.ILongTermMemoryService;
import com.xlc.ai.domain.agent.model.valobj.enums.ContextPlacement;
import com.xlc.ai.domain.agent.service.context.provider.ContextProvider;
import com.xlc.ai.domain.agent.service.memory.LongTermMemoryService;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 长期记忆上下文提供者（order=25）
 * 
 * 在 ReAct 循环构建 Prompt 时，自动召回与当前对话相关的长期记忆，
 * 输出 {@code longTermMemorySummary} 字段，经 DynamicPromptBuilder 渲染为 [长期记忆] 段落。
 * 
 * 排序 order=25，位于 TaskProvider(20) 之后、MilestoneProvider(30) 之前，
 * 表达"先确定任务、再召回记忆、最后收集里程碑"的语义优先级。
 * 
 * 召回查询构造策略：取消息历史中首条用户消息（初始目标）+ 最近用户消息（当前需求）拼接，
 * 保证召回的记忆既与全局任务相关，又与当前轮次相关。
 */
@Component
public class LongTermMemoryProvider implements ContextProvider {

    @Resource
    private ILongTermMemoryService longTermMemoryService;

    @Override
    public String getName() {
        return "long-term-memory";
    }

    /**
     * 排序权重 25，位于 TaskProvider(20) 与 MilestoneProvider(30) 之间
     */
    @Override
    public int getOrder() {
        return 25;
    }

    @Override
    public boolean enabled() {
        return true;
    }

    @Override
    public ContextPlacement getPlacement() {
        return ContextPlacement.STABLE_PREFIX;
    }

    /**
     * 召回长期记忆并构建摘要，注入 PromptContextVO.longTermMemorySummary。
     *
     * @param sessionId          会话 ID
     * @param userId             用户 ID
     * @param messageHistory     消息历史（用于构造召回查询）
     * @return 包含 longTermMemorySummary 的 Map，无记忆时返回空 Map
     */
    @Override
    public Map<String, Object> provide(String sessionId, String userId, List<Map<String, Object>> messageHistory) {
        Map<String, Object> result = new HashMap<>();
        String query = buildQuery(messageHistory);
        String summary = longTermMemoryService.buildMemorySummary(userId, query, 5);
        if (!summary.isBlank()) {
            result.put("longTermMemorySummary", summary);
        }
        return result;
    }

    /**
     * 构造召回查询：首条用户消息（初始目标）+ 最近用户消息（当前需求）拼接。
     * 
     * 首条用户消息代表会话的初始目标（如"排查 nginx 502"），
     * 最近用户消息代表当前轮次的具体需求（如"再看看 redis 连接池"），
     * 两者拼接保证召回的记忆既与全局任务相关，又与当前轮次相关。
     *
     * @param messageHistory 消息历史
     * @return 召回查询字符串，无用户消息时返回空字符串
     */
    private String buildQuery(List<Map<String, Object>> messageHistory) {
        if (messageHistory == null || messageHistory.isEmpty()) {
            return "";
        }

        String latestUser = "";
        String firstUser = "";
        for (Map<String, Object> message : messageHistory) {
            if (!"user".equals(message.get("role"))) {
                continue;
            }
            String content = String.valueOf(message.getOrDefault("content", ""));
            if (firstUser.isBlank()) {
                firstUser = content;
            }
            latestUser = content;
        }
        return (firstUser + " " + latestUser).trim();
    }
}
