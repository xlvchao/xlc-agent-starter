package com.xlc.ai.domain.agent.service.install.node;

import com.xlc.ai.core.framework.StrategyHandler;
import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.LlmAgent;
import com.google.adk.tools.FunctionTool;
import com.xlc.ai.domain.agent.model.entity.InstallCommandEntity;
import com.xlc.ai.domain.agent.model.valobj.AgentConfigure;
import com.xlc.ai.domain.agent.model.valobj.AiAgentRegisterVO;
import com.xlc.ai.domain.agent.service.install.AbstractInstallSupport;
import com.xlc.ai.domain.agent.service.install.factory.DefaultInstallFactory;
import com.xlc.ai.domain.agent.service.install.matter.patch.MySpringAI;
import com.xlc.ai.domain.agent.service.install.matter.session.factory.CustomRunnerFactory;
import com.xlc.ai.domain.agent.service.install.matter.tools.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;


@Slf4j
@Service
public class AgentNode extends AbstractInstallSupport {

    @Resource
    private AgentWorkflowNode agentWorkflowNode;

    @Resource
    private SampleAdkTool sampleAdkTool;

    @Resource
    private CustomRunnerFactory customRunnerFactory;

    @Resource
    private DynamicAgentOrchestrator dynamicAgentOrchestrator;

    @Resource
    private PlannerAgentBuilder plannerAgentBuilder;

    @Resource
    private PlanParser planParser;

    @Resource
    private PlanValidator planValidator;


    @Override
    protected AiAgentRegisterVO doApply(InstallCommandEntity requestParameter, DefaultInstallFactory.DynamicContext dynamicContext) throws Exception {
        log.info("Ai Agent 装配操作 - AgentNode");

        ChatModel chatModel = dynamicContext.getChatModel();

        AgentConfigure agentConfigure = requestParameter.getAgentConfigure();
        List<AgentConfigure.Module.Agent> agents = agentConfigure.getModule().getAgents();
        AgentConfigure.Module.ChatModel chatModelConfig = agentConfigure.getModule().getChatModel();

        for (AgentConfigure.Module.Agent agentConfig : agents) {
            MySpringAI modelAdapter;
            if (chatModel != null) {
                modelAdapter = new MySpringAI(chatModel, chatModel, chatModelConfig.getModel());
            } else {
                modelAdapter = new MySpringAI(null, chatModelConfig.getModel());
            }

            //ReAct流程用到
            dynamicContext.getAgentOutputKeyMap().put(agentConfig.getName(), agentConfig.getOutputKey());

            LlmAgent.Builder builder = LlmAgent.builder()
                    .name(agentConfig.getName())
                    .description(agentConfig.getDescription())
                    .model(modelAdapter)
                    .instruction(agentConfig.getInstruction())
                    .outputKey(agentConfig.getOutputKey());

            // 构建 ADK 工具列表 - 注意，这部分也可以提炼到配置文件
            List<Object> adkTools = new ArrayList<>();

            // 添加工具（ADK 原生 FunctionTool）
            try {
                log.info("开始创建 FunctionTool, sampleAdkTool={}", sampleAdkTool);
                FunctionTool functionTool = FunctionTool.create(sampleAdkTool, "executeCommand");
                log.info("FunctionTool 创建成功: name={}, declaration={}",
                        functionTool.name(),
                        functionTool.declaration().isPresent() ? functionTool.declaration().get() : "null");
                adkTools.add(functionTool);
                log.info("为 Agent [{}] 注册工具成功", agentConfig.getName());
            } catch (Exception e) {
                log.error("创建ADK 工具失败", e);
            }

            // 注册工具到 Agent
            if (!adkTools.isEmpty()) {
                log.info("为 Agent [{}] 注册 {} 个工具", agentConfig.getName(), adkTools.size());
                builder.tools(adkTools);
            } else {
                log.warn("Agent [{}] 没有注册任何工具！", agentConfig.getName());
            }

            LlmAgent llmAgent = builder.build();

            dynamicContext.getAgentGroup().put(agentConfig.getName(), llmAgent);
        }

        // 子agent派发的核心机制，就是把其他智能体作为工具使用（要不给你留个小作业，把subagent的构建，放到下一个单独的 SubAgentNode 节点，串联使用）
        buildAgentTools(
                dynamicContext,
                agents,
                chatModel,
                agentConfigure.getModule().getChatModel().getModel());

        return router(requestParameter, dynamicContext);
    }

