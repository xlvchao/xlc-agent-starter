package com.xlc.ai.config;

import com.alibaba.fastjson2.JSON;
import com.xlc.ai.domain.agent.model.valobj.AgentConfigure;
import com.xlc.ai.domain.agent.service.IInstallService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Configuration;

import javax.annotation.Resource;
import java.util.ArrayList;

@Slf4j
@Configuration
@EnableConfigurationProperties({AgentConfigure.class})
public class AgentInstaller implements ApplicationListener<ApplicationReadyEvent> {

    @Resource
    private AgentConfigure agentConfigure;

    @Resource
    private IInstallService installService;


    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        try {
            log.info("Ai Agent 智能体装配 {}", JSON.toJSONString(agentConfigure));

            installService.installAgent(agentConfigure);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

}
