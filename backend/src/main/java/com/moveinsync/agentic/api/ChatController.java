package com.moveinsync.agentic.api;

import com.moveinsync.agentic.chat.ChatService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Phase 7's conversational drill-down surface. */
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final ChatService chatService;

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    public record ChatRequest(String message) {}

    @PostMapping
    public Map<String, Object> chat(@RequestBody ChatRequest request) {
        return chatService.answer(request.message());
    }
}
