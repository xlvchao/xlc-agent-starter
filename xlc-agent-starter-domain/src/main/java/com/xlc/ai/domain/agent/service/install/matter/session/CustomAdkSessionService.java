package com.xlc.ai.domain.agent.service.install.matter.session;

import com.xlc.ai.domain.agent.service.install.matter.session.model.SessionSnapshot;
import com.xlc.ai.domain.agent.service.prompt.PromptEnvelope;
import com.google.adk.events.Event;
import com.google.adk.sessions.BaseSessionService;
import com.google.adk.sessions.GetSessionConfig;
import com.google.adk.sessions.ListEventsResponse;
import com.google.adk.sessions.ListSessionsResponse;
import com.google.adk.sessions.Session;
import com.google.adk.sessions.SessionKey;
import com.google.adk.sessions.State;
import com.google.common.collect.ImmutableList;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

@Component
/**
 * 自定义 ADK Session 服务。
 *
 * 这个类并不是去“1:1 复刻” ADK 原生/默认 Session 的完整存储语义，
 * 而是围绕 ReAct 流程，做了一层面向运行期的轻量化会话治理。
 *
 * 为什么要自己接管 Session？
 * 因为当前工程在业务层已经做了动态 Prompt 上下文增强，例如：
 *
 *   [最近执行的命令]
 *   [关键事件]
 *   [里程碑]
 *   ---
 *   用户原始问题
 * 
 *
 * 这类“富化消息”适合发给模型做推理，但并不适合再被 ADK 原样存回 Session。
 * 如果直接回灌，会带来几个典型问题：
 * 
 *   1. 用户原始问题被动态后缀污染
 *   2. 相同上下文在业务层和框架层重复保存
 *   3. tool / assistant 长文本让 Session 持续膨胀
 *   4. 对话轮次越来越长，后续取历史成本越来越高
 * 
 *
 * 所以，这个类相对“原生 ADK Session 使用方式”，做了几项有意识的简化：
 * 
 *   1. 只保留运行期轻量 Session，不做持久化落库
 *   2. 不追求保存完整原文历史，而是保存“够用”的净化后历史
 *   3. 不把动态 Prompt 后缀原样写回 Session，避免上下文重复
 *   4. 不保留无限长的 tool/assistant 文本，而是按类型截断
 *   5. 不保留无限轮对话，而是只保留最近 MAX_TURNS 轮、最多 MAX_EVENTS 条
 *   6. 只同步 event 中真正有价值的 stateDelta，维持最新运行态 state
 * 
 *
 * 你可以把它理解成：放在 ADK Session 前面的一道“净化器 + 限流器 + 修剪器”。
 * 目标不是把所有历史都留下，而是让框架层会话干净、轻量、可控，
 * 同时避免与业务侧 {@code ChatContextService} 管理的上下文发生重复和打架。
 *
 * @author xlvchao
 */
public class CustomAdkSessionService implements BaseSessionService {

    /** Session 最多保留的 event 条数，防止框架层历史无限膨胀。 */
    private static final int MAX_EVENTS = 20;
    /** assistant/model 文本的最大保留长度。 */
    private static final int MAX_ASSISTANT_TEXT = 2000;
    /** tool 结果文本的最大保留长度。 */
    private static final int MAX_TOOL_TEXT = 1000;
    /** 最多保留的用户轮次数；轮次是比“消息条数”更自然的对话单位。 */
    private static final int MAX_TURNS = 4;

    /** appName -> userId -> sessionId -> SessionSnapshot */
    private final ConcurrentMap<String, ConcurrentMap<String, ConcurrentMap<String, SessionSnapshot>>> sessions = new ConcurrentHashMap<>();
    /** 预留的 user 级别状态存储。 */
    private final ConcurrentMap<String, ConcurrentMap<String, ConcurrentMap<String, Object>>> userState = new ConcurrentHashMap<>();
    /** 预留的 app 级别状态存储。 */
    private final ConcurrentMap<String, ConcurrentMap<String, Object>> appState = new ConcurrentHashMap<>();

