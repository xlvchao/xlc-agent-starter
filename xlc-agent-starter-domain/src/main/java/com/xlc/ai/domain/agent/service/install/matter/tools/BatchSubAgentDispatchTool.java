package com.xlc.ai.domain.agent.service.install.matter.tools;

import com.xlc.ai.domain.agent.model.valobj.dynamic.AgentExecutionContext;
import com.xlc.ai.domain.agent.model.valobj.dynamic.DynamicTask;
import com.xlc.ai.domain.agent.model.valobj.dynamic.DynamicTaskPlan;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.adk.tools.BaseTool;
import com.google.adk.tools.ToolContext;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.Schema;
import io.reactivex.rxjava3.core.Single;
import lombok.extern.slf4j.Slf4j;

import java.util.*;

/**
 * 批量子 Agent 派发工具 - 主 Agent 一次函数调用即可并发派发多个子 Agent 任务。
 * 
 * 与 {@link DynamicPlanDispatchTool}（先由独立规划器生成计划）不同，
 * 本工具由主 Agent 在函数调用参数中直接给出任务列表（含依赖关系），
 * 经校验后交给编排器按 DAG 并发执行，用于主 Agent 自主拆解的批量诊断场景。
 * 
 * 注：本工具在装配阶段创建（非 Spring 管理），构造时传入允许派发的 Agent 白名单。
 */
/**
 * 批量子 Agent 派发工具 - 主 Agent 一次函数调用即可并发派发多个子 Agent 任务。
 * 
 * 与 {@link DynamicPlanDispatchTool}（先由独立规划器生成计划）不同，
 * 本工具由主 Agent 在函数调用参数中直接给出任务列表（含依赖关系），
 * 经校验后交给编排器按 DAG 并发执行，用于主 Agent 自主拆解的批量诊断场景。
 * 
 * 注：本工具在装配阶段创建（非 Spring 管理），构造时传入允许派发的 Agent 白名单。
 */
@Slf4j
public class BatchSubAgentDispatchTool extends BaseTool {

    /** 允许派发的子 Agent 名称白名单（所有已装配的 Agent） */
    private final List<String> allowedAgents;

    /** DAG 编排器，负责任务并发调度 */
    private final DynamicAgentOrchestrator orchestrator;

    /** 计划解析/校验器：解析函数调用参数并校验依赖合法性 */
    private final PlanParser planParser = new PlanParser();
    private final PlanValidator planValidator = new PlanValidator();
    private final ObjectMapper objectMapper = new ObjectMapper();

    public BatchSubAgentDispatchTool(List<String> allowedAgents, DynamicAgentOrchestrator orchestrator) {
        super("dispatchSubAgents", "批量派发多个已配置子Agent，支持任务依赖、并行执行和结果汇总");
        this.allowedAgents = List.copyOf(allowedAgents);
        this.orchestrator = orchestrator;
    }

    /**
     * 声明工具的函数签名：
     * tasks 为任务数组（每项含 agentName、request，可选 taskId/dependsOn/timeoutSeconds/maxRetries），
     * maxConcurrency 为可选并发上限，failFast 为可选的失败即中止开关。
     */
    @Override
    public Optional<FunctionDeclaration> declaration() {
        Schema taskSchema = Schema.builder()
                .type("OBJECT")
                .properties(ImmutableMap.of(
                        "taskId", Schema.builder().type("STRING").build(),
                        "agentName", Schema.builder().type("STRING").build(),
                        "request", Schema.builder().type("STRING").build(),
                        "dependsOn", Schema.builder().type("ARRAY")
                                .items(Schema.builder().type("STRING").build()).build(),
                        "timeoutSeconds", Schema.builder().type("INTEGER").build(),
                        "maxRetries", Schema.builder().type("INTEGER").build()))
                .required(ImmutableList.of("agentName", "request"))
                .build();

        return Optional.of(FunctionDeclaration.builder()
                .name(name())
                .description(description())
                .parameters(Schema.builder()
                        .type("OBJECT")
                        .properties(ImmutableMap.of(
                                "tasks", Schema.builder().type("ARRAY").items(taskSchema).build(),
                                "maxConcurrency", Schema.builder().type("INTEGER").build(),
                                "failFast", Schema.builder().type("BOOLEAN").build()))
                        .required(ImmutableList.of("tasks"))
                        .build())
                .build());
    }

    /**
     * 批量派发流程：解析参数 → 补全 taskId → 构建计划并校验（上限 10）→
     * 白名单校验 → 从 ToolContext 提取执行上下文（含父会话绑定的终端会话）→
     * 交给编排器按 DAG 执行 → 返回 {success, tasks, allSucceeded}。
     * 任何环节失败都返回 {success:false, error:...}，不向上抛异常。
     */
    @Override
    public Single<Map<String, Object>> runAsync(Map<String, Object> args, ToolContext toolContext) {
        try {
            // 解析函数调用参数中的任务列表
            List<Map<String, Object>> rawTasks = objectMapper.convertValue(args.get("tasks"), new com.fasterxml.jackson.core.type.TypeReference<>() {});
            if (rawTasks == null || rawTasks.isEmpty()) {
                return Single.just(ImmutableMap.of("success", false, "error", "tasks is empty"));
            }

            // 转为 DynamicTask 并补全缺失的 taskId
            List<DynamicTask> tasks = rawTasks.stream()
                    .map(item -> objectMapper.convertValue(item, DynamicTask.class))
                    .peek(task -> {
                        if (task.getTaskId() == null || task.getTaskId().isBlank()) {
                            task.setTaskId("task-" + UUID.randomUUID());
                        }
                    })
                    .toList();

            // 构建计划（maxConcurrency 默认 4）并校验结构/依赖合法性，任务数上限 10
            DynamicTaskPlan plan =
                    DynamicTaskPlan.builder()
                            .tasks(tasks)
                            .maxConcurrency(args.get("maxConcurrency") instanceof Number number ? number.intValue() : 4)
                            .failFast(Boolean.TRUE.equals(args.get("failFast")))
                            .build();
            planValidator.validate(plan, 10);

            // Agent 白名单校验：任务中不允许出现未装配的 Agent
            List<String> requestedAgents = tasks.stream().map(DynamicTask::getAgentName).toList();
            if (!new HashSet<>(allowedAgents).containsAll(requestedAgents)) {
                return Single.just(ImmutableMap.of("success", false, "error", "agent not allowed"));
            }

            // 构建执行上下文：透传给子Agent
            // toolContext 可能为 null（如 LLM 直接触发工具调用而未经过完整 Runner 会话），需兜底
            AgentExecutionContext context = AgentExecutionContext.builder()
                    .userId(toolContext != null ? toolContext.userId() : "unknown")
                    .agentId(toolContext != null ? toolContext.agentName() : "unknown")
                    .parentSessionId(toolContext != null ? toolContext.invocationId() : "unknown")
                    .build();

            // 到这开始执行任务计划
            Map<String, Object> result = orchestrator.execute(context, plan);
            result.put("success", result.get("allSucceeded"));

            return Single.just(result);
        } catch (Exception exception) {
            log.error("批量子Agent派发失败", exception);
            return Single.just(ImmutableMap.of("success", false, "error", String.valueOf(exception.getMessage())));
        }

    }

}
