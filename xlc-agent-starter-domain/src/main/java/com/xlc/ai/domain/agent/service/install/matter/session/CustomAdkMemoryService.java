package com.xlc.ai.domain.agent.service.install.matter.session;

import com.google.adk.memory.BaseMemoryService;
import com.google.adk.memory.SearchMemoryResponse;
import com.google.common.collect.ImmutableList;
import com.google.adk.sessions.Session;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Single;
import org.springframework.stereotype.Component;

@Component
public class CustomAdkMemoryService implements BaseMemoryService {

    @Override
    public Completable addSessionToMemory(Session session) {
        return Completable.complete();
    }

    @Override
    public Single<SearchMemoryResponse> searchMemory(String appName, String userId, String query) {
        return Single.just(SearchMemoryResponse.builder()
                .memories(ImmutableList.of())
                .build());
    }
}