    /**
     * 创建一个新的轻量 Session 快照。
     *
     * 这里的设计很克制：
     * 只初始化当前会话运行必需的 state、event 容器和更新时间，
     * 不做更重的持久化、归档、索引等动作。
     *
     * 案例：
     * 
     *   appName   = app-001
     *   userId    = user-001
     *   sessionId = react-session-01
     *
     *   创建后得到：
     *   sessions["app-001"]["user-001"]["react-session-01"] = SessionSnapshot
     * 
     *
     * @param appName 应用名
     * @param userId 用户 ID
     * @param initialState 初始状态
     * @param sessionId 会话 ID，若为空则自动生成
     * @return 对外暴露的 ADK Session
     */
    @Override
    public Single<Session> createSession(String appName, String userId, ConcurrentMap<String, Object> initialState, String sessionId) {
        String finalSessionId = (sessionId == null || sessionId.isBlank()) ? UUID.randomUUID().toString() : sessionId;

        State state = new State(initialState == null ? new ConcurrentHashMap<>() : initialState);
        SessionSnapshot snapshot = SessionSnapshot.builder()
                .sessionKey(new SessionKey(appName, userId, finalSessionId))
                .state(state)
                .rawEvents(new CopyOnWriteArrayList<>())
                .lastUpdateTime(Instant.now())
                .build();

        sessions.computeIfAbsent(appName, k -> new ConcurrentHashMap<>())
                .computeIfAbsent(userId, k -> new ConcurrentHashMap<>())
                .put(finalSessionId, snapshot);

        userState.computeIfAbsent(appName, k -> new ConcurrentHashMap<>())
                .computeIfAbsent(userId, k -> new ConcurrentHashMap<>());
        appState.computeIfAbsent(appName, k -> new ConcurrentHashMap<>());

        return Single.just(toSession(snapshot, Optional.empty()));
    }

    /**
     * 查询指定会话，并按配置返回事件子集。
     *
     * 这里支持两种最常见的轻量过滤：
     * 
     *   afterTimestamp  只取某个时间点之后的事件
     *   numRecentEvents 只取最近 N 条事件
     * 
     *
     * 案例：
     * 
     *   一个 Session 当前有 12 条事件
     *   如果 numRecentEvents = 5
     *   最终只返回后 5 条，而不是把 12 条全量带回去
     * 
     *
     * @param appName 应用名
     * @param userId 用户 ID
     * @param sessionId 会话 ID
     * @param configOpt 查询配置
     * @return Session，若不存在则返回空
     */
    @Override
    public Maybe<Session> getSession(String appName, String userId, String sessionId, Optional<GetSessionConfig> configOpt) {
        SessionSnapshot snapshot = findSnapshot(appName, userId, sessionId);
        if (snapshot == null) {
            return Maybe.empty();
        }

        List<Event> events = new ArrayList<>(snapshot.getRawEvents());
        if (configOpt.isPresent()) {
            GetSessionConfig config = configOpt.get();
            if (config.afterTimestamp().isPresent()) {
                Instant ts = config.afterTimestamp().get();
                events = events.stream()
                        .filter(event -> Instant.ofEpochMilli(event.timestamp()).isAfter(ts))
                        .collect(Collectors.toList());
            }
            if (config.numRecentEvents().isPresent()) {
                int n = config.numRecentEvents().get();
                if (n >= 0 && events.size() > n) {
                    events = new ArrayList<>(events.subList(events.size() - n, events.size()));
                }
            }
        }

        return Maybe.just(toSession(snapshot, Optional.of(events)));
    }

