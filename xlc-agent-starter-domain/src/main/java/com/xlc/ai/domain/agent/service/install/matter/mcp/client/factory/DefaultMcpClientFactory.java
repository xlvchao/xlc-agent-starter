package com.xlc.ai.domain.agent.service.install.matter.mcp.client.factory;

import com.xlc.ai.domain.agent.model.valobj.AgentConfigure;
import com.xlc.ai.domain.agent.service.install.matter.mcp.client.ToolMcpCreateService;
import com.xlc.ai.domain.agent.service.install.matter.mcp.client.impl.LocalToolMcpCreateService;
import com.xlc.ai.domain.agent.service.install.matter.mcp.client.impl.SSEToolMcpCreateService;
import com.xlc.ai.domain.agent.service.install.matter.mcp.client.impl.StdioToolMcpCreateService;
import com.xlc.ai.types.enums.ResponseCode;
import com.xlc.ai.types.exception.AppException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

@Slf4j
@Service
public class DefaultMcpClientFactory {

    @Resource
    private LocalToolMcpCreateService localToolMcpCreateService;

    @Resource
    private SSEToolMcpCreateService sseToolMcpCreateService;

    @Resource
    private StdioToolMcpCreateService stdioToolMcpCreateService;

    public ToolMcpCreateService getTooMcpCreateService(AgentConfigure.Module.ChatModel.ToolMcp toolMcp) {
        if (null != toolMcp.getLocal()) return localToolMcpCreateService;
        if (null != toolMcp.getSse()) return sseToolMcpCreateService;
        if (null != toolMcp.getStdio()) return stdioToolMcpCreateService;
        throw new AppException(ResponseCode.NOT_FOUND_METHOD.getCode(), ResponseCode.NOT_FOUND_METHOD.getInfo());
    }

}
