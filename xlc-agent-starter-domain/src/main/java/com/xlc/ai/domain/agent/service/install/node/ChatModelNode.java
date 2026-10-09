package com.xlc.ai.domain.agent.service.install.node;

import com.xlc.ai.domain.agent.model.entity.InstallCommandEntity;
import com.xlc.ai.domain.agent.model.valobj.AgentConfigure;
import com.xlc.ai.domain.agent.model.valobj.AiAgentRegisterVO;
import com.xlc.ai.domain.agent.service.install.AbstractInstallSupport;
import com.xlc.ai.domain.agent.service.install.factory.DefaultInstallFactory;
import com.xlc.ai.domain.agent.service.install.matter.mcp.client.ToolMcpCreateService;
import com.xlc.ai.domain.agent.service.install.matter.mcp.client.factory.DefaultMcpClientFactory;
import com.xlc.ai.domain.agent.service.install.matter.skills.ToolSkillsCreateService;
import com.xlc.ai.core.framework.StrategyHandler;
import org.apache.commons.lang3.StringUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
public class ChatModelNode extends AbstractInstallSupport {

    @Resource
    private AgentNode agentNode;

    @Resource
    private DefaultMcpClientFactory defaultMcpClientFactory;

    @Resource
    private ToolSkillsCreateService toolSkillsCreateService;

    @Override
    protected AiAgentRegisterVO doApply(InstallCommandEntity requestParameter, DefaultInstallFactory.DynamicContext dynamicContext) throws Exception {
        log.info("Ai Agent 装配操作 - ChatModelNode");

        // 获取上下文对象
        OpenAiApi openAiApi = dynamicContext.getOpenAiApi();

        // 获取配置对象
        AgentConfigure agentConfigure = requestParameter.getAgentConfigure();
        AgentConfigure.Module.ChatModel chatModelConfig = agentConfigure.getModule().getChatModel();
        List<AgentConfigure.Module.ChatModel.ToolMcp> toolMcpList = chatModelConfig.getToolMcpList();
        List<AgentConfigure.Module.ChatModel.ToolSkills> toolSkillsList = chatModelConfig.getToolSkillsList();

        // 构建mcp服务（工厂）
        List<ToolCallback> toolCallbackList = new ArrayList<>();

        if (null != toolMcpList && !toolMcpList.isEmpty()) {
            for (AgentConfigure.Module.ChatModel.ToolMcp toolMcp : toolMcpList) {
                ToolMcpCreateService toolMcpCreateService = defaultMcpClientFactory.getTooMcpCreateService(toolMcp);
                ToolCallback[] toolCallbacks = toolMcpCreateService.buildToolCallback(toolMcp);
                toolCallbackList.addAll(List.of(toolCallbacks));
            }
        }

        // 构建skills服务
        if (null != toolSkillsList && !toolSkillsList.isEmpty()) {
            for (AgentConfigure.Module.ChatModel.ToolSkills toolSkills : toolSkillsList) {
                ToolCallback[] toolCallbacks = toolSkillsCreateService.buildToolCallback(toolSkills);
                toolCallbackList.addAll(List.of(toolCallbacks));
            }
        }

        // 构建对话模型
        OpenAiChatOptions.Builder optionsBuilder = OpenAiChatOptions.builder()
                .model(chatModelConfig.getModel())
                .toolCallbacks(toolCallbackList)
                // 开启流式 usage 统计：OpenAI 协议要求 stream_options.include_usage=true，
                // 末块才返回完整 usage（含 prompt_tokens_details.cached_tokens 缓存命中）。
                .streamUsage(true);

        // 推理强度（仅推理模型生效，非推理模型忽略）
        String reasoningEffort = chatModelConfig.getReasoningEffort();
        if (StringUtils.isNotBlank(reasoningEffort)) {
            optionsBuilder.reasoningEffort(reasoningEffort);
        }

        ChatModel rawChatModel = OpenAiChatModel.builder()
                .openAiApi(openAiApi)
                .defaultOptions(optionsBuilder.build())
                .build();

        dynamicContext.setChatModel(rawChatModel);

        return router(requestParameter, dynamicContext);
    }

    @Override
    public StrategyHandler<InstallCommandEntity, DefaultInstallFactory.DynamicContext, AiAgentRegisterVO> get(InstallCommandEntity requestParameter, DefaultInstallFactory.DynamicContext dynamicContext) throws Exception {
        return agentNode;
    }

}
