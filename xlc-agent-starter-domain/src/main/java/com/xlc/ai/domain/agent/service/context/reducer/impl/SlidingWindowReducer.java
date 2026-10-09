package com.xlc.ai.domain.agent.service.context.reducer.impl;

import com.xlc.ai.domain.agent.service.context.reducer.MessageReducer;
import lombok.AllArgsConstructor;
import lombok.Data;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 滑动窗口裁剪器
 * 
 * 功能：保留"最近"的消息组——从新到旧按组装入窗口，
 * 受"窗口组数（20）+ token 预算"双重限制，任一超限即停止。
 * 时效性保障：最近的完整对话轮次和工具调用链对当前推理最重要。
 * 
 * 运行过程：
 * 
 *   messages --> 按消息组切分（tool 调用链成组，普通消息单独成组）
 *        |
 *        v
 *   从最后一个消息组向前扫描
 *        |
 *        +-- 已保留组数 >= 20 ?              --是--> 停止
 *        +-- usedTokens + groupTokens > 预算 ? --是--> 停止
 *        |
 *        v
 *   window.add(0, group)   从头部插入，保持时间正序
 *        |
 *        v
 *   返回 window（最近 <=20 个消息组且总 token 不超预算的消息）
 * 
 * token 估算：粗略按 content.length()/2（2 个字符约 1 token）。
 *
 * @author xlvchao
 */
@Component
public class SlidingWindowReducer implements MessageReducer {
    private static final int DEFAULT_WINDOW_GROUP_SIZE = 20;

