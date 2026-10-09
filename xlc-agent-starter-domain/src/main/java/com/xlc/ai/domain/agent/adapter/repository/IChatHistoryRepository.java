package com.xlc.ai.domain.agent.adapter.repository;

import com.xlc.ai.domain.agent.model.entity.ChatMessageEntity;
import com.xlc.ai.domain.agent.model.entity.ChatSessionEntity;
import com.xlc.ai.domain.agent.model.valobj.prompt.MilestoneVO;

import java.util.List;

/**
 * 会话历史持久化仓储接口（领域层抽象）
 * 
 * 定义对话会话、消息、里程碑的持久化能力，由基础设施层 {@code ChatHistoryRepository} 实现。
 * 领域层通过此接口面向抽象编程，不依赖 MyBatis、PO 等基础设施细节。
 * 
 * 核心场景：
 *   {@link ReActRootNade} 冷启动时通过 {@link #getRecentMessages} 加载历史消息恢复上下文
 *   {@link ReActAiCallNode} / {@link ReActToolCallNode} 每轮将 user/assistant/tool 消息落库
 *   {@link MilestoneTracker} 检测到关键事件后通过 {@link #saveMilestone} 持久化
 *   前端历史记录功能通过 {@link #querySessionList} / {@link #queryMessageList} 获取数据
 */
public interface IChatHistoryRepository {

    /**
     * 保存会话元数据（在 {@code ChatService.createSession} 创建 ADK Session 时同步落库）
     *
     * @param session 会话实体，包含 agentId、userId、title 等
     */
    void saveSession(ChatSessionEntity session);

    /**
     * 保存对话消息，同时更新会话表的 message_count 计数（+1）
     *
     * @param message 消息实体，包含 role、content、priority、tokenCount 等
     */
    void saveMessage(ChatMessageEntity message);

    /**
     * 获取指定会话最近 N 条消息（按时间倒序查询，返回时转为正序）。
     * 
     * SQL 层 {@code ORDER BY id DESC LIMIT N} 取最近 N 条，Java 层 {@code Collections.reverse()} 转正序，
     * 一次 DB 操作同时拿到"最近 N 条 + 正序排列"。
     *
     * @param sessionId 会话 ID
     * @param limit     最大返回条数
     * @return 消息列表（时间正序），无数据时返回空列表
     */
    List<ChatMessageEntity> getRecentMessages(String sessionId, int limit);

    /**
     * 获取指定 token 预算内的消息（从最近消息往前累积，直到预算耗尽）。
     * 
     * 与 2-6 节 SlidingWindowReducer 思路一致：最近的消息一定保留，最早的消息可能被丢弃。
     *
     * @param sessionId    会话 ID
     * @param tokenBudget  token 预算上限
     * @return 预算内的消息列表（时间正序）
     */
    List<ChatMessageEntity> getMessagesWithBudget(String sessionId, int tokenBudget);

    /**
     * 保存里程碑事件（任务切换、错误、用户纠偏等关键事件）
     *
     * @param sessionId   会话 ID
     * @param milestoneVO 里程碑值对象
     */
    void saveMilestone(String sessionId, MilestoneVO milestoneVO);

    /**
     * 获取指定会话最近的里程碑事件
     *
     * @param sessionId 会话 ID
     * @param limit     最大返回条数
     * @return 里程碑列表（时间倒序），无数据时返回空列表
     */
    List<MilestoneVO> getRecentMilestones(String sessionId, int limit);

    /**
     * 查询用户在某 Agent 下的会话列表（用于前端历史会话列表展示）
     *
     * @param agentId 智能体 ID
     * @param userId  用户 ID
     * @param limit   最大返回条数
     * @return 会话列表
     */
    List<ChatSessionEntity> querySessionList(String agentId, String userId, int limit);

    /**
     * 查询指定会话的消息列表（用于前端历史消息展示）
     *
     * @param sessionId 会话 ID
     * @param limit     最大返回条数
     * @return 消息列表（时间正序）
     */
    List<ChatMessageEntity> queryMessageList(String sessionId, int limit);
}
