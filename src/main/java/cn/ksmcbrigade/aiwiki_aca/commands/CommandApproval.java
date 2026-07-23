package cn.ksmcbrigade.aiwiki_aca.commands;

import cn.ksmcbrigade.aiwiki_aca.Config;
import cn.ksmcbrigade.aiwiki_aca.McChatbot;
import net.minecraft.server.permissions.PermissionSet;

import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

public class CommandApproval {
    private static CommandApproval INSTANCE;
    private final Map<UUID, Queue<PendingCommand>> pendingApprovals = new ConcurrentHashMap<>();
    private final Map<String, PendingCommand> tokenMap = new ConcurrentHashMap<>();

    public static synchronized CommandApproval getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new CommandApproval();
        }
        return INSTANCE;
    }

    public String requestApproval(String command, UUID playerId, String playerName) {
        if (command.startsWith("/")) {
            command = command.substring(1);
        }

        for (String blacklisted : Config.BLACKLIST_COMMANDS.get()) {
            String bl = blacklisted.startsWith("/") ? blacklisted.substring(1) : blacklisted;
            if (command.startsWith(bl)) {
                return "Command rejected: " + command + " is blacklisted.";
            }
        }

        boolean isDangerous = false;
        for (String dangerous : Config.DANGEROUS_COMMANDS.get()) {
            String d = dangerous.startsWith("/") ? dangerous.substring(1) : dangerous;
            if (command.startsWith(d)) {
                isDangerous = true;
                break;
            }
        }

        boolean needsApproval = Config.APPROVAL_REQUIRED.get() || isDangerous;

        if (!needsApproval) {
            return executeNow(command, playerId);
        }

        String token = UUID.randomUUID().toString().substring(0, 8);
        PendingCommand pending = new PendingCommand(command, playerId, playerName, token);
        addPending(pending);

        return "APPROVAL_REQUIRED:" + token;
    }

    public void addPending(PendingCommand pending) {
        pendingApprovals.computeIfAbsent(pending.playerId, k -> new ConcurrentLinkedQueue<>()).add(pending);
        tokenMap.put(pending.token, pending);
    }

    public String approve(String token, UUID playerId) {
        PendingCommand pending = tokenMap.get(token);
        if (pending == null) return "Invalid or expired token.";
        if (!pending.playerId.equals(playerId)) return "This token belongs to another player.";

        tokenMap.remove(token);
        var queue = pendingApprovals.get(playerId);
        if (queue != null) {
            queue.remove(pending);
        }

        return executeNow(pending.command, playerId);
    }

    public String approveAll(UUID playerId) {
        var queue = pendingApprovals.get(playerId);
        if (queue == null || queue.isEmpty()) return "No pending commands to approve.";
        int count = queue.size();
        queue.clear();
        tokenMap.entrySet().removeIf(e -> e.getValue().playerId.equals(playerId));
        return "Approved " + count + " pending commands.";
    }

    public String denyAll(UUID playerId) {
        var queue = pendingApprovals.get(playerId);
        if (queue == null || queue.isEmpty()) return "No pending commands to deny.";
        int count = queue.size();
        queue.clear();
        tokenMap.entrySet().removeIf(e -> e.getValue().playerId.equals(playerId));
        return "Denied " + count + " pending commands.";
    }

    public String deny(String token, UUID playerId) {
        PendingCommand pending = tokenMap.get(token);
        if (pending == null) return "Invalid or expired token.";
        if (!pending.playerId.equals(playerId)) return "This token belongs to another player.";

        tokenMap.remove(token);
        var queue = pendingApprovals.get(playerId);
        if (queue != null) {
            queue.remove(pending);
        }
        return "Command denied by player.";
    }

    public String executeNow(String command) {
        return executeNow(command, null);
    }

    public String executeNow(String command, UUID playerId) {
        try {
            var server = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
            if (server == null) return "Error: Server not available.";

            var source = server.createCommandSourceStack().withPermission(PermissionSet.ALL_PERMISSIONS);
            if (playerId != null) {
                var player = server.getPlayerList().getPlayer(playerId);
                if (player != null) {
                    source = player.createCommandSourceStack().withPermission(PermissionSet.ALL_PERMISSIONS);
                }
            }
            server.getCommands().performPrefixedCommand(source, command);
            McChatbot.LOGGER.info("[AI executed] /{}", command);
            return "Command '/" + command + "' executed. If no visible result, check syntax and retry.";
        } catch (Exception e) {
            McChatbot.LOGGER.error("Failed to execute command", e);
            return "Error executing '/" + command + "': " + e.getMessage() + ". Please check syntax and retry.";
        }
    }

    public java.util.List<PendingCommand> getPending(UUID playerId) {
        var queue = pendingApprovals.get(playerId);
        if (queue == null) return java.util.Collections.emptyList();
        return java.util.List.copyOf(queue);
    }

    public static class PendingCommand {
        public final String command;
        public final UUID playerId;
        public final String playerName;
        public final String token;

        public PendingCommand(String command, UUID playerId, String playerName, String token) {
            this.command = command;
            this.playerId = playerId;
            this.playerName = playerName;
            this.token = token;
        }
    }
}
