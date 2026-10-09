package com.xlc.ai.domain.agent.service.install.matter.plugin;

import com.google.adk.plugins.LoggingPlugin;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service("myLogPlugin")
public class MyLogPlugin extends LoggingPlugin {
}
