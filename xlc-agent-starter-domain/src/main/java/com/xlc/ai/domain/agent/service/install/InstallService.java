package com.xlc.ai.domain.agent.service.install;

import com.xlc.ai.domain.agent.model.entity.InstallCommandEntity;
import com.xlc.ai.domain.agent.model.valobj.AgentConfigure;
import com.xlc.ai.domain.agent.model.valobj.AiAgentRegisterVO;
import com.xlc.ai.domain.agent.service.IInstallService;
import com.xlc.ai.domain.agent.service.install.factory.DefaultInstallFactory;
import com.xlc.ai.core.framework.StrategyHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

@Slf4j
@Service
public class InstallService implements IInstallService {

    @Resource
    private DefaultInstallFactory defaultInstallFactory;

    @Override
    public void installAgent(AgentConfigure agentConfigure) throws Exception {
        StrategyHandler<InstallCommandEntity, DefaultInstallFactory.DynamicContext, AiAgentRegisterVO> handler = defaultInstallFactory.installStrategyHandler();
        handler.apply(
                InstallCommandEntity.builder()
                        .agentConfigure(agentConfigure)
                        .build(),
                new DefaultInstallFactory.DynamicContext());
    }

}
