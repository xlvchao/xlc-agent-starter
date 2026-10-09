package com.xlc.ai.infrastructure.adapter;

import com.xlc.ai.domain.agent.adapter.repository.IChatHistoryRepository;
import com.xlc.ai.domain.agent.model.entity.ChatMessageEntity;
import com.xlc.ai.domain.agent.model.entity.ChatSessionEntity;
import com.xlc.ai.domain.agent.model.valobj.prompt.MilestoneVO;
import com.xlc.ai.infrastructure.dao.IChatMessageDao;
import com.xlc.ai.infrastructure.dao.IChatMilestoneDao;
import com.xlc.ai.infrastructure.dao.IChatSessionDao;
import com.xlc.ai.infrastructure.dao.po.ChatMessagePO;
import com.xlc.ai.infrastructure.dao.po.ChatMilestonePO;
import com.xlc.ai.infrastructure.dao.po.ChatSessionPO;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 会话历史仓储实现（基础设施层）
 * 
 * 实现 {@link IChatHistoryRepository} 接口，通过 MyBatis DAO 操作数据库，
 * 负责 Entity ↔ PO 转换、消息计数更新、倒序查询转正序等基础设施细节。
 *
 * @see IChatHistoryRepository
 */
@Repository
public class ChatHistoryRepository implements IChatHistoryRepository {

    @Resource
    private IChatSessionDao chatSessionDao;

    @Resource
    private IChatMessageDao chatMessageDao;

    @Resource
    private IChatMilestoneDao chatMilestoneDao;

    /**
     * {@inheritDoc}
     * 
     * 将会话实体转为 PO 后插入 chat_session 表。
     */
    @Override
    public void saveSession(ChatSessionEntity session) {
        ChatSessionPO po = ChatSessionPO.builder()
                .id(session.getId())
                .agentId(session.getAgentId())
                .userId(session.getUserId())
                .title(session.getTitle())
                .messageCount(session.getMessageCount())
                .build();
        chatSessionDao.insert(po);
    }

    /**
     * {@inheritDoc}
     * 
     * 插入 chat_message 记录后，同步执行 {@code chatSessionDao.updateMessageCount}
     * 更新会话消息计数（SQL: message_count = message_count + 1，保证并发安全）。
     */
    @Override
    public void saveMessage(ChatMessageEntity message) {
        ChatMessagePO po = ChatMessagePO.builder()
                .sessionId(message.getSessionId())
                .role(message.getRole())
                .content(message.getContent())
                .toolName(message.getToolName())
                .toolCallId(message.getToolCallId())
                .priority(message.getPriority())
                .tokenCount(message.getTokenCount())
                .build();
        chatMessageDao.insert(po);
        chatSessionDao.updateMessageCount(message.getSessionId());
    }

    /**
     * {@inheritDoc}
     * 
     * SQL 层 ORDER BY id DESC LIMIT N 取最近 N 条（倒序），
     * Java 层 Collections.reverse() 转为正序返回。
     * 这样一次 DB 操作就拿到"最近 N 条 + 正序排列"。
     */
    @Override
    public List<ChatMessageEntity> getRecentMessages(String sessionId, int limit) {
        List<ChatMessagePO> pos = chatMessageDao.queryRecentBySessionId(sessionId, limit);
        if (pos == null || pos.isEmpty()) {
            return Collections.emptyList();
        }

        List<ChatMessagePO> reversedPos = new ArrayList<>(pos);
        Collections.reverse(reversedPos);
        return reversedPos.stream().map(this::toMessageEntity).collect(Collectors.toList());
    }

    /**
     * {@inheritDoc}
     * 
     * 先取最近 100 条消息，然后从最后一条（最近）往前累积 token，
     * 直到预算耗尽——保证最近的消息一定在上下文里，最早的可能被丢弃。
     */
    @Override
    public List<ChatMessageEntity> getMessagesWithBudget(String sessionId, int tokenBudget) {
        List<ChatMessageEntity> recent = getRecentMessages(sessionId, 100);
        if (recent.isEmpty() || tokenBudget <= 0) {
            return recent;
        }

        List<ChatMessageEntity> result = new ArrayList<>();
        int currentTokens = 0;
        // 从最近的消息开始往前累积
        for (int i = recent.size() - 1; i >= 0; i--) {
            ChatMessageEntity msg = recent.get(i);
            int tokens = msg.getTokenCount() != null ? msg.getTokenCount() : 0;
            if (currentTokens + tokens > tokenBudget && !result.isEmpty()) {
                break;
            }
            result.add(0, msg);
            currentTokens += tokens;
        }

        return result;
    }

    /**
     * {@inheritDoc}
     * 
     * 将里程碑 VO 转为 PO 后插入 chat_milestone 表。
     */
    @Override
    public void saveMilestone(String sessionId, MilestoneVO milestoneVO) {
        ChatMilestonePO po = ChatMilestonePO.builder()
                .sessionId(sessionId)
                .type(milestoneVO.getType().name())
                .content(milestoneVO.getContent())
                .build();
        chatMilestoneDao.insert(po);
    }

    /**
     * {@inheritDoc}
     * 
     * 从 chat_milestone 表查询并转回 MilestoneVO，timestamp 从 LocalDateTime 转为毫秒。
     */
    @Override
    public List<MilestoneVO> getRecentMilestones(String sessionId, int limit) {
        List<ChatMilestonePO> pos = chatMilestoneDao.queryRecentBySessionId(sessionId, limit);
        if (pos == null || pos.isEmpty()) {
            return Collections.emptyList();
        }
        return pos.stream().map(po -> MilestoneVO.builder()
                .type(MilestoneVO.Type.valueOf(po.getType()))
                .content(po.getContent())
                .timestamp(po.getCreatedAt() != null ? Timestamp.valueOf(po.getCreatedAt()).getTime() : System.currentTimeMillis())
                .build()).collect(Collectors.toList());
    }

    @Override
    public List<ChatSessionEntity> querySessionList(String agentId, String userId, int limit) {
        List<ChatSessionPO> pos = chatSessionDao.querySessionList(agentId, userId, limit);
        if (pos == null || pos.isEmpty()) {
            return Collections.emptyList();
        }
        return pos.stream().map(this::toSessionEntity).collect(Collectors.toList());
    }

    @Override
    public List<ChatMessageEntity> queryMessageList(String sessionId, int limit) {
        return getRecentMessages(sessionId, limit);
    }

    // ==================== PO ↔ Entity 转换 ====================

    private ChatSessionEntity toSessionEntity(ChatSessionPO po) {
        return ChatSessionEntity.builder()
                .id(po.getId())
                .agentId(po.getAgentId())
                .userId(po.getUserId())
                .title(po.getTitle())
                .messageCount(po.getMessageCount())
                .createdAt(po.getCreatedAt() != null ? Timestamp.valueOf(po.getCreatedAt()) : null)
                .updatedAt(po.getUpdatedAt() != null ? Timestamp.valueOf(po.getUpdatedAt()) : null)
                .build();
    }

    private ChatMessageEntity toMessageEntity(ChatMessagePO po) {
        return ChatMessageEntity.builder()
                .id(po.getId())
                .sessionId(po.getSessionId())
                .role(po.getRole())
                .content(po.getContent())
                .toolName(po.getToolName())
                .toolCallId(po.getToolCallId())
                .priority(po.getPriority())
                .tokenCount(po.getTokenCount())
                .createdAt(po.getCreatedAt() != null ? new Date(Timestamp.valueOf(po.getCreatedAt()).getTime()) : null)
                .build();
    }
}
