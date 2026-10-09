package com.xlc.ai.infrastructure.dao;

import com.xlc.ai.infrastructure.dao.po.ChatMessagePO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 对话消息 MyBatis DAO 接口
 * 
 * 对应 chat_message 表，提供消息插入和按会话查询最近消息的能力。
 */
@Mapper
public interface IChatMessageDao {

    /**
     * 插入一条对话消息
     *
     * @param po 消息持久化对象
     */
    void insert(ChatMessagePO po);

    /**
     * 按会话 ID 查询最近的消息（倒序，用于冷启动恢复和消息列表展示）
     *
     * @param sessionId 会话 ID
     * @param limit     最大返回条数
     * @return 消息列表（按 id DESC 排序）
     */
    List<ChatMessagePO> queryRecentBySessionId(@Param("sessionId") String sessionId, @Param("limit") int limit);
}