    /**
     * 列出某个用户在当前应用下的全部 Session。
     *
     * 当前实现只做内存级遍历与组装，
     * 不做分页、排序、远程存储读取等更重的能力。
     *
     * @param appName 应用名
     * @param userId 用户 ID
     * @return Session 列表
     */
    @Override
    public Single<ListSessionsResponse> listSessions(String appName, String userId) {
        List<Session> sessionList = new ArrayList<>();
        ConcurrentMap<String, ConcurrentMap<String, SessionSnapshot>> appSessions = sessions.get(appName);
        if (appSessions != null) {
            ConcurrentMap<String, SessionSnapshot> userSessions = appSessions.get(userId);
            if (userSessions != null) {
                userSessions.values().forEach(snapshot -> sessionList.add(toSession(snapshot, Optional.empty())));
            }
        }

        return Single.just(ListSessionsResponse.builder()
                .sessions(ImmutableList.copyOf(sessionList))
                .build());
    }

    /**
     * 删除指定 Session。
     *
     * 当前实现只从内存索引中移除快照，不做更复杂的级联清理，
     * 因为当前阶段 Session 的定位就是“运行期轻量会话”。
     *
     * @param appName 应用名
     * @param userId 用户 ID
     * @param sessionId 会话 ID
     * @return Completable
     */
    @Override
    public Completable deleteSession(String appName, String userId, String sessionId) {
        return Completable.fromAction(() -> {
            ConcurrentMap<String, ConcurrentMap<String, SessionSnapshot>> appSessions = sessions.get(appName);
            if (appSessions != null) {
                ConcurrentMap<String, SessionSnapshot> userSessions = appSessions.get(userId);
                if (userSessions != null) {
                    userSessions.remove(sessionId);
                }
            }
        });
    }

    /**
     * 列出指定 Session 的事件列表。
     *
     * 注意这里返回的是已经过净化、截断、裁剪后的 rawEvents，
     * 而不是最初送进 ADK 的“完整原始富化消息”。
     *
     * @param appName 应用名
     * @param userId 用户 ID
     * @param sessionId 会话 ID
     * @return 事件响应
     */
    @Override
    public Single<ListEventsResponse> listEvents(String appName, String userId, String sessionId) {
        SessionSnapshot snapshot = findSnapshot(appName, userId, sessionId);
        List<Event> events = snapshot == null ? List.of() : new ArrayList<>(snapshot.getRawEvents());
        return Single.just(ListEventsResponse.builder()
                .events(ImmutableList.copyOf(events))
                .build());
    }

    /**
     * 追加一条事件，并在写入前完成本类最核心的会话治理逻辑。
     *
     * 执行顺序：
     * 
     *   1. normalizeEvent   补齐时间戳与 id
     *   2. resolveRole      判断角色
     *   3. 按角色治理：
     *      - user      -> sanitizeUserEvent    剥离动态后缀
     *      - tool      -> truncateEventText    截断工具长文本
     *      - assistant -> truncateEventText    截断助手长文本
     *   4. rawEvents.add     写入事件
     *   5. mergeStateDelta   合并 ADK 状态变化
     *   6. trimEvents        按轮次 + 按总条数裁剪
     *   7. updateTime        更新时间
     * 
     *
     * 案例：
     * 
     *   输入 user event：
     *   [里程碑]
     *   系统: Linux
     *   ---
     *   帮我看 nginx 日志
     *
     *   写入后实际保留：
     *   帮我看 nginx 日志
     * 
     *
     * @param session 当前 Session
     * @param event 待追加事件
     * @return 最终写入的规范化事件
     */
    @Override
    public Single<Event> appendEvent(Session session, Event event) {
        SessionKey key = session.sessionKey();
        SessionSnapshot snapshot = findSnapshot(key.appName(), key.userId(), key.id());
        if (snapshot == null) {
            return Single.error(new IllegalStateException("session not found: " + key));
        }

        Event normalized = normalizeEvent(event);
        String role = resolveRole(normalized);
        if ("user".equalsIgnoreCase(role)) {
            normalized = sanitizeUserEvent(normalized);
        } else if ("tool".equalsIgnoreCase(role)) {
            normalized = truncateEventText(normalized, MAX_TOOL_TEXT);
        } else if ("assistant".equalsIgnoreCase(role) || "model".equalsIgnoreCase(role)) {
            normalized = truncateEventText(normalized, MAX_ASSISTANT_TEXT);
        }

        snapshot.getRawEvents().add(normalized);
        mergeStateDelta(snapshot, normalized);
        trimEvents(snapshot.getRawEvents());
        snapshot.setLastUpdateTime(resolveUpdateTime(normalized));

        return Single.just(normalized);
    }

