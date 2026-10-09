package com.xlc.ai.domain.agent.service;

import com.xlc.ai.domain.agent.model.entity.ChatCommandEntity;
import com.xlc.ai.domain.agent.model.entity.ChatMessageEntity;
import com.xlc.ai.domain.agent.model.entity.ChatSessionEntity;
import com.xlc.ai.domain.agent.model.valobj.AgentConfigure;
import com.google.adk.events.Event;
import io.reactivex.rxjava3.core.Flowable;

import java.util.List;

/**
 * 智能体聊天服务接口
 *
 * @author xlvchao
 */
public interface IChatService {

    /**
     * 查询智能体配置
     */
    AgentConfigure.Agent queryAiAgentConfigList();

    /**
     * 创建会话
     * @param agentId 智能体ID
     * @param userId 用户ID
     * @return sessionId 会话ID
     */
    String createSession(String agentId, String userId);


    /**
     * 查询用户会话列表
     * 
     * 为前端历史记录功能提供后端支撑，默认返回最多 20 条。
     *
     * @param agentId 智能体 ID
     * @param userId  用户 ID
     * @param limit   最大返回条数，≤0 时默认 20
     * @return 会话列表
     */
    List<ChatSessionEntity> querySessionList(String agentId, String userId, int limit);

    /**
     * 查询会话消息列表
     * 
     * 为前端历史消息展示提供后端支撑，默认返回最多 100 条。
     *
     * @param sessionId 会话 ID
     * @param limit     最大返回条数，≤0 时默认 100
     * @return 消息列表（时间正序）
     */
    List<ChatMessageEntity> queryMessageList(String sessionId, int limit);

    /**
     * 处理消息（流式）
     * @param chatCommandEntity 对话命令对象
     * @return 事件流
     */
    List<String> handleMessage(ChatCommandEntity chatCommandEntity);

    /**
     * 处理消息（指定会话ID）
     * @param agentId 智能体ID
     * @param userId 用户ID
     * @param sessionId 会话ID
     * @param message 消息内容
     */
    List<String> handleMessage(String agentId, String userId, String sessionId, String message);

    /**
     * 处理消息（流式）
     * @param agentId 智能体ID
     * @param userId 用户ID
     * @param sessionId 会话ID
     * @param message 消息内容
     * @return 事件流
     */
    Flowable<Event> handleMessageStream(String agentId, String userId, String sessionId, String message);
}
