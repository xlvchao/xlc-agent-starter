package com.xlc.ai.infrastructure.dao;

import com.xlc.ai.infrastructure.dao.po.ChatMilestonePO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 对话里程碑 MyBatis DAO 接口
 * 
 * 对应 chat_milestone 表，提供里程碑事件插入和按会话查询最近里程碑的能力。
 */
@Mapper
public interface IChatMilestoneDao {

    /**
     * 插入一条里程碑事件记录
     *
     * @param po 里程碑持久化对象
     */
    void insert(ChatMilestonePO po);

    /**
     * 按会话 ID 查询最近的里程碑事件（倒序）
     *
     * @param sessionId 会话 ID
     * @param limit     最大返回条数
     * @return 里程碑列表（按 id DESC 排序）
     */
    List<ChatMilestonePO> queryRecentBySessionId(@Param("sessionId") String sessionId, @Param("limit") int limit);
}
