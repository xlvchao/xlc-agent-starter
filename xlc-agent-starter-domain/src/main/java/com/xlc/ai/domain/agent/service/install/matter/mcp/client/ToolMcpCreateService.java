package com.xlc.ai.domain.agent.service.install.matter.mcp.client;

import com.xlc.ai.domain.agent.model.valobj.AgentConfigure;
import org.springframework.ai.tool.ToolCallback;

/**
 * 工具 MCP 构建服务
 *
 * @author xlvchao
 */
public interface ToolMcpCreateService {

    ToolCallback[] buildToolCallback(AgentConfigure.Module.ChatModel.ToolMcp toolMcp) throws Exception;

}
