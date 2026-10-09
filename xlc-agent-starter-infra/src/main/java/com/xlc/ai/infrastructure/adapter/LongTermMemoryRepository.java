package com.xlc.ai.infrastructure.adapter;

import com.xlc.ai.domain.agent.adapter.repository.ILongTermMemoryRepository;
import com.xlc.ai.domain.agent.model.entity.LongTermMemoryEntity;
import com.xlc.ai.infrastructure.dao.ILongTermMemoryDao;
import com.xlc.ai.infrastructure.dao.po.LongTermMemoryPO;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 长期记忆仓储实现（基础设施层）
 * 
 * 实现 {@link ILongTermMemoryRepository} 接口，通过 MyBatis DAO 操作数据库。
 * 
 * 核心逻辑：
 * 
 *   {@link #saveOrUpdate} - "先查后插/更新"的 Upsert 模式，通过唯一键
 *       (userId, memoryType, memoryKey) 判断是否已存在，不存在则 INSERT，
 *       已存在则 UPDATE 并 hit_count+1
 *   {@link #queryRecentByUserId} - 按 updated_at DESC 排序取最近 N 条（粗筛阶段）
 * 
 * 
 * 为什么不用 INSERT ... ON DUPLICATE KEY UPDATE？
 * 因为 hit_count 需要在原有值上 +1，且需更新多个字段，"先查后改"更直观可控，
 * 也方便日志区分"新建记忆"和"更新记忆"。长期记忆写入频率不高，性能不是瓶颈。
 */
@Repository
public class LongTermMemoryRepository implements ILongTermMemoryRepository {

    @Resource
    private ILongTermMemoryDao longTermMemoryDao;

    /**
     * {@inheritDoc}
     * 
     * Upsert 流程：先通过唯一键查询 → 不存在则 INSERT → 已存在则 UPDATE + hit_count+1。
     */
    @Override
    public void saveOrUpdate(LongTermMemoryEntity memoryEntity) {
        LongTermMemoryPO exist = longTermMemoryDao.queryByIdentity(
                memoryEntity.getUserId(),
                memoryEntity.getMemoryType(),
                memoryEntity.getMemoryKey()
        );

        if (exist == null) {
            // 不存在 → 新建记忆
            longTermMemoryDao.insert(toPO(memoryEntity));
            return;
        }

        // 已存在 → 更新内容并 hit_count + 1
        exist.setSessionId(memoryEntity.getSessionId());
        exist.setContent(memoryEntity.getContent());
        exist.setKeywords(memoryEntity.getKeywords());
        exist.setSourceRole(memoryEntity.getSourceRole());
        exist.setConfidence(memoryEntity.getConfidence());
        exist.setHitCount((exist.getHitCount() == null ? 0 : exist.getHitCount()) + 1);
        longTermMemoryDao.update(exist);
    }

    /**
     * {@inheritDoc}
     * 
     * SQL: ORDER BY updated_at DESC, id DESC LIMIT N，取最近 N 条记忆供精排使用。
     */
    @Override
    public List<LongTermMemoryEntity> queryRecentByUserId(String userId, int limit) {
        List<LongTermMemoryPO> list = longTermMemoryDao.queryRecentByUserId(userId, limit);
        if (list == null || list.isEmpty()) {
            return Collections.emptyList();
        }
        return list.stream().map(this::toEntity).collect(Collectors.toList());
    }

    // ==================== PO ↔ Entity 转换 ====================

    private LongTermMemoryPO toPO(LongTermMemoryEntity entity) {
        return LongTermMemoryPO.builder()
                .id(entity.getId())
                .userId(entity.getUserId())
                .sessionId(entity.getSessionId())
                .memoryType(entity.getMemoryType())
                .memoryKey(entity.getMemoryKey())
                .content(entity.getContent())
                .keywords(entity.getKeywords())
                .sourceRole(entity.getSourceRole())
                .confidence(entity.getConfidence())
                .hitCount(entity.getHitCount())
                .build();
    }

    private LongTermMemoryEntity toEntity(LongTermMemoryPO po) {
        return LongTermMemoryEntity.builder()
                .id(po.getId())
                .userId(po.getUserId())
                .sessionId(po.getSessionId())
                .memoryType(po.getMemoryType())
                .memoryKey(po.getMemoryKey())
                .content(po.getContent())
                .keywords(po.getKeywords())
                .sourceRole(po.getSourceRole())
                .confidence(po.getConfidence())
                .hitCount(po.getHitCount())
                .createdAt(po.getCreatedAt() != null ? new Date(Timestamp.valueOf(po.getCreatedAt()).getTime()) : null)
                .updatedAt(po.getUpdatedAt() != null ? new Date(Timestamp.valueOf(po.getUpdatedAt()).getTime()) : null)
                .build();
    }
}
