package com.xlc.ai.domain.agent.service.context.reducer.impl;

import com.xlc.ai.domain.agent.service.context.reducer.MessageReducer;
import lombok.AllArgsConstructor;
import lombok.Data;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 混合裁剪器（ChatContextService 实际使用的裁剪策略）
 * 
 * 功能：组合两种裁剪器的优点——PriorityReducer 保证"重要的不丢"，
 * SlidingWindowReducer 保证"新的不丢"。整体策略不再简单取交集，
 * 而是以 PriorityReducer 为主结果，再补充 SlidingWindowReducer 中近期且有价值的缺失消息，
 * 最后统一保底最近 2 个完整消息组，兼顾重要性、时效性和消息配对完整性。
 * 
 * 运行过程：
 * 
 *   messages ──+--> PriorityReducer.reduce()      --> 主保留集合 A（按重要性、按组裁剪）
 *              |
 *              +--> SlidingWindowReducer.reduce() --> 候选集合 B（按时效、按组裁剪）
 *                          |
 *                          v
 *             keep = A + (B 中尚未保留的消息，按原顺序补充)
 *                          |
 *                          v
 *                保底：强制加入最近 2 个完整消息组
 *                          |
 *                          v
 *                   按原顺序输出最终消息
 * 
 *
 * @author xlvchao
 */
@Component
public class HybridReducer implements MessageReducer {

    /**
     * 负责按重要性筛选历史消息，并尽量保留关键错误、路径和工具调用链。
     */
    @Resource
    private PriorityReducer priorityReducer;

    /**
     * 负责按时间顺序保留最近的消息组，避免当前对话上下文断层。
     */
    @Resource
    private SlidingWindowReducer slidingReducer;

    /**
     * 执行混合裁剪，把“重要历史”和“近期上下文”合并为一份按原始顺序排列的消息列表。
     *
     * 这里采用"优先级主导 + 最近窗口补充 + 完整消息组保底"的组合策略：
     * 
     *   PriorityReducer 决定哪些历史消息最值得保留
     *   SlidingWindowReducer 提供最近上下文补充，避免主策略过度偏向旧关键消息
     *   最近 2 个完整消息组强制保底，确保当前轮上下文不被破坏
     * 
     *
     * 案例：
     * 
     *   原始消息组：
     *   G1=[system: 你是运维助手]
     *   G2=[user: 查看 /etc/nginx/nginx.conf]
     *   G3=[assistant: 很长的分析说明（如果你用waliapi的话，可以看过调用过程中的日志信息）...]
     *   G4=[assistant(tool_calls), tool: permission denied]
     *   G5=[user: 那你改查 error.log]
     *   G6=[assistant(tool_calls), tool: tail 结果]
     *
     *   PriorityReducer 可能给出：
     *   A = [G1, G2, G4, G5]
     *
     *   SlidingWindowReducer 可能给出：
     *   B = [G4, G5, G6]
     *
     *   HybridReducer 合并过程：
     *   1. 先保留 A
     *   2. 再把 B 中 A 还没有的 G6 补进来
     *   3. 最后再强制保底最近 2 组（这里就是 G5、G6）
     *
     *   最终结果：
     *   [G1, G2, G4, G5, G6]
     * 
     *
     * 这样可以同时避免两种极端：
     * 一种是只看优先级，导致最近上下文断层；
     * 另一种是只看最近窗口，导致关键错误和关键指令被冲掉。
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

        List<Map<String, Object>> priorityResult = priorityReducer.reduce(messages, tokenBudget);
        List<Map<String, Object>> slidingResult = slidingReducer.reduce(messages, tokenBudget);

        // 第一步：先收 PriorityReducer 的结果，作为"重要历史主集合"。
        Set<Integer> keepIndices = new LinkedHashSet<>(indexSet(priorityResult, messages));

        // 用最近窗口结果补充优先级裁剪中未保留的消息，强化当前轮上下文连续性。
        keepIndices.addAll(indexSet(slidingResult, messages));

        // 统一保底最近 2 个完整消息组，而不是最近 2 条单消息，避免把工具调用链截断。
        List<MessageGroup> groups = groupMessages(messages);
        int minKeepGroups = Math.min(2, groups.size());
        for (int i = groups.size() - minKeepGroups; i < groups.size(); i++) {
            keepIndices.addAll(groups.get(i).getIndices());
        }

        List<Map<String, Object>> result = new ArrayList<>();
        for (int i = 0; i < messages.size(); i++) {
            if (keepIndices.contains(i)) {
                result.add(messages.get(i));
            }
        }
        return result;
    }

    /**
     * 将子集消息映射回原始列表索引。
     *
     * 这里不再使用 indexOf(msg) 的内容匹配方式，而是使用"按引用顺序扫描"的稳定映射：
     * 同一个 Map 实例在原始列表里只会匹配一次，可避免内容相同消息被错误映射到首个位置。
     *
     * 案例：
     * 
     *   all 中有两条内容完全一样的消息：
     *   0 -> {role=user, content="继续"}
     *   5 -> {role=user, content="继续"}
     *
     *   如果直接用 indexOf，会永远命中索引 0。
     *   现在改成"按引用 + used[]"扫描后：
     *   - 第一次匹配到 0
     *   - 第二次匹配到 5
     * 
     *
     * 这样可以确保合并两个 reducer 结果时，保留的是"原始消息中的正确位置"，
     * 而不是"内容长得像的第一条消息"。
     *
     * @param subset 裁剪结果子集
     * @param all 原始消息全集
     * @return 对应原始消息索引集合
     */
    private Set<Integer> indexSet(List<Map<String, Object>> subset, List<Map<String, Object>> all) {
        Set<Integer> indices = new LinkedHashSet<>();
        boolean[] used = new boolean[all.size()];

        for (Map<String, Object> msg : subset) {
            for (int i = 0; i < all.size(); i++) {
                if (!used[i] && all.get(i) == msg) {
                    indices.add(i);
                    used[i] = true;
                    break;
                }
            }
        }

        return indices;
    }