    /**
     * 为配置了 subAgents 的父 Agent 重新装配"多 Agent 派发"能力：
     * <ol>
     *   把每个声明的子 Agent 包装成 {@link SubAgentDispatchTool}（单 Agent 派发工具）
     *   追加 {@link BatchSubAgentDispatchTool}（主 Agent 自行拆解任务的批量派发工具）
     *   追加 {@link DynamicPlanDispatchTool}（由独立规划器生成计划的动态派发工具）
     * </ol>
     * 用同一套配置重建父 Agent 并覆盖 agentGroup，使其获得派发类工具；
     * 子 Agent 未在配置中声明时直接抛出异常，fail-fast。
     */
    private void buildAgentTools(
            DefaultInstallFactory.DynamicContext dynamicContext,
            List<AgentConfigure.Module.Agent> agents,
            ChatModel chatModel,
            String modelName) throws Exception {

        Map<String, BaseAgent> agentGroup = dynamicContext.getAgentGroup();

        for (AgentConfigure.Module.Agent agentConfig : agents) {
            // 未声明 subAgents 的 Agent 不需要派发能力，跳过重建
            List<String> subAgentNames = agentConfig.getSubAgents();
            if (subAgentNames == null || subAgentNames.isEmpty()) {
                continue;
            }

            List<Object> adkTools = new ArrayList<>();

            // 为每个声明的子 Agent 构建单独的派发工具（工具名即子 Agent 名，LLM 可直接点名调用）
            for (String subAgentName : subAgentNames) {
                BaseAgent subAgent = agentGroup.get(subAgentName);
                if (subAgent == null) {
                    throw new IllegalArgumentException(
                            "sub agent not found: " + subAgentName);
                }
                adkTools.add(new SubAgentDispatchTool(subAgent, customRunnerFactory));
            }

            // 批量派发工具：主 Agent 自行拆解任务列表并发派发 2-11节，agentEventPublisher 推送。前后有好几个地方都要有这个。
            adkTools.add(new BatchSubAgentDispatchTool(
                    agents.stream().map(AgentConfigure.Module.Agent::getName).toList(),
                    dynamicAgentOrchestrator));

            // 动态规划派发工具：由独立规划器 LLM 生成任务计划后派发
            adkTools.add(new DynamicPlanDispatchTool(
                    plannerAgentBuilder,
                    dynamicAgentOrchestrator,
                    planParser,
                    planValidator,
                    dynamicContext.getOpenAiApi(),
                    modelName,
                    agents.stream().map(AgentConfigure.Module.Agent::getName).toList()));

            // 和前面node节点里一样，创建智能体
            LlmAgent parentAgent = LlmAgent.builder()
                    .name(agentConfig.getName())
                    .description(agentConfig.getDescription())
                    .model(new MySpringAI(chatModel, chatModel, modelName))
                    .instruction(agentConfig.getInstruction())
                    .outputKey(agentConfig.getOutputKey())
                    .tools(adkTools)
                    .build();

            agentGroup.put(agentConfig.getName(), parentAgent);
        }
    }

    @Override
    public StrategyHandler<InstallCommandEntity, DefaultInstallFactory.DynamicContext, AiAgentRegisterVO> get(InstallCommandEntity requestParameter, DefaultInstallFactory.DynamicContext dynamicContext) throws Exception {
        return agentWorkflowNode;
    }

}
