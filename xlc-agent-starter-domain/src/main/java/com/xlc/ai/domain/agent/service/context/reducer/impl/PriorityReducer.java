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
 * 优先级裁剪器
 * 
 * 功能：按消息组推断优先级，预算不足时优先丢弃"不重要"的消息组，
 * 保证错误信息、关键指令以及 tool_call / tool_result 配对关系不被裁坏。
 * 
 * 优先级规则（inferPriority）：
 * 
 *   CRITICAL : tool 结果含 error/failed/exception/permission denied
 *              （错误必须让模型看到，否则会在同一个坑反复失败）
 *   HIGH     : system 消息；user 消息含 / 、.conf、.yml、.properties；
 *              assistant 消息包含 tool_calls
 *   LOW      : assistant 回复 > 5000 字符（多为冗长输出，可丢）
 *   MEDIUM   : 其余消息
 * 
 * 运行过程：
 * 
 *   messages --> 按消息组切分（tool 调用链成组，普通消息单独成组）
 *        |
 *        v
 *   保底：先保留最近 2 个消息组
 *        |
 *        v
 *   剩余消息组按 priority 高到低、同级按时间近到远回填
 *        |
 *        v
 *   返回 kept（保持时间正序）
 * 
 *
 * @author xlvchao
 */
@Component
public class PriorityReducer implements MessageReducer {

    /**
     * 按消息组执行上下文裁剪。
     *
     * 裁剪策略分为 4 步：
     * 
     * 1. 先把原始消息切成“消息组”，避免 tool_call / tool_result 被拆散
     * 2. 无条件保留最近 2 个消息组，保证当前轮上下文完整
     * 3. 对其余历史消息组按优先级从高到低、同级按时间从近到远尝试回填
     * 4. 最终再按原始顺序输出，保证模型看到的消息时间线不乱序
     * 
     *
     * 案例：
     * 
     *   原始消息组：
     *   G1=[system: 你是运维助手]                         HIGH
     *   G2=[user: 帮我看 /etc/nginx/nginx.conf]          HIGH
     *   G3=[assistant: 很长很长的解释文本...]             LOW
     *   G4=[assistant(tool_calls), tool: permission denied] CRITICAL
     *   G5=[user: 那你改看 /var/log/nginx/error.log]     HIGH
     *
     *   假设预算有限，只够保留 4 个组。
     *
     *   裁剪过程：
     *   1. 先保底最近 2 组：G4、G5
     *   2. 再从更早历史里按优先级回填：优先 G1、G2，丢弃 G3
     *   3. 最终结果按原始时间顺序恢复为：[G1, G2, G4, G5]
     * 
     *
     * 这类裁剪器的核心目标不是“越新越好”，而是“关键历史不要丢”。
     * 所以它比滑动窗口更适合保留错误、配置路径、tool 调用入口这类高价值信息。
     *
     * @param messages   原始消息列表
     * @param tokenBudget token 预算
     * @return 裁剪后的消息列表
     */
    @Override
    public List<Map<String, Object>> reduce(List<Map<String, Object>> messages, int tokenBudget) {
        // 空输入直接返回空列表，避免后续 subList / 排序逻辑做无意义处理。
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }

        // 先将消息归并成“可裁剪单元”，普通消息单独成组，工具调用链按组处理。
        List<MessageGroup> groups = groupMessages(messages);
        if (groups.isEmpty()) {
            return List.of();
        }

        // 至少保留最近 2 个消息组，优先保障当前轮对话和最近一次工具调用链路完整。
        int minKeep = Math.min(2, groups.size());
        int startIndex = groups.size() - minKeep;

        // kept 先放入保底消息组，后续只在预算允许时继续补充更早的历史组。
        List<MessageGroup> kept = new ArrayList<>(groups.subList(startIndex, groups.size()));
        int usedTokens = estimateTokens(kept);

        // 候选消息组只来自更早历史，避免和“最近必保留组”重复。
        List<MessageGroup> candidates = new ArrayList<>(groups.subList(0, startIndex));
        candidates.sort(Comparator
            // 高优先级优先保留，例如错误结果、关键 system/user 指令、tool_calls。
            .comparing((MessageGroup g) -> g.getPriority().weight()).reversed()
            // 同优先级时，越新的历史组越优先保留，尽量让上下文更贴近当前问题。
            .thenComparing(MessageGroup::getStartIndex, Comparator.reverseOrder()));

        // 按排序后的优先级依次尝试回填，只要还没超预算就整组加入。
        for (MessageGroup group : candidates) {
            int groupTokens = estimateTokens(group);
            if (usedTokens + groupTokens <= tokenBudget) {
                kept.add(group);
                usedTokens += groupTokens;
            }
        }

