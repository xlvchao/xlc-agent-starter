package com.xlc.ai.domain.agent.service.install.matter.session.model;

import com.google.adk.events.Event;
import com.google.adk.sessions.SessionKey;
import com.google.adk.sessions.State;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SessionSnapshot {

    private SessionKey sessionKey;
    private State state;
    private List<Event> rawEvents;
    private Instant lastUpdateTime;

}