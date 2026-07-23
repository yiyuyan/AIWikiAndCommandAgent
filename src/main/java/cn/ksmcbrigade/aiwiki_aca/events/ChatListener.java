package cn.ksmcbrigade.aiwiki_aca.events;

import cn.ksmcbrigade.aiwiki_aca.Config;
import cn.ksmcbrigade.aiwiki_aca.McChatbot;
import cn.ksmcbrigade.aiwiki_aca.ai.AiService;
import cn.ksmcbrigade.aiwiki_aca.ai.ChatSession;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class ChatListener {
    private static final Map<UUID, ChatSession> sessions = new ConcurrentHashMap<>();

    public static ChatSession getOrCreateSession(UUID playerId, String playerName) {
        return sessions.computeIfAbsent(playerId, k -> new ChatSession(playerId, playerName));
    }

    public static ChatSession getSession(UUID playerId) {
        return sessions.get(playerId);
    }

    public static void removeSession(UUID playerId) {
        ChatSession session = sessions.remove(playerId);
        if (session != null) {
            session.cancelQuestion();
        }
    }

    public static void cleanupStaleSessions(long timeoutMs) {
        long now = System.currentTimeMillis();
        sessions.entrySet().removeIf(entry -> {
            ChatSession session = entry.getValue();
            if (now - session.getLastActivity() > timeoutMs) {
                session.cancelQuestion();
                return true;
            }
            return false;
        });
    }

    public static void handleChat(ServerPlayer player, String message) {
        var playerId = player.getUUID();
        ChatSession session = getOrCreateSession(playerId, player.getName().getString());
        session.touch();

        if (session.hasPendingQuestion()) {
            session.answerQuestion(message);
            return;
        }

        String prefix = Config.AI_PREFIX.get();

        McChatbot.AI_EXECUTOR.submit(() -> {
            try {
                String reply = AiService.getInstance().chat(session, message, player);
                player.sendSystemMessage(Component.literal(prefix + reply));
            } catch (Exception e) {
                player.sendSystemMessage(Component.literal(prefix + "§cAn error occurred."));
                McChatbot.LOGGER.error("AI chat error", e);
            }
        });
    }


}
