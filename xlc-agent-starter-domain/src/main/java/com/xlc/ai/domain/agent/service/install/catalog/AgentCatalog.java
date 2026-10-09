package com.xlc.ai.domain.agent.service.install.catalog;

import com.google.adk.agents.BaseAgent;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 子 Agent 注册表 - Agent 装配完成后集中存放所有子 Agent 的目录。
 * 
 * RunnerNode 装配结束时调用 {@link #register} 把 agentGroup 登记进来，
 * 供派发服务（SubAgentDispatchService）在执行任务时查找对应的子 Agent：
 * 优先按"父 Agent ID + 子 Agent 名称"精确匹配，找不到时再按名称全局兜底查找。
 */
@Component
public class AgentCatalog {

    /** 以父 Agent ID 为键的子 Agent 分组：agentId -> (subAgentName -> subAgent) */
    private final Map<String, Map<String, BaseAgent>> agentsByAgentId = new ConcurrentHashMap<>();

    /** 冗余的按名称查找索引（与 agentsByAgentId 同步写入），供 findByName 全局兜底 */
    private final Map<String, Map<String, BaseAgent>> agentsByAgentName = new ConcurrentHashMap<>();

    /**
     * 登记一个父 Agent 下的全部子 Agent（装配完成后由 RunnerNode 调用）。
     *
     * @param agentId    父 Agent ID
     * @param agentGroup 父 Agent 的子 Agent 分组（name -> agent）
     */
    public void register(String agentId, Map<String, BaseAgent> agentGroup) {
        agentsByAgentId.put(agentId, new java.util.concurrent.ConcurrentHashMap<>(agentGroup));
        agentsByAgentName.put(agentId, agentsByAgentId.get(agentId));
    }

    /**
     * 在指定父 Agent 的分组内查找子 Agent（精确匹配）。
     *
     * @param agentId   父 Agent ID
     * @param agentName 子 Agent 名称
     */
    public Optional<BaseAgent> find(String agentId, String agentName) {
        return Optional.ofNullable(agentsByAgentId.get(agentId))
                .map(group -> group.get(agentName));
    }

    /**
     * 跨所有父 Agent 分组按名称全局查找子 Agent（兜底，找到第一个即返回）。
     */
    public Optional<BaseAgent> findByName(String agentName) {
        return agentsByAgentId.values().stream()
                .map(group -> group.get(agentName))
                .filter(java.util.Objects::nonNull)
                .findFirst();
    }

    /**
     * 列出指定父 Agent 下的全部子 Agent，未登记时返回空 Map。
     */
    public Map<String, BaseAgent> list(String agentId) {
        return agentsByAgentId.getOrDefault(agentId, new ConcurrentHashMap<>());
    }

}
