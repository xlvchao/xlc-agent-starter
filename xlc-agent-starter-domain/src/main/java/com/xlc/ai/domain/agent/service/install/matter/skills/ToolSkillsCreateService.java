package com.xlc.ai.domain.agent.service.install.matter.skills;

import com.xlc.ai.domain.agent.model.valobj.AgentConfigure;
import org.springframework.ai.tool.ToolCallback;

/**
 * 工具 skills 构建服务
 *
 * @author xlvchao
 */
public interface ToolSkillsCreateService {

    ToolCallback[] buildToolCallback(AgentConfigure.Module.ChatModel.ToolSkills toolSkills) throws Exception;

}
