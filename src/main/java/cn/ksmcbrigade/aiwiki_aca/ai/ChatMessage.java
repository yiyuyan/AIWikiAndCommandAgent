package cn.ksmcbrigade.aiwiki_aca.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.List;

public class ChatMessage {
    public enum Role {
        SYSTEM, USER, ASSISTANT, TOOL
    }

    public final Role role;
    public final String content;
    public final List<ToolCall> toolCalls;
    public final String toolCallId;
    public final String name;

    public ChatMessage(Role role, String content) {
        this(role, content, null, null, null);
    }

    public ChatMessage(Role role, String content, List<ToolCall> toolCalls) {
        this(role, content, toolCalls, null, null);
    }

    public ChatMessage(Role role, String content, List<ToolCall> toolCalls, String toolCallId, String name) {
        this.role = role;
        this.content = content;
        this.toolCalls = toolCalls;
        this.toolCallId = toolCallId;
        this.name = name;
    }

    public JsonObject toJson() {
        JsonObject obj = new JsonObject();
        obj.addProperty("role", role.name().toLowerCase());
        if (content != null) {
            obj.addProperty("content", content);
        }
        if (toolCalls != null && !toolCalls.isEmpty()) {
            JsonArray arr = new JsonArray();
            for (ToolCall tc : toolCalls) {
                arr.add(tc.toJson());
            }
            obj.add("tool_calls", arr);
        }
        if (toolCallId != null) {
            obj.addProperty("tool_call_id", toolCallId);
        }
        if (name != null) {
            obj.addProperty("name", name);
        }
        return obj;
    }
}