    /**
     * 按消息组执行滑动窗口裁剪。
     *
     * 这里不再按“单条消息”滑动，而是按“完整消息组”滑动，
     * 目的是保证 assistant tool_calls 与后续 tool result 不会在窗口裁剪时被拆散。
     *
     * 案例：
     * 
     *   原始消息（已经先按 groupMessages 切分成 5 组）：
     *   G1=[user: 登录服务器]
     *   G2=[assistant(tool_calls: ls), tool(ls结果)]
     *   G3=[user: 再看 nginx 日志]
     *   G4=[assistant(tool_calls: tail), tool(tail结果)]
     *   G5=[assistant: 我已经定位到报错原因]
     *
     *   假设 tokenBudget 只够放下 G3 + G4 + G5
     *
     *   裁剪过程：
     *   1. 从最后一组 G5 开始回收
     *   2. 再尝试加入 G4
     *   3. 再尝试加入 G3
     *   4. 继续尝试 G2 时，如果超预算，则停止，不再向前拿 G1
     *
     *   最终结果：
     *   [G3, G4, G5]
     * 
     *
     * 这里一旦某个更早的消息组超预算就直接停止，而不是“跳过当前组继续找更早的组”，
     * 因为滑动窗口的目标是保留“最近的一段连续上下文”，而不是在旧历史里做离散挑选。
     *
     * @param messages 原始消息列表
     * @param tokenBudget token 预算
     * @return 裁剪后的消息列表
     */
    @Override
    public List<Map<String, Object>> reduce(List<Map<String, Object>> messages, int tokenBudget) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }

        List<MessageGroup> groups = groupMessages(messages);
        if (groups.isEmpty()) {
            return List.of();
        }

        List<MessageGroup> window = new ArrayList<>();
        int usedTokens = 0;

        // 从最新消息组向前回收，优先保留最近轮次的完整上下文。
        for (int i = groups.size() - 1; i >= 0; i--) {
            MessageGroup group = groups.get(i);
            int groupTokens = estimateTokens(group);
            if (window.size() >= DEFAULT_WINDOW_GROUP_SIZE || usedTokens + groupTokens > tokenBudget) {
                break;
            }
            window.add(0, group);
            usedTokens += groupTokens;
        }

        return window.stream()
            .sorted(Comparator.comparingInt(MessageGroup::getStartIndex))
            .flatMap(group -> group.getMessages().stream())
            .collect(Collectors.toList());
    }

    /**
     * 将原始消息切分为消息组。
     *
     * 分组规则与 PriorityReducer 保持一致，保证整个智能体调用链中，
     * 不同裁剪器对“完整上下文单元”的理解一致。
     *
     * 案例：
     * 
     *   1. user: 帮我看磁盘空间
     *   2. assistant: tool_calls=[df -h]
     *   3. tool: tool_call_id=call_1, content=...
     *   4. assistant: 磁盘空间正常
     *
     *   切分结果：
     *   G1 = [1]
     *   G2 = [2, 3]
     *   G3 = [4]
     * 
     *
     * 注意第 2、3 条虽然是两条消息，但语义上是一件事：
     * “assistant 发起工具调用 + tool 返回执行结果”。
     * 如果把它们拆开裁剪，模型可能只看到“调用了工具”却看不到结果。
     *
     * @param messages 原始消息列表
     * @return 消息组列表
     */
    private List<MessageGroup> groupMessages(List<Map<String, Object>> messages) {
        List<MessageGroup> groups = new ArrayList<>();
        int index = 0;

        while (index < messages.size()) {
            Map<String, Object> message = messages.get(index);
            if (isToolCallAssistant(message)) {
                List<Map<String, Object>> groupedMessages = new ArrayList<>();
                groupedMessages.add(message);

                // assistant 这一条可能一次声明多个 tool_call_id，
                // 所以后续需要把所有属于这次调用批次的 tool result 一起并入同一组。
                Set<String> toolCallIds = extractToolCallIds(message);
                int next = index + 1;
                while (next < messages.size()) {
                    Map<String, Object> candidate = messages.get(next);
                    // 只要后面的消息仍然是这批 tool_call_id 的结果，就继续吸收到当前组里。
                    if (isMatchingToolResult(candidate, toolCallIds)) {
                        groupedMessages.add(candidate);
                        next++;
                        continue;
                    }
                    // 一旦遇到非匹配消息，说明当前工具调用链已经结束，后面应开启新分组。
                    break;
                }

                groups.add(new MessageGroup(groupedMessages, index));
                index = next;
                continue;
            }

            groups.add(new MessageGroup(List.of(message), index));
            index++;
        }

        return groups;
    }

    /**
     * 判断一条 assistant 消息是否为工具调用入口消息。
     *
     * @param message 待判断消息
     * @return true 表示该消息包含 tool_calls
     */
    private boolean isToolCallAssistant(Map<String, Object> message) {
        return "assistant".equals(stringValue(message.get("role"))) && hasToolCalls(message);
    }

    /**
     * 判断消息是否存在非空 tool_calls 列表。
     *
     * @param message 待判断消息
     * @return true 表示存在 tool_calls
     */
    private boolean hasToolCalls(Map<String, Object> message) {
        Object toolCalls = message.get("tool_calls");
        return toolCalls instanceof List<?> list && !list.isEmpty();
    }

    /**
     * 提取 assistant 工具调用消息中的全部 tool_call_id。
     *
     * @param message assistant 工具调用消息
     * @return tool_call_id 集合
     */
    @SuppressWarnings("unchecked")
    private Set<String> extractToolCallIds(Map<String, Object> message) {
        Set<String> toolCallIds = new HashSet<>();
        Object toolCalls = message.get("tool_calls");
        if (!(toolCalls instanceof List<?> list)) {
            return toolCallIds;
        }

        for (Object item : list) {
            if (item instanceof Map<?, ?> call) {
                Object id = ((Map<String, Object>) call).get("id");
                if (id != null) {
                    toolCallIds.add(String.valueOf(id));
                }
            }
        }
        return toolCallIds;
    }

    /**
     * 判断一条消息是否属于当前工具调用组的 tool result。
     * 兼容 OpenAI (role=tool, tool_call_id) 和 Anthropic (type=tool_result, tool_use_id)
     *
     * 案例：
     * 
     *   assistant.tool_calls = [call_1, call_2]
     *
     *   后续消息：
     *   - tool(tool_call_id=call_1)  -> 匹配
     *   - tool(tool_call_id=call_2)  -> 匹配
     *   - assistant("继续分析")      -> 不匹配，说明这一组结束
     * 
     *
     * @param message 待匹配消息
     * @param toolCallIds 当前工具调用组的 id 集合
     * @return true 表示该消息是匹配结果
     */
    private boolean isMatchingToolResult(Map<String, Object> message, Set<String> toolCallIds) {
        String role = stringValue(message.get("role"));
        String type = stringValue(message.get("type"));
        
        if (!"tool".equals(role) && !"tool_result".equals(type)) {
            return false;
        }
        
        String toolCallId = stringValue(message.get("tool_call_id"));
        if (toolCallId.isEmpty()) {
            toolCallId = stringValue(message.get("tool_use_id"));
        }
        
        return !toolCallId.isEmpty() && toolCallIds.contains(toolCallId);
    }

    /**
     * 安全获取对象字符串值，避免 null 干扰判断逻辑。
     *
     * @param value 原始对象
     * @return 非 null 字符串
     */
    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    /**
     * 估算单条消息 token 数。
     *
     * 这里采用非常轻量的经验估算：content.length() / 2。
     * 目的不是得到绝对精确的 token 数，而是给裁剪算法一个“够用的体积感知”。
     *
     * 案例：
     * 
     *   content 长度 = 200 字符
     *   估算 token = 100
     * 
     *
     * @param message 单条消息
     * @return 估算 token 数
     */
    private int estimateToken(Map<String, Object> message) {
        String content = stringValue(message.get("content"));
        return content.length() / 2;
    }

    /**
     * 统计单个消息组的 token 消耗。
     *
     * 案例：
     * 
     *   group = [
     *     assistant(tool_calls, content 长度 40),
     *     tool(result, content 长度 200)
     *   ]
     *
     *   groupTokens = 40/2 + 200/2 = 120
     * 
     *
     * @param group 消息组
     * @return 该组总 token 数
     */
    private int estimateTokens(MessageGroup group) {
        return group.getMessages().stream().mapToInt(this::estimateToken).sum();
    }

    /**
     * 消息组。
     *
     * 滑动窗口阶段按组保留，保证上下文单元完整。
     */
    @Data
    @AllArgsConstructor
    private static class MessageGroup {
        /** 组内消息，按原始顺序保存。 */
        private List<Map<String, Object>> messages;
        /** 该组在原始消息列表中的起始索引。 */
        private int startIndex;
    }

}