        // 虽然回填阶段按优先级选组，但最终输出必须恢复原始时间顺序。
        kept.sort(Comparator.comparing(MessageGroup::getStartIndex));
        return kept.stream()
            .flatMap(group -> group.getMessages().stream())
            .collect(Collectors.toList());
    }

    /**
     * 将原始消息切分为消息组。
     *
     * 规则：
     * 
     *   普通 user / system / assistant 消息：单条成组
     *   assistant 中包含 tool_calls：与其后连续匹配的 tool result 合并成组
     * 
     *
     * 这样做的目的，是把工具调用请求与结果视作一个原子上下文单元，
     * 裁剪时只能整组保留或整组删除，不能留下半截历史。
     *
     * 案例：
     * 
     *   1. assistant: tool_calls=[call_1]
     *   2. tool: tool_call_id=call_1, content="permission denied"
     *   3. assistant: 我执行失败了
     *
     *   分组后：
     *   G1 = [1, 2]
     *   G2 = [3]
     * 
     *
     * 这样即便预算吃紧，G1 也会作为“完整失败链路”一起保留或一起删除，
     * 不会出现只留下第 1 条调用请求、却把第 2 条错误结果裁掉的情况。
     *
     * @param messages 原始消息列表
     * @return 切分后的消息组列表
     */
    private List<MessageGroup> groupMessages(List<Map<String, Object>> messages) {
        List<MessageGroup> groups = new ArrayList<>();
        int index = 0;

        while (index < messages.size()) {
            Map<String, Object> message = messages.get(index);
            if (isToolCallAssistant(message)) {
                // assistant 发起工具调用时，先把当前 assistant 消息加入组头。
                List<Map<String, Object>> groupedMessages = new ArrayList<>();
                groupedMessages.add(message);

                // 收集本次 assistant 工具调用中声明的全部 tool_call_id。
                Set<String> toolCallIds = extractToolCallIds(message);
                int next = index + 1;
                while (next < messages.size()) {
                    Map<String, Object> candidate = messages.get(next);
                    // 只要后续消息还是当前 tool_call_id 对应的 tool result，就继续并入同一组。
                    if (isMatchingToolResult(candidate, toolCallIds)) {
                        groupedMessages.add(candidate);
                        next++;
                        continue;
                    }
                    // 一旦遇到非匹配消息，说明当前工具调用链已经结束。
                    break;
                }

                groups.add(new MessageGroup(groupedMessages, inferPriority(groupedMessages), index));
                index = next;
                continue;
            }

            // 补充：兼容当前 ADK 自动执行分支缺失 assistant tool_calls 的情况，把孤立的 tool result 也单独成组
            // 避免 tool result 和下一个 assistant / user 合并导致越界
            groups.add(new MessageGroup(List.of(message), inferPriority(List.of(message)), index));
            index++;
        }

        return groups;
    }

    /**
     * 计算一个消息组的优先级。
     *
     * 当前策略取组内“最高优先级”作为整组优先级，原因是：
     * 只要组里包含一条关键消息（如错误 tool result），整组都应该被优先保留。
     *
     * 案例：
     * 
     *   group = [
     *     assistant(tool_calls),          -> HIGH
     *     tool("permission denied")       -> CRITICAL
     *   ]
     *
     *   最终组优先级 = CRITICAL
     * 
     *
     * @param messages 消息组内的消息列表
     * @return 该消息组的优先级
     */
    private Priority inferPriority(List<Map<String, Object>> messages) {
        return messages.stream()
            .map(this::inferPriority)
            .max(Comparator.comparingInt(Priority::weight))
            .orElse(Priority.MEDIUM);
    }

    /**
     * 计算单条消息的优先级。
     *
     * 优先级只负责描述“重要性”，具体保留顺序在 reduce 方法中统一处理。
     *
     * 典型判断样例：
     * 
     *   tool("permission denied")              -> CRITICAL
     *   assistant(tool_calls=[...])            -> HIGH
     *   user("查看 /etc/nginx/nginx.conf")      -> HIGH
     *   system("你是 Linux 运维助手")            -> HIGH
     *   assistant(超长说明 6000 字符)            -> LOW
     *   普通 user / assistant 文本               -> MEDIUM
     * 
     *
     * @param message 单条消息
     * @return 消息优先级
     */
    private Priority inferPriority(Map<String, Object> message) {
        String role = stringValue(message.get("role"));
        String content = stringValue(message.get("content"));

        // 工具报错信息最关键，必须尽量保留给模型用于纠错。
        if (("tool".equals(role) || "tool_result".equals(stringValue(message.get("type")))) 
            && containsAny(content, "error", "failed", "exception", "permission denied")) {
            return Priority.CRITICAL;
        }
        // assistant 里出现 tool_calls，说明这是调用工具的入口消息，和 tool result 配对价值很高。
        if ("assistant".equals(role) && hasToolCalls(message)) {
            return Priority.HIGH;
        }
        // 用户显式给出路径或配置文件时，通常是关键操作目标，不应轻易裁掉。
        if ("user".equals(role) && containsAny(content, "/", ".conf", ".yml", ".properties")) {
            return Priority.HIGH;
        }
        // system 指令默认高优先级，避免基础行为约束被裁掉。
        if ("system".equals(role)) {
            return Priority.HIGH;
        }
        // 超长 assistant 文本通常是冗长说明，优先级可降低。
        if ("assistant".equals(role) && content.length() > 5000) {
            return Priority.LOW;
        }
        return Priority.MEDIUM;
    }

    /**
     * 判断一条 assistant 消息是否为“工具调用入口消息”。
     *
     * @param message 待判断消息
     * @return true 表示该 assistant 消息包含 tool_calls
     */
    private boolean isToolCallAssistant(Map<String, Object> message) {
        return "assistant".equals(stringValue(message.get("role"))) && hasToolCalls(message);
    }

    /**
     * 判断消息中是否包含 tool_calls 字段。
     *
     * @param message 待判断消息
     * @return true 表示存在非空 tool_calls 列表
     */
    private boolean hasToolCalls(Map<String, Object> message) {
        Object toolCalls = message.get("tool_calls");
        return toolCalls instanceof List<?> list && !list.isEmpty();
    }

    /**
     * 从 assistant 的 tool_calls 中提取全部调用 ID。
     *
     * 一个 assistant 消息可能一次发起多个工具调用，因此这里返回 Set，
     * 供后续 tool result 匹配阶段统一判断。
     *
     * 案例：
     * 
     *   tool_calls = [
     *     {id=call_1, name="ls"},
     *     {id=call_2, name="tail"}
     *   ]
     *
     *   提取结果 = {"call_1", "call_2"}
     * 
     *
     * @param message assistant 工具调用消息
     * @return 当前消息声明的全部 tool_call_id
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
     * 判断一条消息是否是当前工具调用组对应的 tool result。
     * 兼容 OpenAI (role=tool, tool_call_id) 和 Anthropic (type=tool_result, tool_use_id)
     *
     * 案例：
     * 
     *   当前组 toolCallIds = {"call_1", "call_2"}
     *
     *   messageA = {role=tool, tool_call_id=call_1}      -> true
     *   messageB = {type=tool_result, tool_use_id=call_2} -> true
     *   messageC = {role=assistant, content="继续分析"}     -> false
     * 
     *
     * @param message 待匹配消息
     * @param toolCallIds 当前工具调用组持有的 tool_call_id 集合
     * @return true 表示该消息属于当前工具调用组
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
     * 判断文本是否包含任一关键字。
     *
     * @param content 待匹配文本
     * @param keywords 关键字列表
     * @return true 表示命中任一关键字
     */
    private boolean containsAny(String content, String... keywords) {
        if (content == null) return false;
        String lower = content.toLowerCase();
        for (String keyword : keywords) {
            if (lower.contains(keyword)) return true;
        }
        return false;
    }

    /**
     * 安全获取对象字符串值，避免 null 参与后续判断。
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
     * 这里用 content.length()/2 做粗略估算，重点是让裁剪逻辑知道
     * “这条消息大概有多重”，而不是追求与模型 tokenizer 完全一致。
     *
     * @param message 单条消息
     * @return 估算 token 数
     */
    private int estimateToken(Map<String, Object> message) {
        String content = stringValue(message.get("content"));
        return content.length() / 2;
    }

    /**
     * 统计多个消息组的总 token 消耗。
     *
     * 案例：
     * 
     *   kept = [G4, G5]
     *   estimateTokens(kept) = estimateTokens(G4) + estimateTokens(G5)
     * 
     *
     * @param groups 消息组列表
     * @return 总 token 数
     */
    private int estimateTokens(List<MessageGroup> groups) {
        return groups.stream().mapToInt(this::estimateTokens).sum();
    }

    /**
     * 统计单个消息组的 token 消耗。
     *
     * 案例：
     * 
     *   G4 = [assistant(tool_calls, 40 chars), tool(error, 120 chars)]
     *   groupTokens = 40/2 + 120/2 = 80
     * 
     *
     * @param group 消息组
     * @return 该组总 token 数
     */
    private int estimateTokens(MessageGroup group) {
        return group.getMessages().stream().mapToInt(this::estimateToken).sum();
    }

    /**
     * 优先级枚举，用于排序。
     *
     * 权重越大，越早参与回填。
     * 这里的 weight 不是给模型看的分数，而是裁剪阶段内部用于排序的比较值。
     */
    private enum Priority {
        CRITICAL(100),
        HIGH(80),
        MEDIUM(50),
        LOW(20);

        private final int weight;

        Priority(int weight) {
            this.weight = weight;
        }

        public int weight() {
            return weight;
        }
    }

    /**
     * 消息组。
     *
     * 将强相关的一组消息（如工具调用及其结果）绑定在一起，
     * 保证在裁剪和回填时同进同出，防止上下文断裂。
     */
    @Data
    @AllArgsConstructor
    private static class MessageGroup {
        /** 组内消息，按原始顺序保存。 */
        private List<Map<String, Object>> messages;
        /** 组优先级，通常取组内消息最高优先级。 */
        private Priority priority;
        /** 该组在原始消息列表中的起始索引，用于最终恢复时间顺序。 */
        private int startIndex;
    }

}
