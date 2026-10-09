package com.xlc.ai.domain.agent.service.context.provider.impl;

import com.xlc.ai.domain.agent.service.context.provider.ContextProvider;
import lombok.AllArgsConstructor;
import lombok.Data;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * 工具结果上下文提供者（order=40）
 * 
 * 功能：按会话缓存 ReAct 循环中的工具执行结果，生成"工具执行摘要"注入 Prompt，
 * 让模型在多轮工具调用后仍能全局回顾"之前执行过什么、结果如何"。
 * 
 * 运行过程：
 * 
 *   写入路径（ReAct 每轮工具执行后）：
 *   ReActAiCallNode/ReActToolCallNode --> ChatContextService.pushToolResult()
 *        |
 *        v
 *   pushResult(sessionId, toolName, result)
 *        |
 *        +--> results[sessionId].add(ToolResultEntry)   追加记录
 *        +--> summaryCache.remove(sessionId)            使摘要缓存失效
 *
 *   读取路径（下一轮构建上下文时）：
 *   provide(sessionId, ...)
 *        |
 *        v
 *   results[sessionId] 为空 ? --> 返回空 Map
 *        |
 *        v
 *   summaryCache.computeIfAbsent(sessionId, generateSummary)  懒摘要
 *        |
 *        +-- 条目 <= 5：逐条拼接 "toolName: 结果(截断100字)"
 *        +-- 条目 >  5："最近执行了 N 个工具调用" + 最近5条(截断80字)
 *        |
 *        v
 *   Map{ toolResultSummary } --> 消息前缀 [工具执行摘要] 段落
 * 
 * 缓存设计：摘要是"懒加载"的——provide 时若缓存命中直接返回；
 * 只有 pushResult 写入新结果才使缓存失效，避免每轮重复生成。
 *
 * @author xlvchao
 */
@Component
public class ToolResultProvider implements ContextProvider {

    private final Map<String, List<ToolResultEntry>> results = new ConcurrentHashMap<>();
    private final Map<String, String> summaryCache = new ConcurrentHashMap<>();

    private static final int MAX_ENTRIES_PER_SESSION = 50;

    @Override
    public String getName() {
        return "tool-result";
    }

    @Override
    public int getOrder() {
        return 40;
    }

    @Override
    public boolean enabled() {
        return true;
    }

    @Override
    public Map<String, Object> provide(String sessionId, String userId, List<Map<String, Object>> messageHistory) {
        Map<String, Object> result = new HashMap<>();
        List<ToolResultEntry> entries = results.getOrDefault(sessionId, Collections.emptyList());
        if (entries.isEmpty()) return result;

        // 懒摘要：有缓存直接返回，否则重新生成
        String summary = summaryCache.computeIfAbsent(sessionId, id -> generateSummary(entries));
        result.put("toolResultSummary", summary);
        return result;
    }

    /**
     * 推送一条工具执行结果到会话级缓存，并生成摘要。
     * 
     * 写入后使摘要缓存失效，下一轮 provide() 时重新生成摘要。
     * 
     * 案例 1：少量结果（≤5条）
     * 
     *   推送前：results["session-001"] = [ls_result, tail_result]
     *
     *   调用 pushResult("session-001", "cat", "配置文件内容...")
     *   -> results["session-001"] = [ls_result, tail_result, cat_result]
     *   -> summaryCache 失效
     *
     *   下一轮 provide() 时生成摘要：
     *   "ls: total 12\n- tail: tail -100 error.log\n- cat: 配置文件内容..."
     * 
     * 
     * 案例 2：大量结果（>5条，自动淘汰最旧的）
     * 
     *   推送前：results["session-001"] 已有 48 条
     *
     *   调用 pushResult("session-001", "grep", "匹配结果...")
     *   -> results["session-001"] = [...49条新记录]
     *   -> 超出 MAX_ENTRIES_PER_SESSION=50，淘汰最旧的 1 条
     *   -> 最终保留 50 条最近的结果
     * 
     */
    public void pushResult(String sessionId, String toolName, String result) {
        List<ToolResultEntry> entries = results.computeIfAbsent(sessionId, k -> new CopyOnWriteArrayList<>());
        entries.add(new ToolResultEntry(toolName, result));
        
        // 限制最大缓存条目数，防止内存泄露
        while (entries.size() > MAX_ENTRIES_PER_SESSION) {
            entries.remove(0);
        }
        
        summaryCache.remove(sessionId);  // 失效摘要缓存
    }

    public void clear(String sessionId) {
        if (sessionId != null) {
            results.remove(sessionId);
            summaryCache.remove(sessionId);
        }
    }

    /**
     * 生成工具执行摘要，用于注入 Prompt。
     * 
     * 摘要策略：
     * 
     *   ≤5 条：逐条拼接 "工具名: 结果(截断100字)"
     *   >5 条：模板化压缩 "最近执行了 N 个工具调用" + 最近5条(截断80字)
     * 
     * 
     * 案例 1：少量结果
     * 
     *   entries = [
     *     { toolName="ls", result="total 12\ndrwxr-xr-x" },
     *     { toolName="tail", result="error: connection refused" }
     *   ]
     *
     *   生成摘要：
     *   "ls: total 12\ndrwxr-xr-x\ntail: error: connection refused"
     * 
     * 
     * 案例 2：大量结果
     * 
     *   entries.size() = 10
     *
     *   生成摘要：
     *   "最近执行了 10 个工具调用:\n- ls: total 12\n- tail: error...\n..."
     *   （只展示最近5条）
     * 
     */
    private String generateSummary(List<ToolResultEntry> entries) {
        // 少量结果直接拼接，大量结果模板化压缩
        if (entries.size() <= 5) {
            return entries.stream()
                    .map(e -> e.getToolName() + ": " + truncate(e.getResult(), 100))
                    .collect(Collectors.joining("\n"));
        }
        StringBuilder sb = new StringBuilder();
        sb.append("最近执行了 ").append(entries.size()).append(" 个工具调用:\n");
        // 只取最近 5 条详细 + 总结
        List<ToolResultEntry> recent = entries.subList(entries.size() - 5, entries.size());
        for (ToolResultEntry e : recent) {
            sb.append("- ").append(e.getToolName()).append(": ")
                    .append(truncate(e.getResult(), 80)).append("\n");
        }
        return sb.toString();
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }

    @Data
    @AllArgsConstructor
    public static class ToolResultEntry {
        private String toolName;
        private String result;
    }

}