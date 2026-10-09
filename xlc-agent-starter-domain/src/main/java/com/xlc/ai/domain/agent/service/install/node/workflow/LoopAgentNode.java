package com.xlc.ai.domain.agent.service.install.node.workflow;

import com.xlc.ai.domain.agent.model.entity.InstallCommandEntity;
import com.xlc.ai.domain.agent.model.valobj.AgentConfigure;
import com.xlc.ai.domain.agent.model.valobj.AiAgentRegisterVO;
import com.xlc.ai.domain.agent.service.install.AbstractInstallSupport;
import com.xlc.ai.domain.agent.service.install.factory.DefaultInstallFactory;
import com.xlc.ai.core.framework.StrategyHandler;
import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.LoopAgent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service("loopAgentNode")
public class LoopAgentNode extends AbstractInstallSupport {

    @Override
    protected AiAgentRegisterVO doApply(InstallCommandEntity requestParameter, DefaultInstallFactory.DynamicContext dynamicContext) throws Exception {
        log.info("Ai Agent 装配操作 - LoopAgentNode");

        AgentConfigure.Module.AgentWorkflow currentAgentWorkflow = dynamicContext.getCurrentAgentWorkflow();

        List<String> subAgentNames = currentAgentWorkflow.getSubAgents();
        List<BaseAgent> subAgents = dynamicContext.queryAgentList(subAgentNames);

        LoopAgent loopAgent =
                LoopAgent.builder()
                        .name(currentAgentWorkflow.getName())
                        .description(currentAgentWorkflow.getDescription())
                        .subAgents(subAgents)
                        .maxIterations(currentAgentWorkflow.getMaxIterations())
                        .build();

        dynamicContext.getAgentGroup().put(currentAgentWorkflow.getName(), loopAgent);

        return router(requestParameter, dynamicContext);
    }

    @Override
    public StrategyHandler<InstallCommandEntity, DefaultInstallFactory.DynamicContext, AiAgentRegisterVO> get(InstallCommandEntity requestParameter, DefaultInstallFactory.DynamicContext dynamicContext) throws Exception {
        return getBean("agentWorkflowNode");
    }

}