    /**
     * 根据 appName / userId / sessionId 查找 SessionSnapshot。
     *
     * @param appName 应用名
     * @param userId 用户 ID
     * @param sessionId 会话 ID
     * @return SessionSnapshot，不存在则返回 null
     */
    private SessionSnapshot findSnapshot(String appName, String userId, String sessionId) {
        ConcurrentMap<String, ConcurrentMap<String, SessionSnapshot>> appSessions = sessions.get(appName);
        if (appSessions == null) {
            return null;
        }
        ConcurrentMap<String, SessionSnapshot> userSessions = appSessions.get(userId);
        if (userSessions == null) {
            return null;
        }
        return userSessions.get(sessionId);
    }

    /**
     * 将内部快照对象转换为对外暴露的 ADK Session。
     *
     * 内部真实存储用的是 {@link SessionSnapshot}，
     * 对外仍然按 ADK 的 Session 语义返回，方便 Runner 正常使用。
     *
     * @param snapshot 内部快照
     * @param eventsOverride 可选的事件覆盖集
     * @return ADK Session
     */
    private Session toSession(SessionSnapshot snapshot, Optional<List<Event>> eventsOverride) {
        List<Event> events = eventsOverride.orElseGet(snapshot::getRawEvents);
        Session session = Session.builder(snapshot.getSessionKey())
                .state(snapshot.getState())
                .events(events)
                .build();
        session.lastUpdateTime(snapshot.getLastUpdateTime());
        return session;
    }

    /**
     * 规范化 Event，补齐时间戳和事件 ID。
     *
     * 这样后面做更新时间同步、时间过滤、事件追踪时会更稳定。
     *
     * @param event 原始事件
     * @return 规范化后的事件
     */
    private Event normalizeEvent(Event event) {
        Event normalized = event.toBuilder().build();
        if (normalized.timestamp() <= 0) {
            normalized.setTimestamp(System.currentTimeMillis());
        }
        if (normalized.id() == null || normalized.id().isBlank()) {
            normalized.setId(Event.generateEventId());
        }
        return normalized;
    }

    /**
     * 解析事件角色。
     *
     * 优先取 content.role，取不到时回退到 author。
     * 角色判断准确，后面的“净化 / 截断 / 裁剪”才会准确。
     *
     * @param event 事件
     * @return role
     */
    private String resolveRole(Event event) {
        return event.content().flatMap(Content::role).orElse(event.author());
    }

    /**
     * 对 user 事件做净化，去掉动态 Prompt 后缀，只保留原始用户问题。
     *
     * 这一步是“避免上下文重复”的关键动作之一。
     * 因为环境信息、最近命令、关键事件等内容，业务层已经维护了一份，
     * 不应该再在框架层 Session 里无限重复保存。
     *
     * 案例：
     * 
     *   输入：
     *   请继续查看 error.log
     *   ---
     *   [里程碑]
     *   系统: Linux
     *   [关键事件]
     *   - permission denied
     *
     *   输出：
     *   请继续查看 error.log
     * 
     *
     * @param event user 事件
     * @return 净化后的事件
     */
    private Event sanitizeUserEvent(Event event) {
        String text = event.stringifyContent();
        if (text.isBlank()) {
            return event;
        }

        String actualUserMessage = PromptEnvelope.extractUserMessage(text);
        if (actualUserMessage.equals(text)) {
            return event;
        }

        Event sanitized = event.toBuilder().build();
        Content content = sanitized.content().orElse(Content.builder().role("user").build());
        String role = content.role().orElse("user");
        sanitized.setContent(Content.builder()
                .role(role)
                .parts(List.of(Part.fromText(actualUserMessage)))
                .build());
        return sanitized;
    }

