package cn.ksmcbrigade.aiwiki_aca.commands;

import cn.ksmcbrigade.aiwiki_aca.ai.ModelManager;
import cn.ksmcbrigade.aiwiki_aca.events.ChatListener;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.permissions.Permission;
import net.minecraft.server.permissions.Permissions;

public class ModCommands {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("ai")
                .then(Commands.literal("approve")
                        .then(Commands.argument("token", StringArgumentType.word())
                                .executes(ctx -> {
                                    var player = ctx.getSource().getPlayerOrException();
                                    String token = StringArgumentType.getString(ctx, "token");
                                    String result = CommandApproval.getInstance().approve(token, player.getUUID());
                                    player.sendSystemMessage(Component.literal(result));
                                    var session = ChatListener.getSession(player.getUUID());
                                    if (session != null) {
                                        session.completeCommandResult(result);
                                    }
                                    return 1;
                                })))
                .then(Commands.literal("deny")
                        .then(Commands.argument("token", StringArgumentType.word())
                                .executes(ctx -> {
                                    var player = ctx.getSource().getPlayerOrException();
                                    String token = StringArgumentType.getString(ctx, "token");
                                    String result = CommandApproval.getInstance().deny(token, player.getUUID());
                                    player.sendSystemMessage(Component.literal(result));
                                    var session = ChatListener.getSession(player.getUUID());
                                    if (session != null) {
                                        session.completeCommandResult(result);
                                    }
                                    return 1;
                                })))
                .then(Commands.literal("approveall")
                        .executes(ctx -> {
                            var player = ctx.getSource().getPlayerOrException();
                            String result = CommandApproval.getInstance().approveAll(player.getUUID());
                            player.sendSystemMessage(Component.literal(result));
                            var session = ChatListener.getSession(player.getUUID());
                            if (session != null) {
                                session.completeCommandResult("All commands approved and executed.");
                            }
                            return 1;
                        }))
                .then(Commands.literal("denyall")
                        .executes(ctx -> {
                            var player = ctx.getSource().getPlayerOrException();
                            String result = CommandApproval.getInstance().denyAll(player.getUUID());
                            player.sendSystemMessage(Component.literal(result));
                            var session = ChatListener.getSession(player.getUUID());
                            if (session != null) {
                                session.completeCommandResult("All commands denied.");
                            }
                            return 1;
                        }))
                .then(Commands.literal("reload")
                        .requires(s -> s.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))
                        .executes(ctx -> {
                            ModelManager.getInstance().refreshModels();
                            ctx.getSource().sendSuccess(() -> Component.literal("Model list refreshed."), true);
                            return 1;
                        }))
                .then(Commands.literal("clear")
                        .requires(s -> s.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))
                        .executes(ctx -> {
                            var player = ctx.getSource().getPlayer();
                            if (player != null) {
                                ChatListener.removeSession(player.getUUID());
                            }
                            ctx.getSource().sendSuccess(() -> Component.literal("Chat context cleared."), true);
                            return 1;
                        }))
                .then(Commands.literal("debug")
                        .requires(s -> s.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))
                        .executes(ctx -> {
                            boolean current = cn.ksmcbrigade.aiwiki_aca.Config.DEBUG_MODE.get();
                            cn.ksmcbrigade.aiwiki_aca.Config.DEBUG_MODE.set(!current);
                            ctx.getSource().sendSuccess(() -> Component.literal("Debug mode set to " + !current), true);
                            return 1;
                        }))
                .then(Commands.literal("clearall")
                        .requires(s -> s.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))
                        .then(Commands.argument("player", EntityArgument.player())
                                .executes(ctx -> {
                                    var target = EntityArgument.getPlayer(ctx, "player");
                                    ChatListener.removeSession(target.getUUID());
                                    ctx.getSource().sendSuccess(() -> Component.literal("Cleared context for " + target.getName().getString()), true);
                                    return 1;
                                })))
                .then(Commands.literal("models")
                        .requires(s -> s.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))
                        .executes(ctx -> {
                            var models = ModelManager.getInstance().getModels();
                            ctx.getSource().sendSuccess(() -> Component.literal("Available models (" + models.size() + "):"), false);
                            for (String m : models) {
                                ctx.getSource().sendSuccess(() -> Component.literal("  - " + m), false);
                            }
                            return 1;
                        }))
                .then(Commands.argument("message", StringArgumentType.greedyString())
                        .executes(ctx -> {
                            var player = ctx.getSource().getPlayerOrException();
                            String message = StringArgumentType.getString(ctx, "message");
                            ChatListener.handleChat(player, message);
                            return 1;
                        })));
    }
}
