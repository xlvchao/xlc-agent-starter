package com.xlc.ai.domain.agent.service.intent;

import com.xlc.ai.domain.agent.model.valobj.intent.ConversationContextVO;
import com.xlc.ai.domain.agent.model.valobj.intent.IntentHistoryEntryVO;
import com.xlc.ai.domain.agent.model.valobj.intent.IntentResultVO;
import com.xlc.ai.domain.agent.model.valobj.intent.TaskStateVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Iterator;
import java.util.LinkedList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 上下文追踪器
 * 
 * 按 sessionId 维护会话级意图历史（滑动窗口 10 条）与任务态，
 * 供规则分类器和 LLM 分类器做上下文加权，并支撑 CONTINUE / 自纠错。
 * 
 * 采用惰性过期清理 + 会话数上限保护，避免长期运行内存无限增长。
 *
 * 数据流：
 * 
 *   IntentService.classify / reportFeedback
 *      │  getContext(sessionId)            ← 取（或惰性创建）上下文
 *      │  updateContext / recordFeedback   ← 回写意图历史、失败计数、任务态
 *      ▼
 *   ConversationContextVO（内存，ConcurrentHashMap）
 *      ├─ recentIntents      ← 滑动窗口 10 条，满则淘汰最旧
 *      ├─ consecutiveFailures← 反馈失败累计，≥2 触发强制兜底
 *      └─ taskState           ← CONTINUE / 多步任务续接依据
 *      │
 *      └─ evictExpired()      ← 每次 getContext 触发：30min 无活跃淘汰，超 500 会话淘汰最旧
 * 
 *
 * 案例：会话 S1 连续 35 分钟无活动 → 下次任意会话 getContext 时被惰性清除，
 * 避免后台线程开销。
 *
 * @author xlvchao
 */
@Slf4j
@Component
public class ContextTracker {

    private static final int WINDOW_SIZE = 10;
    /** 会话上下文最大存活数量上限 */
    private static final int MAX_SESSIONS = 500;
    /** 会话过期时间：30 分钟无活跃则淘汰 */
    private static final long SESSION_TTL_MS = 30 * 60 * 1000L;

    private final Map<String, ConversationContextVO> contexts = new ConcurrentHashMap<>();

    /**
     * 获取（或创建）会话上下文。
     * 
     * 若会话不存在则自动创建初始上下文，并触发惰性过期清理。
     * 
     * 案例：
     * 
     *   sessionId = "session-001"
     *
     *   第1次调用：
     *   - 会话不存在，创建新上下文
     *   - 返回初始 ConversationContextVO：
     *     { recentIntents=[], turnCount=0, sessionStartTime=xxx, lastActiveTime=xxx, consecutiveFailures=0 }
     *
     *   第2次调用（5秒后）：
     *   - 会话已存在，直接返回
     *   - 触发 evictExpired() 清理过期会话
     * 
     */
    public ConversationContextVO getContext(String sessionId) {
        evictExpired();
        return contexts.computeIfAbsent(sessionId, id -> {
            long now = System.currentTimeMillis();
            return ConversationContextVO.builder()
                    .recentIntents(new LinkedList<>())
                    .turnCount(0)
                    .sessionStartTime(now)
                    .lastActiveTime(now)
                    .consecutiveFailures(0)
                    .build();
        });
    }

    /**
     * 更新会话上下文（记录意图历史、递增轮次、刷新活跃时间）
     * 
     * 每次分类后调用，维护滑动窗口 10 条的意图历史。
     * 
     * 案例：
     * 
     *   初始上下文：recentIntents=[], turnCount=0
     *
     *   调用 updateContext("session-001", { intent=DIAGNOSE, conf=0.8 })
     *   -> recentIntents = [{ intent=DIAGNOSE, conf=0.8 }]
     *   -> turnCount = 1
     *
     *   调用 updateContext("session-001", { intent=MONITOR, conf=0.9 })
     *   -> recentIntents = [{ intent=DIAGNOSE }, { intent=MONITOR }]
     *   -> turnCount = 2
     *
     *   第11次调用（窗口满）：
     *   -> recentIntents = [{ intent=MONITOR }, ..., { intent=最新意图 }]
     *   -> 最旧的 intent=DIAGNOSE 被淘汰
     *   -> turnCount = 11
     * 
     */
    public void updateContext(String sessionId, IntentResultVO result) {
        ConversationContextVO ctx = getContext(sessionId);
        ctx.getRecentIntents().addLast(IntentHistoryEntryVO.builder()
                .intent(result.getIntent())
                .confidence(result.getConfidence())
                .timestamp(System.currentTimeMillis())
                .build());
        if (ctx.getRecentIntents().size() > WINDOW_SIZE) {
            ctx.getRecentIntents().removeFirst();
        }
        ctx.setTurnCount(ctx.getTurnCount() + 1);
        ctx.setLastIntent(result.getIntent());
        ctx.setLastIntentResult(result);
        ctx.setLastActiveTime(System.currentTimeMillis());
    }

