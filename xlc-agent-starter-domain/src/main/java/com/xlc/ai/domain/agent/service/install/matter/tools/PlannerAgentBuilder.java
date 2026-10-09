package com.xlc.ai.domain.agent.service.install.matter.tools;

import com.google.adk.agents.LlmAgent;
import com.google.adk.artifacts.InMemoryArtifactService;
import com.google.adk.memory.InMemoryMemoryService;
import com.google.adk.models.springai.SpringAI;
import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.RunConfig;
import com.google.adk.events.Event;
import com.google.adk.runner.Runner;
import com.google.adk.sessions.InMemorySessionService;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 规划器 Agent 构建器 - 负责把用户任务"翻译"成结构化的多 Agent 执行计划。
 * 
 * 每次调用都临时构建一个只做规划的单轮 LLM Agent（DynamicTaskPlanner），
 * 通过指令约束其仅输出 JSON 格式的任务计划（不允许 Markdown 等其他内容），
 * 输出交给 PlanParser 解析、PlanValidator 校验后执行。
 */
@Service
public class PlannerAgentBuilder {

    /**
     * 规划器系统指令：
     * 约束输出为纯 JSON 计划；简单任务不拆分，复杂任务最多 5 个并行诊断任务；
     * 变更类任务必须依赖诊断类任务；只能从允许的 Agent 列表中选择。
     */
    private static final String INSTRUCTION = """
            你是多Agent任务规划器。只输出一个JSON对象，不要输出Markdown或其他文字。
            JSON格式:
            {"tasks":[{"taskId":"唯一ID","agentName":"允许的子Agent名称","request":"完整任务指令","dependsOn":["依赖任务ID"],"timeoutSeconds":120}],"maxConcurrency":4}
            简单任务只输出一个任务。复杂任务最多5个并行诊断任务。变更任务必须依赖诊断任务。
            只能选择用户提供的允许Agent，不要创建新的Agent。
            """;

    /**
     * 执行任务规划。
     *
     * @param openAiApi     OpenAI API 客户端（复用主 Agent 的渠道配置）
     * @param modelName     规划使用的模型名称
     * @param userRequest   用户的原始任务描述
     * @param allowedAgents 允许派发的子 Agent 名称列表，同时注入指令与提示词
     * @return 规划器输出的 JSON 计划文本（解析交给 PlanParser）
     * @throws IllegalStateException 规划器执行失败或超时（60 秒）
     */
    public String plan(OpenAiApi openAiApi, String modelName, String userRequest, List<String> allowedAgents) {
        ChatModel chatModel = OpenAiChatModel.builder()
                .openAiApi(openAiApi)
                .defaultOptions(OpenAiChatOptions.builder().model(modelName).build())
                .build();

        BaseAgent planner = LlmAgent.builder()
                .name("DynamicTaskPlanner")
                .model(new SpringAI(chatModel))
                .instruction(INSTRUCTION + "\n允许Agent:" + String.join(",", allowedAgents))
                .build();

        /*
         * 这部分，没有在额外引入当前的上下文工程，使用谷歌自身提供的就够用了。
         *
         * 旧版实例化，新版用 builder 构造器了
         *  Runner runner = new Runner(planner, "dynamic-planner",
                new InMemoryArtifactService(),
                new InMemorySessionService(),
                new InMemoryMemoryService(),
                List.of());
         */

        Runner runner = Runner.builder().agent(planner)
                .appName("dynamic-planner")
                .artifactService(new InMemoryArtifactService())
                .memoryService(new InMemoryMemoryService())
                .sessionService(new InMemorySessionService())
                .plugins(List.of())
                .build();

        String prompt = "用户任务:" + userRequest + "\n允许Agent:" + String.join(",", allowedAgents);
        try {
            List<Event> events = runner.runAsync("planner-user", "planner-" + java.util.UUID.randomUUID(),
                            Content.fromParts(Part.fromText(prompt)), RunConfig.builder().autoCreateSession(true).build())
                    .timeout(60, TimeUnit.SECONDS)
                    .toList()
                    .blockingGet();

            return events.isEmpty() ? "" : events.get(events.size() - 1)
                    .content()
                    .flatMap(Content::parts)
                    .flatMap(parts -> parts.stream().map(part -> part.text().orElse("")).reduce(String::concat))
                    .orElse("");
        } catch (Exception exception) {
            throw new IllegalStateException("planner failed", exception);
        }

    }

}
