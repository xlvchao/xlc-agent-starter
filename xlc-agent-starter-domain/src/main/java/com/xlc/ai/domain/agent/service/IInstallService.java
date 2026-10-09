package com.xlc.ai.domain.agent.service;

import com.xlc.ai.domain.agent.model.valobj.AgentConfigure;

import java.util.List;

/**
 * 装配接口
 *
 * @author xlvchao
 */
public interface IInstallService {

    void installAgent(AgentConfigure agentConfigure) throws Exception;

}
