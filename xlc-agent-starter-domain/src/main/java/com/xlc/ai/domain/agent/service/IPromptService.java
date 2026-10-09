package com.xlc.ai.domain.agent.service;

import java.util.List;
import java.util.Map;

/**
 * 提示词服务接口
 * 
 * 面向 Case 层提供动态 Prompt 能力：
 * 1. 根据会话上下文（环境/任务/工具摘要/里程碑）构建富化消息
 * 2. 记录里程碑事件
 * 3. 清理会话级缓存
 *
 * @author xlvchao
 */
public interface IPromptService {

    /**
     * 构建带动态上下文前缀的富化消息。
     *
     * @param userMessage       原始用户消息
     * @param sessionId         会话 ID
     * @param userId            用户 ID
     * @param recentCommands    最近执行命令
     * @param messageHistory    对话历史
     * @return 富化后的消息
     */
    String buildEnrichedMessage(String userMessage, String sessionId, String userId, List<String> recentCommands, List<Map<String, Object>> messageHistory);

    /**
     * 构建带动态上下文前缀的富化消息（含意图标签）。
     *
     * @param userMessage       原始用户消息
     * @param sessionId         会话 ID
     * @param userId            用户 ID
     * @param recentCommands    最近执行命令
     * @param messageHistory    对话历史
     * @param intentLabel       意图标签（由意图识别系统提供，可为 null）
     * @return 富化后的消息
     */
    String buildEnrichedMessage(String userMessage, String sessionId, String userId, List<String> recentCommands, List<Map<String, Object>> messageHistory, String intentLabel);

    /**
     * 检测并记录里程碑事件。
     *
     * @param sessionId 会话 ID
     * @param role      角色
     * @param content   内容
     */
    void detectAndRecordMilestone(String sessionId, String role, String content);

    /**
     * 清理指定会话的里程碑缓存。
     *
     * @param sessionId 会话 ID
     */
    void clearMilestones(String sessionId);

}