    /**
     * 将原始消息切分为消息组。
     *
     * 分组规则与其他 reducer 保持一致，确保在整个智能体调用链中，
     * "完整上下文单元"的定义一致，不会在不同 reducer 之间出现理解偏差。
     *
     * 案例：
     * 
     *   原始消息序列：
     *   0: user: 查看磁盘
     *   1: assistant: tool_calls=[call_1]
     *   2: tool: tool_call_id=call_1, content="磁盘 80%"
     *   3: assistant: 建议清理日志
     *
     *   分组结果：
     *   G1 = [0]      （普通 user 消息，单独成组）
     *   G2 = [1, 2]   （assistant 发出 tool_calls，后续紧跟的 tool result 并入同组）
     *   G3 = [3]      （普通 assistant 回复，单独成组）
     * 
     *
     * HybridReducer 这里只记录索引，不直接保存消息内容，
     * 因为它最终关心的是"哪些原始位置需要保留"，方便在两个 reducer 结果合并后统一恢复顺序。
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
                List<Integer> indices = new ArrayList<>();
                indices.add(index);

                // assistant 声明的这批 tool_call_id，决定后面哪些 tool result 应该并入同组。
                Set<String> toolCallIds = extractToolCallIds(message);
                int next = index + 1;
                while (next < messages.size()) {
                    Map<String, Object> candidate = messages.get(next);
                    if (isMatchingToolResult(candidate, toolCallIds)) {
                        indices.add(next);
                        next++;
                        continue;
                    }
                    break;
                }

                groups.add(new MessageGroup(indices, index));
                index = next;
                continue;
            }

            groups.add(new MessageGroup(List.of(index), index));
            index++;
        }

        return groups;
    }

    /**
     * 判断一条 assistant 消息是否为工具调用入口消息。
     *
     * 案例：
     * 
     *   messageA = {role=assistant, tool_calls=[{id=call_1, type=...}]}
     *              -> true  （assistant 且有 tool_calls，是工具调用入口）
     *
     *   messageB = {role=assistant, content="根据磁盘情况，建议清理日志"}
     *              -> false （assistant 但没有 tool_calls，是普通回复）
     *
     *   messageC = {role=tool, tool_call_id=call_1, content="磁盘 80%"}
     *              -> false （role 不是 assistant）
     * 
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
     * 案例：
     * 
     *   messageA = {role=assistant, tool_calls=[{id=call_1, type="bash"}]} -> true
     *   messageB = {role=assistant, content="直接回复，无工具调用"}         -> false（无 tool_calls 字段）
     *   messageC = {role=assistant, tool_calls=[]}                         -> false（tool_calls 为空列表）
     *   messageD = {role=tool, content="结果"}                             -> false（role 不是 assistant）
     * 
     *
     * @param message 待判断消息
     * @return true 表示消息中存在非空 tool_calls 列表
     */
    private boolean hasToolCalls(Map<String, Object> message) {
        Object toolCalls = message.get("tool_calls");
        return toolCalls instanceof List<?> list && !list.isEmpty();
    }

