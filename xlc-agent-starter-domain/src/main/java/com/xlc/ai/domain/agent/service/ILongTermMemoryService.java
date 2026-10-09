package com.xlc.ai.domain.agent.service;

import com.xlc.ai.domain.agent.model.entity.ChatMessageEntity;
import com.xlc.ai.domain.agent.model.entity.LongTermMemoryEntity;
import com.xlc.ai.domain.agent.service.memory.LongTermMemoryService;

import java.util.List;

/**
 * 长期记忆服务接口
 * 
 * 定义从对话流中自动提取结构化记忆、召回相关记忆、构建 Prompt 摘要的能力。
 * 由 {@code LongTermMemoryService} 实现，在 ReAct 循环各阶段被调用：
 * 
 *   {@link ReActAiCallNode} 首轮调用 {@link #recordUserMessage} 提取用户偏好
 *   {@link ReActToolCallNode} 调用 {@link #recordToolObservation} 提取环境/软件/失败信号
 *   {@link ReActAiCallNode} 调用 {@link #recordAssistantConclusion} 提取排查结论
 *   {@link LongTermMemoryProvider} 调用 {@link #buildMemorySummary} 构建注入 Prompt 的摘要
 *
 */
public interface ILongTermMemoryService {

    /**
     * 记录用户消息，检测并提取用户偏好记忆（USER_PREFERENCE）。
     * 
     * 当用户消息包含"以后""默认""记住"等偏好词时，提取为偏好记忆。
     * 意图标签会拼入关键词，提高后续召回命中率。
     *
     * @param userId      用户 ID
     * @param sessionId   会话 ID
     * @param message     用户原始消息
     * @param intentLabel 当前意图标签（可为 null）
     */
    void recordUserMessage(String userId, String sessionId, String message, String intentLabel);

    /**
     * 记录工具执行结果，自动提取环境事实、软件版本、失败信号等记忆。
     * 
     * 通过正则匹配从工具输出中提取：
     * 
     *   操作系统（ENVIRONMENT_FACT）
     *   失败信号（TROUBLESHOOTING_CASE，仅 success=false 时）
     * 
     *
     * @param userId        用户 ID
     * @param sessionId     会话 ID
     * @param toolName      工具名称
     * @param resultContent 工具执行结果内容
     * @param success       工具执行是否成功
     */
    void recordToolObservation(String userId, String sessionId, String toolName, String resultContent, boolean success);

    /**
     * 记录助手回复，当包含"结论""原因""建议"等关键词时提取排查案例记忆（TROUBLESHOOTING_CASE）。
     *
     * @param userId           用户 ID
     * @param sessionId        会话 ID
     * @param assistantContent 助手回复内容
     */
    void recordAssistantConclusion(String userId, String sessionId, String assistantContent);

    /**
     * 保存用户消息并提取用户偏好记忆。
     * 
     * 将用户消息落库（role=user，仅首轮），同时调用 {@link #recordUserMessage} 检测偏好信号。
     * Case 层通过此方法完成"消息落库 + 长期记忆提取"的闭环，不再直接调用仓储层。
     *
     * @param userId      用户 ID
     * @param sessionId   会话 ID
     * @param message     用户原始消息
     * @param intentLabel 当前意图标签（可为 null）
     * @param firstRound  是否首轮（首轮才落库 user 消息，避免多轮循环重复写入）
     */
    void saveUserMessage(String userId, String sessionId, String message, String intentLabel, boolean firstRound);

    /**
     * 保存助手回复并提取排查结论记忆。
     * 
     * 将助手回复落库（role=assistant），同时调用 {@link #recordAssistantConclusion} 检测结论信号。
     *
     * @param userId           用户 ID
     * @param sessionId        会话 ID
     * @param assistantContent 助手回复内容
     */
    void saveAssistantMessage(String userId, String sessionId, String assistantContent);

    /**
     * 保存工具执行结果并提取环境/软件/失败信号记忆。
     * 
     * 将工具结果落库（role=tool，priority=HIGH），同时调用 {@link #recordToolObservation} 提取记忆。
     *
     * @param userId        用户 ID
     * @param sessionId     会话 ID
     * @param toolName      工具名称
     * @param toolCallId    工具调用 ID
     * @param resultContent 工具执行结果内容
     * @param success       工具执行是否成功
     */
    void saveToolMessage(String userId, String sessionId, String toolName, String toolCallId, String resultContent, boolean success);

    /**
     * 获取指定会话最近 N 条消息（冷启动恢复上下文用）。
     * 
     * Case 层（ReActRootNode）冷启动时通过此方法加载历史消息，不再直接调用仓储层。
     *
     * @param sessionId 会话 ID
     * @param limit     最大返回条数
     * @return 消息列表（时间正序），无数据时返回空列表
     */
    List<ChatMessageEntity> getRecentMessages(String sessionId, int limit);

    /**
     * 查询与当前对话相关的长期记忆（粗筛+精排两阶段召回）。
     * 
     * 阶段一：从 DB 拉最近 120 条记忆（粗筛）；
     * 阶段二：用查询关键词与记忆关键词做重叠打分（精排），返回 Top-N。
     *
     * @param userId 用户 ID
     * @param query  召回查询（首条+最近用户消息拼接）
     * @param limit  最大返回条数
     * @return 相关记忆列表（按打分降序）
     */
    List<LongTermMemoryEntity> queryRelevantMemories(String userId, String query, int limit);

    /**
     * 构建长期记忆摘要字符串，用于注入 Prompt 的 [长期记忆] 段落。
     * 
     * 输出格式示例：
     * 
     * - [USER_PREFERENCE] 以后执行命令都加 sudo
     * - [TROUBLESHOOTING_CASE] 执行命令失败，错误码 137，原因是权限不足
     * - [ENVIRONMENT_FACT] 操作系统是 Ubuntu 20.04
     *
     * @param userId 用户 ID
     * @param query  召回查询
     * @param limit  最大返回条数
     * @return 记忆摘要字符串，无记忆时返回空字符串
     */
    String buildMemorySummary(String userId, String query, int limit);
}