    /**
     * 截断过长的事件文本。
     *
     * 这一步的核心思想是：框架层 Session 保留“够用信息”，
     * 而不是无上限保存完整原文。否则像日志、配置文件、命令输出等大文本，
     * 很容易把 ADK Session 撑得越来越重。
     *
     * 案例：
     * 
     *   tool 返回 3000 字符日志
     *   maxLength = 1000
     *
     *   截断结果：
     *   前 1000 字符 + "..."
     * 
     *
     * @param event 原始事件
     * @param maxLength 最大长度
     * @return 截断后的事件
     */
    private Event truncateEventText(Event event, int maxLength) {
        String text = event.stringifyContent();
        if (text.length() <= maxLength) {
            return event;
        }

        Event truncated = event.toBuilder().build();
        Content content = truncated.content().orElse(null);
        String role = content == null ? "assistant" : content.role().orElse("assistant");
        truncated.setContent(Content.builder()
                .role(role)
                .parts(List.of(Part.fromText(text.substring(0, maxLength) + "...")))
                .build());
        return truncated;
    }

    /**
     * 合并 event.actions.stateDelta 到 SessionSnapshot.state。
     *
     * 这里不重复保存整段执行历史，而是只同步“最新状态变化”，
     * 让 Session 维持一个轻量的当前运行态。
     *
     * @param snapshot 会话快照
     * @param event 当前事件
     */
    private void mergeStateDelta(SessionSnapshot snapshot, Event event) {
        if (event.actions() == null || event.actions().stateDelta() == null || event.actions().stateDelta().isEmpty()) {
            return;
        }

        for (Map.Entry<String, Object> entry : event.actions().stateDelta().entrySet()) {
            if (State.REMOVED.equals(entry.getValue())) {
                snapshot.getState().remove(entry.getKey());
            } else {
                snapshot.getState().put(entry.getKey(), entry.getValue());
            }
        }
    }

    /**
     * 裁剪事件列表。
     *
     * 先按轮次裁剪，再按总条数兜底裁剪。
     * 这样比单纯“超过 20 条删最前面”更符合对话语义。
     *
     * @param events 事件列表
     */
    private void trimEvents(List<Event> events) {
        trimByTurn(events);
        while (events.size() > MAX_EVENTS) {
            events.remove(0);
        }
    }

    /**
     * 按用户轮次裁剪事件。
     *
     * 算法思路：
     * 从后往前遍历事件，持续回收最近消息；
     * 每遇到一条 user 消息，视为进入上一轮；
     * 当累计到 MAX_TURNS 轮后停止。
     *
     * 案例：
     * 
     *   原始事件：
     *   U1 A1 U2 A2 U3 A3 U4 A4 U5 A5
     *
     *   MAX_TURNS = 4
     *
     *   从后往前回收：
     *   U5 A5 U4 A4 U3 A3 U2 A2
     *
     *   最终保留：
     *   U2 A2 U3 A3 U4 A4 U5 A5
     * 
     *
     * 这样做的优势是，保留下来的往往是更完整的最近几轮对话，
     * 而不是被消息条数硬裁成东一块西一块的碎片。
     *
     * @param events 原始事件列表
     */
    private void trimByTurn(List<Event> events) {
        if (events.isEmpty()) {
            return;
        }

        LinkedList<Event> trimmed = new LinkedList<>();
        int userTurnCount = 0;
        for (int i = events.size() - 1; i >= 0; i--) {
            Event current = events.get(i);
            trimmed.addFirst(current);
            if ("user".equalsIgnoreCase(resolveRole(current))) {
                userTurnCount++;
                if (userTurnCount >= MAX_TURNS) {
                    break;
                }
            }
        }

        events.clear();
        events.addAll(trimmed);
    }

    /**
     * 解析事件更新时间。
     *
     * @param event 当前事件
     * @return 更新时间
     */
    private Instant resolveUpdateTime(Event event) {
        if (event.timestamp() > 0) {
            return Instant.ofEpochMilli(event.timestamp());
        }
        return Instant.now();
    }
}
