package com.dataforge.etl.ai.entity;

import com.dataforge.etl.ai.nlp.IntentType;
import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * AI 助手聊天消息:按 sessionId 聚合多轮对话。
 */
@Entity
@Table(name = "ai_chat_message", indexes = {
        @Index(name = "idx_ai_msg_session", columnList = "sessionId")
})
public class AiChatMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 会话 ID(无则服务端生成 UUID) */
    @Column(nullable = false)
    private String sessionId;

    /** 角色:user / assistant */
    @Column(nullable = false)
    private String role;

    @Lob
    @Column(nullable = false)
    private String content;

    /** 该条消息识别出的意图(assistant 消息记录其应答意图) */
    @Enumerated(EnumType.STRING)
    private IntentType intent;

    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        if (this.createdAt == null) {
            this.createdAt = LocalDateTime.now();
        }
    }

    public AiChatMessage() {
    }

    public AiChatMessage(String sessionId, String role, String content, IntentType intent) {
        this.sessionId = sessionId;
        this.role = role;
        this.content = content;
        this.intent = intent;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public IntentType getIntent() {
        return intent;
    }

    public void setIntent(IntentType intent) {
        this.intent = intent;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
