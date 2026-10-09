package com.xlc.ai.domain.agent.service.chat;

import com.xlc.ai.domain.agent.adapter.repository.IChatHistoryRepository;
import com.xlc.ai.domain.agent.model.entity.ChatCommandEntity;
import com.xlc.ai.domain.agent.model.entity.ChatMessageEntity;
import com.xlc.ai.domain.agent.model.entity.ChatSessionEntity;
import com.xlc.ai.domain.agent.model.valobj.AgentConfigure;
import com.xlc.ai.domain.agent.model.valobj.AiAgentRegisterVO;
import com.xlc.ai.domain.agent.service.IChatService;
import com.xlc.ai.domain.agent.service.install.factory.DefaultInstallFactory;
import com.xlc.ai.types.enums.ResponseCode;
import com.xlc.ai.types.exception.AppException;
import com.google.adk.agents.RunConfig;
import com.google.adk.events.Event;
import com.google.adk.runner.Runner;
import com.google.adk.sessions.Session;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Flowable;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class ChatService implements IChatService {

    private static final RunConfig STREAM_RUN_CONFIG = RunConfig.builder()
            .streamingMode(RunConfig.StreamingMode.SSE)
            .autoCreateSession(true)
            .build();

    @Resource
    private DefaultInstallFactory defaultInstallFactory;

    @Resource
    private AgentConfigure agentConfigure;

    @Resource
    private IChatHistoryRepository chatHistoryRepository;

    private final Map<String, String> userSessions = new ConcurrentHashMap<>();


    @Override
    public AgentConfigure.Agent queryAiAgentConfigList() {
        return agentConfigure.getAgent();
    }

    @Override
    public String createSession(String agentId, String userId) {
        AiAgentRegisterVO aiAgentRegisterVO = defaultInstallFactory.getAiAppRegisterVO(agentId);

        if (null == aiAgentRegisterVO) {
            throw new AppException(ResponseCode.E0001.getCode());
        }

        String appName = aiAgentRegisterVO.getAgentName();
        Runner runner = aiAgentRegisterVO.getRunner();
        String sessionKey = agentId + ":" + userId;

        String sessonId = userSessions.computeIfAbsent(sessionKey, key -> {
            Session session = runner.sessionService().createSession(appName, userId)
                    .blockingGet();

            // 会话元数据落库：try-catch 旁路写入，DB 写入失败不影响 ADK Session 创建。
            // 旁路原则：如果 DB 异常（如表不存在），只是 DB 里没有这条记录，不影响 Agent 正常运行。
            try {
                chatHistoryRepository.saveSession(ChatSessionEntity.builder()
                        .id(session.id())
                        .agentId(agentId)
                        .userId(userId)
                        .title("新会话")
                        .messageCount(0)
                        .build());
            } catch (Exception e) {
                log.error("保存会话元数据失败 sessionId={}", session.id(), e);
            }

            return session.id();
        });
        return sessonId;
    }

    @Override
    public List<ChatSessionEntity> querySessionList(String agentId, String userId, int limit) {
        return chatHistoryRepository.querySessionList(agentId, userId, limit > 0 ? limit : 20);
    }


    @Override
    public List<ChatMessageEntity> queryMessageList(String sessionId, int limit) {
        return chatHistoryRepository.queryMessageList(sessionId, limit > 0 ? limit : 100);
    }

    @Override
    public Flowable<Event> handleMessageStream(String agentId, String userId, String sessionId, String message) {
        AiAgentRegisterVO aiAgentRegisterVO = defaultInstallFactory.getAiAppRegisterVO(agentId);

        if (null == aiAgentRegisterVO) {
            throw new AppException(ResponseCode.E0001.getCode());
        }

        Runner runner = aiAgentRegisterVO.getRunner();

        Content userMsg = Content.fromParts(Part.fromText(message));
        return runner.runAsync(userId, sessionId, userMsg, STREAM_RUN_CONFIG);
    }

    @Override
    public List<String> handleMessage(ChatCommandEntity chatCommandEntity) {
        AiAgentRegisterVO aiAgentRegisterVO = defaultInstallFactory.getAiAppRegisterVO(chatCommandEntity.getAgentId());

        if (null == aiAgentRegisterVO) {
            throw new AppException(ResponseCode.E0001.getCode());
        }

        List<Part> parts = new ArrayList<>();

        List<ChatCommandEntity.Content.Text> texts = chatCommandEntity.getTexts();
        if (null != texts && !texts.isEmpty()) {
            for (ChatCommandEntity.Content.Text text : texts) {
                parts.add(Part.fromText(text.getMessage()));
            }
        }

        List<ChatCommandEntity.Content.File> files = chatCommandEntity.getFiles();
        if (null != files && !files.isEmpty()) {
            for (ChatCommandEntity.Content.File file : files) {
                parts.add(Part.fromUri(file.getFileUri(), file.getMimeType()));
            }
        }

        List<ChatCommandEntity.Content.InlineData> inlineDatas = chatCommandEntity.getInlineDatas();
        if (null != inlineDatas && !inlineDatas.isEmpty()) {
            for (ChatCommandEntity.Content.InlineData inlineData : inlineDatas) {
                parts.add(Part.fromBytes(inlineData.getBytes(), inlineData.getMimeType()));
            }
        }

        Content content = Content.builder().role("user").parts(parts).build();

        // 获取运行体
        Runner runner = aiAgentRegisterVO.getRunner();

        Flowable<Event> events = runner.runAsync(chatCommandEntity.getUserId(), chatCommandEntity.getSessionId(), content);

        List<String> outputs = new ArrayList<>();
        events.blockingForEach(event -> outputs.add(event.stringifyContent()));

        return outputs;
    }

    @Override
    public List<String> handleMessage(String agentId, String userId, String sessionId, String message) {
        AiAgentRegisterVO aiAgentRegisterVO = defaultInstallFactory.getAiAppRegisterVO(agentId);

        if (null == aiAgentRegisterVO) {
            throw new AppException(ResponseCode.E0001.getCode());
        }

        Runner runner = aiAgentRegisterVO.getRunner();

        Content userMsg = Content.fromParts(Part.fromText(message));
        Flowable<Event> events = runner.runAsync(userId, sessionId, userMsg);

        List<String> outputs = new ArrayList<>();
        events.blockingForEach(event -> outputs.add(event.stringifyContent()));
        return outputs;
    }



}