    /**
     * 记录一次反馈结果（成功/失败），维护连续失败计数。
     * 
     * 成功时重置计数器，失败时递增。
     * 
     * 案例：
     * 
     *   初始 consecutiveFailures = 0
     *
     *   第1次调用 recordFeedback("session-001", false)
     *   -> consecutiveFailures = 1
     *   -> 若 taskState 存在，标记 lastFeedbackFailed = true
     *
     *   第2次调用 recordFeedback("session-001", false)
     *   -> consecutiveFailures = 2
     *   -> IntentService 连续失败阈值触发：后续分类跳规则层，LLM 兜底
     *
     *   第3次调用 recordFeedback("session-001", true)
     *   -> consecutiveFailures = 0（重置）
     *   -> 恢复正常分类流程
     * 
     */
    public void recordFeedback(String sessionId, boolean success) {
        ConversationContextVO ctx = getContext(sessionId);
        if (success) {
            ctx.setConsecutiveFailures(0);
        } else {
            ctx.setConsecutiveFailures(ctx.getConsecutiveFailures() + 1);
        }
        if (ctx.getTaskState() != null) {
            ctx.getTaskState().setLastFeedbackFailed(!success);
        }
        ctx.setLastActiveTime(System.currentTimeMillis());
    }

    /**
     * 获取会话的连续失败次数。
     * 
     * 供 {@link IntentService} 在 classify
     * 前查询，连续失败 ≥2 时触发 LLM 兜底（跳过规则层、放宽置信度门槛至 0.3）。
     *
     * @param sessionId 会话 ID
     * @return 连续失败次数，未初始化时返回 0
     */
    public int getConsecutiveFailures(String sessionId) {
        return getContext(sessionId).getConsecutiveFailures();
    }

    /**
     * 获取会话当前任务状态。
     * 
     * 任务态包含当前进行中的意图、步骤索引及完成标志，供分类器判断是否应返回
     * {@link IntentRegistry#getContinue()}。
     *
     * @param sessionId 会话 ID
     * @return 任务状态，若会话无任务态则返回 null
     */
    public TaskStateVO getTaskState(String sessionId) {
        return getContext(sessionId).getTaskState();
    }

    /**
     * 获取最近一次完整意图识别结果；无历史时返回 null。
     */
    public IntentResultVO getLastIntentResult(String sessionId) {
        return getContext(sessionId).getLastIntentResult();
    }

    /**
     * 设置或更新会话的任务状态。
     * 
     * 通常由主流程（如多步诊断任务启动时）调用，用于记录当前进行中的意图、
     * 步骤索引等信息，供后续 classify 和 reportFeedback 使用。
     * 调用后会同步刷新会话的 lastActiveTime，避免被惰性清理。
     *
     * @param sessionId    会话 ID
     * @param taskState    任务状态对象，可为 null（清除任务态）
     */
    public void setTaskState(String sessionId, TaskStateVO taskState) {
        ConversationContextVO ctx = getContext(sessionId);
        ctx.setTaskState(taskState);
        ctx.setLastActiveTime(System.currentTimeMillis());
    }

    /**
     * 清除指定会话的上下文。
     * 
     * 从内存 Map 中移除该会话的所有意图历史、任务态及连续失败计数。
     * 通常在会话结束或用户显式要求重置时使用。
     *
     * @param sessionId 要清除的会话 ID
     */
    public void clear(String sessionId) {
        contexts.remove(sessionId);
    }

    /**
     * 惰性清理过期会话，并在超出上限时淘汰最久未活跃的会话。
     * 在 getContext 入口触发，无需后台线程。
     * 
     * 两步策略：先按 TTL(30min) 移除过期项，再在总量超 MAX_SESSIONS(500) 时
     * 按 lastActiveTime 升序淘汰最旧的若干项。
     * 
     * 案例：
     * 
     *   当前 510 个会话，最后活跃时间：
     *   session-A: lastActiveTime = 10分钟前
     *   session-B: lastActiveTime = 35分钟前（过期）
     *   session-C: lastActiveTime = 1小时前（过期）
     *   session-D: lastActiveTime = 5分钟前
     *
     *   执行步骤：
     *   1. 按 TTL 移除过期项 → 移除 B、C，剩余 508 个
     *   2. 总量仍 > 500 → 按 lastActiveTime 升序淘汰最旧 8 个
     *   3. 最终保留 500 个最活跃的会话
     * 
    */
    private void evictExpired() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<String, ConversationContextVO>> it = contexts.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, ConversationContextVO> e = it.next();
            if (now - e.getValue().getLastActiveTime() > SESSION_TTL_MS) {
                it.remove();
            }
        }

        // 超出上限时按 lastActiveTime 升序淘汰（最旧的先移除）
        if (contexts.size() > MAX_SESSIONS) {
            contexts.entrySet().stream()
                    .sorted(Map.Entry.comparingByValue((a, b) ->
                            Long.compare(a.getLastActiveTime(), b.getLastActiveTime())))
                    .limit(contexts.size() - MAX_SESSIONS)
                    .forEach(e -> contexts.remove(e.getKey()));
        }
    }
}
