package com.xlc.ai.domain.agent.service.install.matter.tools;

import com.xlc.ai.domain.agent.model.valobj.dynamic.DynamicTask;
import com.xlc.ai.domain.agent.model.valobj.dynamic.DynamicTaskPlan;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 任务计划解析器 - 把规划器输出的文本解析为 {@link DynamicTaskPlan}。
 * 
 * 规划器输出可能夹杂说明文字，本类会截取首个 '{' 到最后一个 '}' 之间的 JSON 片段，
 * 逐项转换并补全缺失的 taskId（自动生成 task-{uuid}），
 * 同时在解析阶段就校验 Agent 白名单和请求非空，不合法直接抛出 IllegalArgumentException。
 */
@Service
public class PlanParser {
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 解析规划器输出的 JSON 文任务计划。
     *
     * @param content        规划器原始输出（可能包含 JSON 之外的文字）
     * @param allowedAgents  允许派发的子 Agent 名称白名单
     * @return 结构化任务计划（maxConcurrency 默认 4）
     * @throws IllegalArgumentException JSON 缺失、格式错误、tasks 为空、Agent 不在白名单或 request 为空
     */
    public DynamicTaskPlan parse(String content, List<String> allowedAgents) {
        try {
            Map<String, Object> root = objectMapper.readValue(extractJson(content), new TypeReference<>() {});
            Object tasksObject = root.get("tasks");
            List<Map<String, Object>> rawTasks = objectMapper.convertValue(tasksObject, new TypeReference<>() {});
            if (rawTasks == null || rawTasks.isEmpty()) {
                throw new IllegalArgumentException("tasks is empty");
            }

            List<DynamicTask> tasks = rawTasks.stream()
                    .map(item -> objectMapper.convertValue(item, DynamicTask.class))
                    .peek(task -> {
                        if (task.getTaskId() == null || task.getTaskId().isBlank()) {
                            task.setTaskId("task-" + UUID.randomUUID());
                        }
                        if (!allowedAgents.contains(task.getAgentName())) {
                            throw new IllegalArgumentException("agent not allowed: " + task.getAgentName());
                        }
                        if (task.getRequest() == null || task.getRequest().isBlank()) {
                            throw new IllegalArgumentException("task request is blank: " + task.getTaskId());
                        }
                    })
                    .toList();

            return DynamicTaskPlan.builder()
                    .tasks(tasks)
                    .maxConcurrency((Integer) root.getOrDefault("maxConcurrency", 4))
                    .requireConfirmation(Boolean.TRUE.equals(root.get("requireConfirmation")))
                    .failFast(Boolean.TRUE.equals(root.get("failFast")))
                    .build();
        } catch (JsonProcessingException | ClassCastException exception) {
            throw new IllegalArgumentException("invalid agent plan", exception);
        }
    }

    /**
     * 从混合文本中截取 JSON 片段：首个 '{' 到最后一个 '}' 之间。
     */
    private String extractJson(String content) {
        int start = content.indexOf('{');
        int end = content.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalArgumentException("plan json not found");
        }
        return content.substring(start, end + 1);
    }
}