    /**
     * 提取 assistant 工具调用消息中的全部 tool_call_id。
     *
     * 案例：
     * 
     *   message = {role=assistant, tool_calls=[
     *                {id="call_1", function={name="bash"}},
     *                {id="call_2", function={name="read_file"}}
     *              ]}
     *   -> {"call_1", "call_2"}
     *
     *   message = {role=assistant, tool_calls=[]}
     *   -> {} （空列表，返回空集合）
     *
     *   message = {role=user, content="hello"}
     *   -> {} （无 tool_calls 字段，返回空集合）
     * 
     *
     * @param message assistant 工具调用消息
     * @return tool_call_id 集合
     */
    @SuppressWarnings("unchecked")
    private Set<String> extractToolCallIds(Map<String, Object> message) {
        Set<String> toolCallIds = new LinkedHashSet<>();
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
     *
     * 案例：
     * 
     *   当前组 toolCallIds = {"call_1"}
     *
     *   messageA = {role=tool, tool_call_id=call_1} -> true
     *   messageB = {role=tool, tool_call_id=call_2} -> false
     * 
     *
     * @param message 待匹配消息
     * @param toolCallIds 当前工具调用组的 id 集合
     * @return true 表示该消息是匹配结果
     */
    private boolean isMatchingToolResult(Map<String, Object> message, Set<String> toolCallIds) {
        if (!"tool".equals(stringValue(message.get("role")))) {
            return false;
        }
        String toolCallId = stringValue(message.get("tool_call_id"));
        return !toolCallId.isEmpty() && toolCallIds.contains(toolCallId);
    }

    /**
     * 安全获取对象字符串值，避免 null 干扰判断逻辑。
     *
     * 案例：
     * 
     *   stringValue("assistant") -> "assistant"
     *   stringValue(null)        -> ""  （null 转为空字符串，避免 NPE）
     *   stringValue(123)         -> "123"  （数字对象转字符串）
     *   stringValue(true)        -> "true"  （布尔对象转字符串）
     * 
     *
     * 该方法是各判断逻辑的底层辅助，确保无论 Map 中存的是 String、Integer 还是其他类型，
     * 都能安全地进行字符串比较，不会出现 NullPointerException。
     *
     * @param value 原始对象
     * @return 非 null 字符串，null 时返回空字符串
     */
    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    /**
     * 消息组。
     *
     * HybridReducer 不直接关心组内消息内容，只关心组覆盖的原始索引范围，
     * 以便在最终合并阶段稳定地保留完整上下文单元。
     */
    @Data
    @AllArgsConstructor
    private static class MessageGroup {
        /** 该消息组覆盖的原始消息索引列表。 */
        private List<Integer> indices;
        /** 该组在原始消息列表中的起始位置。 */
        private int startIndex;
    }

}
