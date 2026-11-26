package com.tongji.knowpost.api;

import com.tongji.auth.tokenservice.JwtService;
import com.tongji.limit.AiRateLimiter;
import com.tongji.llm.chat.RagQueryService;
import com.tongji.llm.debugservice.RagDebugService;
import com.tongji.llm.searchService.RagIndexService;
import com.tongji.payment.VibePayService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class KnowPostRagVipTest {
    private static final long USER_ID = 42;
    private static final String BASE = "/api/v1/knowposts";
    private static final String QUESTION = "How does the cache work?";
    private final VibePayService payments = mock(VibePayService.class);
    private final JwtService jwtService = mock(JwtService.class);
    private final RagQueryService rag = mock(RagQueryService.class);
    private final AiRateLimiter limiter = mock(AiRateLimiter.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new KnowPostRagController(
                        mock(RagIndexService.class), rag, mock(RagDebugService.class), jwtService, limiter, payments))
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .build();
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void refusesNonVipOnEveryPublicAnswerEndpointBeforeStartingRag() throws Exception {
        authenticate("access");
        mvc.perform(get(BASE + "/7/qa/stream").param("question", QUESTION).accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isForbidden());
        mvc.perform(get(BASE + "/qa/stream").param("question", QUESTION).accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isForbidden());
        chat().andExpect(status().isForbidden());
        verifyNoInteractions(rag, limiter);
    }

    @Test
    void refusesAnonymousAndRefreshTokensBeforeCheckingEntitlement() throws Exception {
        mvc.perform(get(BASE + "/7/qa/stream").param("question", QUESTION).accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isUnauthorized());
        authenticate("refresh");
        chat().andExpect(status().isUnauthorized());
        verifyNoInteractions(payments, rag, limiter);
    }

    @Test
    void vipCanStillReceiveArticleAndChatStreams() throws Exception {
        authenticate("access");
        when(payments.hasVipAccess(USER_ID)).thenReturn(true);
        when(rag.streamPostAnswerFlux(7L, QUESTION, 5, null)).thenReturn(Flux.just("article answer"));
        when(rag.streamChatAnswerFlux(USER_ID, null, "global", null, QUESTION, 5))
                .thenReturn(Flux.just(ServerSentEvent.builder("chat answer").build()));
        var article = mvc.perform(get(BASE + "/7/qa/stream").param("question", QUESTION)
                .accept(MediaType.TEXT_EVENT_STREAM)).andExpect(request().asyncStarted()).andReturn();
        mvc.perform(asyncDispatch(article)).andExpect(status().isOk())
                .andExpect(content().string(containsString("data:article answer")));
        var chat = chat().andExpect(request().asyncStarted()).andReturn();
        mvc.perform(asyncDispatch(chat)).andExpect(status().isOk())
                .andExpect(content().string(containsString("data:chat answer")));
    }

    @Test
    void gatewayFailureDoesNotLookLikeAnUnpaidMembership() throws Exception {
        authenticate("access");
        when(payments.hasVipAccess(USER_ID)).thenThrow(new ResponseStatusException(HttpStatus.BAD_GATEWAY));
        chat().andExpect(status().isBadGateway());
        verifyNoInteractions(rag, limiter);
    }

    private ResultActions chat() throws Exception {
        return mvc.perform(post(BASE + "/qa/chat/stream")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .content("""
                        {"scope":"global","question":"How does the cache work?"}
                        """));
    }

    private void authenticate(String tokenType) {
        Jwt jwt = Jwt.withTokenValue("test").header("alg", "RS256").subject("42")
                .claim("uid", USER_ID).claim("token_type", tokenType).build();
        when(jwtService.extractUserId(jwt)).thenReturn(USER_ID);
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }
}
