package com.xlc.ai.infrastructure.dao;

import com.xlc.ai.infrastructure.dao.po.LongTermMemoryPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 长期记忆 MyBatis DAO 接口
 * 
 * 对应 long_term_memory 表，提供 Upsert 模式所需的按唯一键查询、插入、更新，
 * 以及按用户查询最近记忆（粗筛阶段）的能力。
 */
@Mapper
public interface ILongTermMemoryDao {

    /**
     * 按唯一键 (userId, memoryType, memoryKey) 查询是否已存在（Upsert 前置查询）
     *
     * @param userId     用户 ID
     * @param memoryType 记忆类型
     * @param memoryKey  记忆去重键
     * @return 已存在的记忆 PO，不存在时返回 null
     */
    LongTermMemoryPO queryByIdentity(@Param("userId") String userId,
                                     @Param("memoryType") String memoryType,
                                     @Param("memoryKey") String memoryKey);

    /**
     * 插入一条新的长期记忆
     *
     * @param po 长期记忆持久化对象
     */
    void insert(LongTermMemoryPO po);

    /**
     * 更新已有的长期记忆（content/keywords/confidence/hit_count 等字段）
     *
     * @param po 已存在并更新了字段的持久化对象
     */
    void update(LongTermMemoryPO po);

    /**
     * 按用户 ID 查询最近的长期记忆（粗筛阶段，精排在 Service 层用关键词匹配完成）
     *
     * @param userId 用户 ID
     * @param limit  最大返回条数（LongTermMemoryService 传入 120）
     * @return 长期记忆列表（按 updated_at DESC, id DESC 排序）
     */
    List<LongTermMemoryPO> queryRecentByUserId(@Param("userId") String userId,
                                               @Param("limit") int limit);
}
