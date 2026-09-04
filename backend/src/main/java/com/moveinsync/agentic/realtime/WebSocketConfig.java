package com.moveinsync.agentic.realtime;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * Phase 6's live-delivery transport: a Spring STOMP-over-WebSocket broker so
 * a finding from Phase 5's agent cycle appears in the Angular alert feed the
 * instant it's decided - no polling. Endpoint is plain WebSocket (no SockJS
 * fallback layer): every browser this demo needs to run in supports native
 * WebSocket, and skipping SockJS keeps nginx's reverse-proxy config (see
 * frontend/nginx.conf's /ws location) a plain Upgrade-header passthrough
 * instead of SockJS's extra polling/session endpoints.
 *
 * Future-AWS mapping: the same STOMP destinations, fronted by API Gateway's
 * WebSocket API in production - no redesign needed (see the architecture
 * document's section 8).
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic");
        registry.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws").setAllowedOriginPatterns("*");
    }
}
