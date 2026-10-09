package com.xlc.ai.domain.agent.service;

import com.xlc.ai.domain.agent.model.valobj.prompt.PromptContextVO;

import java.util.List;
import java.util.Map;

/**
 * 上下文管理领域服务接口，负责 ReAct 对话的上下文聚合与裁剪：
 * 
 *   聚合各 ContextProvider 输出，组装 PromptContextVO
 *   在 token 预算内裁剪消息历史
 *   接收工具执行结果，供后续生成摘要
 *   清理指定会话的上下文缓存
 * 
 * 在整体架构中的位置：
 * 
 *   Case 层  ReActAiCallNode / ReActToolCallNode
 *              |           ^
 *              v           | 富化消息 / 裁剪历史
 *   Domain 层  IChatContextService <-- PromptService（构建消息前缀）
 *              |
 *              +-- context/provider/impl/*Provider  （上下文采集：环境/任务/里程碑/工具摘要）
 *              +-- context/reducer/impl/*Reducer    （消息裁剪：优先级/滑动窗口/混合）
 * 
 *
 * @author xlvchao
 */
public interface IChatContextService {

    /**
     * 聚合上下文，构建 PromptContextVO
     *
     * @param sessionId         对话会话 ID
     * @param userId            用户 ID
     * @param messageHistory    消息历史
     * @return 组装完成的动态上下文值对象
     */
    PromptContextVO buildPromptContext(String sessionId, String userId, List<Map<String, Object>> messageHistory);

    /**
     * 在 token 预算内裁剪消息历史
     *
     * @param history     原始消息历史
     * @param tokenBudget token 预算（<=0 时使用默认值）
     * @return 裁剪后的消息历史
     */
    List<Map<String, Object>> trimHistory(List<Map<String, Object>> history, int tokenBudget);

    /**
     * 记录工具执行结果（供生成工具摘要）
     *
     * @param sessionId 对话会话 ID
     * @param toolName  工具名称
     * @param result    执行结果
     */
    void pushToolResult(String sessionId, String toolName, String result);

    /**
     * 清理指定会话的上下文缓存
     *
     * @param sessionId 会话 ID
     */
    void clearSessionContext(String sessionId);
}