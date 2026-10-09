package com.xlc.ai.trigger.http;

import com.xlc.ai.core.dto.*;
import com.xlc.ai.core.response.Response;
import com.xlc.ai.cases.IReActChatService;
import com.xlc.ai.domain.agent.model.valobj.AgentConfigure;
import com.xlc.ai.domain.agent.service.IChatService;
import com.xlc.ai.infrastructure.sse.SseUtils;
import com.xlc.ai.types.enums.ResponseCode;
import com.xlc.ai.types.exception.AppException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import javax.annotation.Resource;

/**
 * 智能体查询、会话管理、对话管理
 *
 * @author xlvchao
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/")
@CrossOrigin(origins = "*")
public class AgentServiceController {

    @Resource
    private IChatService chatService;

    @Resource
    private IReActChatService reActChatService;


    @RequestMapping(value = "query_agent_config", method = RequestMethod.GET)
    public Response<AgentConfigDTO> queryAgentConfig() {
        try {
            log.info("查询智能体配置");

            AgentConfigure.Agent agentConfig = chatService.queryAiAgentConfigList();
            AgentConfigDTO responseDTO = new AgentConfigDTO();
            responseDTO.setAgentId(agentConfig.getAgentId());
            responseDTO.setAppName(agentConfig.getAgentName());
            responseDTO.setAppDesc(agentConfig.getAgentDesc());

            return Response.<AgentConfigDTO>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(responseDTO)
                    .build();

        } catch (AppException e) {
            log.error("查询智能体配置列表异常", e);
            return Response.<AgentConfigDTO>builder()
                    .code(e.getCode())
                    .info(e.getInfo())
                    .build();
        } catch (Exception e) {
            log.error("查询智能体配置列表失败", e);
            return Response.<AgentConfigDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }

    @RequestMapping(value = "create_session", method = RequestMethod.POST)
    public Response<CreateSessionResponseDTO> createSession(@RequestBody CreateSessionRequestDTO requestDTO) {
        try {
            log.info("创建会话 agentId:{} userId:{}", requestDTO.getAgentId(), requestDTO.getUserId());
            String sessionId = chatService.createSession(requestDTO.getAgentId(), requestDTO.getUserId());

            CreateSessionResponseDTO responseDTO = new CreateSessionResponseDTO();
            responseDTO.setSessionId(sessionId);

            return Response.<CreateSessionResponseDTO>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(responseDTO)
                    .build();
        } catch (AppException e) {
            log.error("查询智能体配置列表异常", e);
            return Response.<CreateSessionResponseDTO>builder()
                    .code(e.getCode())
                    .info(e.getInfo())
                    .build();
        } catch (Exception e) {
            log.error("创建会话失败 agentId:{} userId:{}", requestDTO.getAgentId(), requestDTO.getUserId(), e);
            return Response.<CreateSessionResponseDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }

    @RequestMapping(value = "chat", method = RequestMethod.POST)
    public Response<ChatResponseDTO> chat(@RequestBody ChatRequestDTO requestDTO) {
        try {
            log.info("智能体对话 agentId:{} userId:{}", requestDTO.getAgentId(), requestDTO.getUserId());
            String sessionId = requestDTO.getSessionId();
            if (sessionId == null || sessionId.isEmpty()) {
                sessionId = chatService.createSession(requestDTO.getAgentId(), requestDTO.getUserId());
            }

            String content = reActChatService.chat(
                    new ChatRequestDTO(requestDTO.getAgentId(),
                            requestDTO.getUserId(),
                            sessionId,
                            requestDTO.getMessage())
            );

            ChatResponseDTO responseDTO = new ChatResponseDTO();
            responseDTO.setContent(content);

            return Response.<ChatResponseDTO>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(responseDTO)
                    .build();
        } catch (AppException e) {
            log.error("智能体对话异常", e);
            return Response.<ChatResponseDTO>builder()
                    .code(e.getCode())
                    .info(e.getInfo())
                    .build();
        } catch (Exception e) {
            log.error("智能体对话败 agentId:{} userId:{}", requestDTO.getAgentId(), requestDTO.getUserId(), e);
            return Response.<ChatResponseDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }

    @RequestMapping(value = "chat_stream", method = RequestMethod.POST, produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseBodyEmitter chatStream(@RequestBody ChatRequestDTO requestDTO) {
        try {
            log.info("ReAct流式对话 agentId:{} userId:{} sessionId:{} message:{}",
                    requestDTO.getAgentId(), requestDTO.getUserId(), requestDTO.getSessionId(), requestDTO.getMessage());

            String sessionId = requestDTO.getSessionId();
            if (sessionId == null || sessionId.isEmpty()) {
                sessionId = chatService.createSession(requestDTO.getAgentId(), requestDTO.getUserId());
            }

            return reActChatService.chatStream(
                    new ChatRequestDTO(requestDTO.getAgentId(),
                            requestDTO.getUserId(),
                            sessionId,
                            requestDTO.getMessage())
            );

        } catch (Exception e) {
            log.error("ReAct 流式对话初始化失败", e);
            SseEmitter emitter = SseUtils.createSseEmitter(30_000L);
            emitter.completeWithError(e);
            return emitter;
        }
    }

}
