package com.xlc.ai.domain.agent.service.install.matter.tools;

import com.xlc.ai.domain.agent.model.valobj.dynamic.DynamicTaskPlan;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 任务计划校验器 - 在派发执行前对 {@link DynamicTaskPlan} 做结构与安全校验。
 * 
 * 整体定位：主 Agent（LLM）会动态规划出一个任务计划（包含多个子任务及其依赖关系），
 * 交给编排器执行。但 LLM 输出是不可信的——可能为空、任务数量超限、ID 重复、
 * 引用不存在的依赖、甚至出现循环依赖。本类是 LLM 动态规划与确定性执行器之间的
 * 一道"防呆 + 防失控"防火墙：把脏数据拦截掉，防止执行器卡死或失控。
 * 
 * 校验规则：
 *   计划非空，任务数量不超过上限（防止 LLM 规划出失控规模的任务）
 *   taskId 不重复（重复 ID 会导致执行器任务分派错乱、依赖指向歧义）
 *   所有 dependsOn 引用的任务必须存在于计划中（悬空依赖会让编排器永远等不到任务完成）
 *   依赖关系不能成环（三色标记 DFS 检测），否则编排器会因"无可执行任务"而卡死
 *
 * 设计要点：
 *   快速失败：结构问题（空/超限/重复）O(n) 先查，成本最高的环检测放最后
 *   软硬结合：硬约束（空/重复/环）直接拒绝；软约束（重试次数）自动钳制修正，
 *       避免因小事让 LLM 重试整轮规划
 *   任一规则不满足即抛出 IllegalArgumentException，由派发工具统一兜底
 *       返回错误信息给 LLM，让它修正计划后重试
 * 
 */
@Service
public class PlanValidator {

    /**
     * 执行完整校验，校验失败抛出 {@link IllegalArgumentException}。
     * 
     * 如果使用的模型能力不强，可能会反馈错误（如输出空计划、幻觉出不存在的 taskId），
     * 导致本方法抛异常——这正好起到提前暴露模型质量问题、阻断脏计划进入执行层的作用。
     *
     * @param plan     待校验任务计划
     * @param maxTasks 任务数量上限（主 Agent 派发时通常为 10）
     */
    public void validate(DynamicTaskPlan plan, int maxTasks) {
        // 规则 1：计划非空 —— LLM 可能返回空计划或解析失败得到 null
        if (plan == null || plan.getTasks() == null || plan.getTasks().isEmpty()) {
            throw new IllegalArgumentException("task plan is empty");
        }
        // 规则 1b：数量上限 —— 防止 LLM 规划出 50 个任务这种失控规模，拖垮执行资源
        if (plan.getTasks().size() > maxTasks) {
            throw new IllegalArgumentException("too many tasks: " + plan.getTasks().size());
        }

        // 规则 2：taskId 唯一 —— 利用 HashSet.add() 的返回值判重：
        // add() 返回 false 说明该 ID 已存在，即发现重复任务。
        Set<String> taskIds = new HashSet<>();
        for (var task : plan.getTasks()) {
            if (!taskIds.add(task.getTaskId())) {
                throw new IllegalArgumentException("duplicate task id: " + task.getTaskId());
            }
            // 重试上限：最多重试 3 次，防止 LLM 规划出失控的重试策略（如重试 100 次）拖垮执行时长。一般也就3-5次。
            // 注意这里是"静默钳制"而非抛异常——小事修正，不必让 LLM 重试整轮规划。
            if (task.getMaxRetries() != null && task.getMaxRetries() > 3) {
                task.setMaxRetries(3);
            }
        }

        // 规则 3：依赖必须存在 —— 引用不存在的任务 = 悬空依赖，
        // 编排器会一直等待一个永远不会完成的任务。
        for (var task : plan.getTasks()) {
            for (String dependency : task.getDependsOn()) {
                if (!taskIds.contains(dependency)) {
                    throw new IllegalArgumentException("unknown dependency: " + dependency);
                }
            }
        }

        // 规则 4：依赖不能成环 —— A→B→C→A 的循环依赖会让所有任务互相等待，
        // 编排器永远选不出"无可执行任务"之外的出口，即永久卡死。
        if (hasCycle(plan)) {
            throw new IllegalArgumentException("task dependency cycle");
        }
    }

    /** 遍历所有任务做依赖环检测（任意一个成环即返回 true）。
     *  已检测过的节点会被 visited 剪枝跳过，整体复杂度 O(V+E)。 */
    private boolean hasCycle(DynamicTaskPlan plan) {
        Set<String> visiting = new HashSet<>();
        Set<String> visited = new HashSet<>();

        for (var task : plan.getTasks()) {
            if (hasCycle(task.getTaskId(), task.getDependsOn(), plan, visiting, visited)) {
                return true;
            }
        }

        return false;
    }

    /**
     * 三色标记 DFS 环检测。
     * 
     * 原理：visiting 表示当前递归栈中的节点（灰色，正在检测的路径），
     * visited 表示已完成检测的节点（黑色，后续可直接跳过）。
     * 判定关键：DFS 递归过程中又碰到了 visiting 中的节点，说明依赖链
     * 绕回到了"当前路径上"的自己——即找到回边（back edge），存在环。
     * 
     * 为什么需要两个集合？只记 visited 无法区分两种情况：
     * 
     *   "节点在当前路径上"（成环，需报错）
     *   "节点已检测过且无环"（安全，剪枝跳过）
     * 
     * 这就是经典的三色标记法（白/灰/黑），JVM GC 可达性分析、死锁检测用的同款算法。
     */
    private boolean hasCycle(String taskId, List<String> dependencies, DynamicTaskPlan plan,
                             Set<String> visiting, Set<String> visited) {

        if (visiting.contains(taskId)) return true;   // ⭐ 回边 = 当前递归栈中的节点再次出现 → 有环
        if (visited.contains(taskId)) return false;   // 已彻底检测过的节点无环，剪枝跳过
        visiting.add(taskId);

        // 沿 dependsOn 向依赖方向深度优先：找到被依赖的任务，递归检测它的依赖链
        for (String dependency : dependencies) {
            var parent = plan.getTasks().stream()
                    .filter(item -> item.getTaskId().equals(dependency))
                    .findFirst().orElse(null);
            if (parent != null && hasCycle(dependency, parent.getDependsOn(), plan, visiting, visited)) {
                return true;
            }
        }

        // 当前节点的整条依赖链都检测完毕且移出递归栈，标记为已检测
        visiting.remove(taskId);
        visited.add(taskId);

        return false;
    }

}
