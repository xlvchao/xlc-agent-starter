package com.xlc.ai.domain.agent.service.install.node;

import com.xlc.ai.domain.agent.model.entity.InstallCommandEntity;
import com.xlc.ai.domain.agent.model.valobj.AgentConfigure;
import com.xlc.ai.domain.agent.model.valobj.AiAgentRegisterVO;
import com.xlc.ai.domain.agent.service.install.catalog.AgentCatalog;
import com.xlc.ai.domain.agent.service.install.AbstractInstallSupport;
import com.xlc.ai.domain.agent.service.install.factory.DefaultInstallFactory;
import com.xlc.ai.domain.agent.service.install.matter.session.factory.CustomRunnerFactory;
import com.xlc.ai.types.enums.ResponseCode;
import com.xlc.ai.types.exception.AppException;
import com.google.adk.agents.BaseAgent;
import com.google.adk.plugins.BasePlugin;
import com.google.adk.runner.Runner;
import com.google.common.collect.ImmutableList;
import com.xlc.ai.core.framework.StrategyHandler;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.util.ArrayList;
import java.util.List;


/**
 * 执行节点
 *
 * @author xlvchao
 */
@Slf4j
@Service
public class RunnerNode extends AbstractInstallSupport {

    @Resource
    private CustomRunnerFactory customRunnerFactory;

    @Resource
    private AgentCatalog agentCatalog;

    protected AiAgentRegisterVO doApply(InstallCommandEntity requestParameter, DefaultInstallFactory.DynamicContext dynamicContext) throws Exception {
        log.info("Ai Agent 装配操作 - RunnerNode");

        AgentConfigure agentConfigure = requestParameter.getAgentConfigure();
        AgentConfigure.Agent agent = agentConfigure.getAgent();
        String agentId = agent.getAgentId();
        String agentName = agent.getAgentName();
        String agentDesc = agent.getAgentDesc();

        Runner runner = getRunner(dynamicContext, agentConfigure, agentName);

        AgentConfigure.Module.Runner runnerConfig = agentConfigure.getModule().getRunner();
        String runnerOutputKey = dynamicContext.getAgentOutputKeyMap().get(runnerConfig.getAgentName());

        // 把装配完成的子 Agent 分组登记到注册表，供子 Agent 派发时按名称查找
        agentCatalog.register(agentId, dynamicContext.getAgentGroup());

        AiAgentRegisterVO aiAgentRegisterVO = AiAgentRegisterVO.builder()
                .agentId(agentId)
                .agentName(agentName)
                .agentDesc(agentDesc)
                .runner(runner)
                .runnerOutputKey(runnerOutputKey)
                // 透传 Agent 的 API 配置与模型名，供意图识别等旁路能力复用（见 AiAgentRegisterVO）。这样就都统一了，都用一套LLM配置
                .openAiApi(dynamicContext.getOpenAiApi())
                .chatModelName(agentConfigure.getModule().getChatModel().getModel())
                .build();

        // 注册到 Spring 容器
        registerBean(agentId, AiAgentRegisterVO.class, aiAgentRegisterVO);

        return aiAgentRegisterVO;
    }

    private Runner getRunner(DefaultInstallFactory.DynamicContext dynamicContext, AgentConfigure agentConfigure, String appName) {
        AgentConfigure.Module.Runner runnerConfig = agentConfigure.getModule().getRunner();

        String agentName = runnerConfig.getAgentName();
        if (StringUtils.isBlank(agentName)) {
            log.error("runner.agentName is null");
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), ResponseCode.ILLEGAL_PARAMETER.getInfo());
        }

        BaseAgent baseAgent = dynamicContext.getAgentGroup().get(agentName);

        List<BasePlugin> plugins;
        List<String> pluginNameList = runnerConfig.getPluginNameList();
        if (null != pluginNameList && !pluginNameList.isEmpty()) {
            plugins = new ArrayList<>();
            for (String pluginName : pluginNameList) {
                BasePlugin plugin = getBean(pluginName);
                plugins.add(plugin);
            }
        } else {
            plugins = ImmutableList.of();
        }

        return customRunnerFactory.create(baseAgent, appName, plugins);
    }

    @Override
    public StrategyHandler<InstallCommandEntity, DefaultInstallFactory.DynamicContext, AiAgentRegisterVO> get(InstallCommandEntity requestParameter, DefaultInstallFactory.DynamicContext dynamicContext) throws Exception {
        return defaultStrategyHandler;
    }

}
