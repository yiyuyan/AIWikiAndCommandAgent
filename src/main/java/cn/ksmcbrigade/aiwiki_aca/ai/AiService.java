package cn.ksmcbrigade.aiwiki_aca.ai;

import cn.ksmcbrigade.aiwiki_aca.mixin.ServerPlayerAccessor;
import com.google.gson.*;
import cn.ksmcbrigade.aiwiki_aca.Config;
import cn.ksmcbrigade.aiwiki_aca.McChatbot;
import cn.ksmcbrigade.aiwiki_aca.commands.CommandApproval;
import cn.ksmcbrigade.aiwiki_aca.knowledge.KnowledgeManager;
import cn.ksmcbrigade.aiwiki_aca.util.TextUtil;

import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.server.level.ServerPlayer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.stream.Collectors;

public class AiService {
    private static final String BUILTIN_API_URL = "https://opencode.ai/zen/v1";
    private static final String BUILTIN_API_KEY = "public";
    private static AiService INSTANCE;
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    public static synchronized AiService getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new AiService();
        }
        return INSTANCE;
    }

    public String chat(ChatSession session, String userMessage, ServerPlayer player) {
        if (!session.tryLock()) {
            return isZh() ? "你正在被处理中，请稍候。" : "You are already being processed. Please wait.";
        }
        try {
            session.addMessage(new ChatMessage(ChatMessage.Role.USER, userMessage));

            int maxRounds = Config.TOOL_CALL_MAX_ROUNDS.get();
            String model = selectModel();
            boolean retried = false;

            for (int round = 0; round < maxRounds; round++) {
                String response = sendRequest(session, model);
                if (response == null) {
                    if (!retried) {
                        retried = true;
                        session.clearContext();
                        session.addMessage(new ChatMessage(ChatMessage.Role.USER, userMessage));
                        round--;
                        continue;
                    }
                    model = selectModel();
                    retried = false;
                    round--;
                    continue;
                }
                if ("RATE_LIMITED".equals(response)) {
                    model = selectModel();
                    retried = false;
                    continue;
                }

                var json = JsonParser.parseString(response).getAsJsonObject();
                var choice = json.getAsJsonArray("choices").get(0).getAsJsonObject();
                var message = choice.getAsJsonObject("message");

                String content = message.has("content") && !message.get("content").isJsonNull()
                        ? message.get("content").getAsString() : null;
                var toolCallsJson = message.getAsJsonArray("tool_calls");

                if (toolCallsJson != null && toolCallsJson.size() > 0) {
                    List<ToolCall> toolCalls = new ArrayList<>();
                    for (var tc : toolCallsJson) {
                        var tcObj = tc.getAsJsonObject();
                        String id = tcObj.get("id").getAsString();
                        String type = tcObj.get("type").getAsString();
                        var func = tcObj.getAsJsonObject("function");
                        String name = func.get("name").getAsString();
                        String args = func.get("arguments").getAsString();
                        toolCalls.add(new ToolCall(id, type, name, args));
                    }

                    session.addMessage(new ChatMessage(ChatMessage.Role.ASSISTANT, content, toolCalls));

                    for (ToolCall tc : toolCalls) {
                        String result = executeTool(session, tc, player);
                        session.addMessage(new ChatMessage(ChatMessage.Role.TOOL, result, null, tc.id, tc.functionName));
                    }

                    // After processing all tool calls, handle any pending command approvals
                    var pendingCommands = session.getAndClearPendingCommands();
                    if (!pendingCommands.isEmpty()) {
                        showApprovalButtonsToPlayer(player, pendingCommands);
                        String approvalResult;
                        try {
                            approvalResult = session.waitForCommandResult();
                        } catch (Exception e) {
                            McChatbot.LOGGER.warn("Command approval timed out or failed: {}", e.getMessage());
                            for (var pc : pendingCommands) {
                                session.addMessage(new ChatMessage(ChatMessage.Role.TOOL,
                                        (isZh() ? "指令审批超时。" : "Command approval timed out."),
                                        null, pc.token, "run_command"));
                            }
                            continue;
                        }
                        if (approvalResult != null && !approvalResult.contains("denied") && !approvalResult.contains("拒绝")) {
                            for (var pc : pendingCommands) {
                                String execResult = CommandApproval.getInstance().executeNow(pc.command, session.playerId);
                                session.addMessage(new ChatMessage(ChatMessage.Role.TOOL, execResult, null, pc.token, "run_command"));
                            }
                        } else {
                            for (var pc : pendingCommands) {
                                session.addMessage(new ChatMessage(ChatMessage.Role.TOOL,
                                        (isZh() ? "指令已被玩家拒绝。" : "Command denied by player."),
                                        null, pc.token, "run_command"));
                            }
                        }
                    }
                } else {
                    String reply = content != null ? content : "";
                    reply = TextUtil.stripEmoji(reply);
                    reply = TextUtil.markdownToMinecraft(reply);
                    session.addMessage(new ChatMessage(ChatMessage.Role.ASSISTANT, reply));
                    return reply;
                }
            }

            return isZh() ? "§cAI达到了最大工具调用轮次但未产生最终响应。" : "§cAI reached the maximum number of tool call rounds without producing a final response.";
        } finally {
            session.unlock();
        }
    }

    private String selectModel() {
        if (!Config.USE_BUILTIN_API.get()) {
            String custom = Config.CUSTOM_MODEL.get();
            if (custom != null && !custom.isBlank()) return custom;
        }
        ModelManager mm = ModelManager.getInstance();
        mm.clearExpiredRateLimits();
        for (String model : mm.getModels()) {
            if (!mm.isRateLimited(model)) {
                return model;
            }
        }
        return "gpt-3.5-turbo";
    }

    private record ApiEndpoint(String baseUrl, String apiKey) {}

    private List<ApiEndpoint> getApiEndpoints() {
        List<ApiEndpoint> endpoints = new ArrayList<>();
        if (Config.USE_BUILTIN_API.get()) {
            endpoints.add(new ApiEndpoint(BUILTIN_API_URL, BUILTIN_API_KEY));
        }
        String customUrl = Config.CUSTOM_API_BASE_URL.get();
        String customKey = Config.CUSTOM_API_KEY.get();
        if (customUrl != null && !customUrl.isBlank() && customKey != null && !customKey.isBlank()) {
            endpoints.add(new ApiEndpoint(customUrl, customKey));
        }
        if (endpoints.isEmpty()) {
            endpoints.add(new ApiEndpoint(BUILTIN_API_URL, BUILTIN_API_KEY));
        }
        return endpoints;
    }

    private String sendRequest(ChatSession session, String model) {
        List<ApiEndpoint> endpoints = getApiEndpoints();
        List<String> modelCandidates = new ArrayList<>();
        modelCandidates.add(model);
        String customModel = Config.CUSTOM_MODEL.get();
        if (customModel != null && !customModel.isBlank() && !modelCandidates.contains(customModel)) {
            modelCandidates.add(customModel);
        }

        endpointLoop:
        for (ApiEndpoint ep : endpoints) {
            for (String candidate : modelCandidates) {
                boolean isDeepSeek = candidate.toLowerCase().contains("deepseek");
                String[] efforts = isDeepSeek ? new String[]{"max", ""} : new String[]{"high", ""};

                for (String effort : efforts) {
                    JsonObject body = new JsonObject();
                    body.addProperty("model", candidate);
                    JsonArray messages = new JsonArray();
                    for (ChatMessage msg : session.getMessages()) {
                        messages.add(msg.toJson());
                    }
                    body.add("messages", messages);
                    body.add("tools", ToolDefinitions.getToolDefinitions());
                    body.addProperty("parallel_tool_calls", false);
                    if (!effort.isEmpty()) {
                        body.addProperty("reasoning_effort", effort);
                    }

                    try {
                        var request = HttpRequest.newBuilder()
                                .uri(URI.create(ep.baseUrl + "/chat/completions"))
                                .header("Content-Type", "application/json")
                                .header("Authorization", "Bearer " + ep.apiKey)
                                .timeout(Duration.ofSeconds(60))
                                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                                .build();

                        var response = client.send(request, HttpResponse.BodyHandlers.ofString());
                        int status = response.statusCode();

                        if (status == 429) {
                            McChatbot.LOGGER.warn("Rate limited on {}, switching to next endpoint...", ep.baseUrl);
                            continue endpointLoop;
                        }

                        if (status == 200) {
                            return response.body();
                        }

                        if (effort.equals(efforts[efforts.length - 1])) {
                            McChatbot.LOGGER.error("API error ({}): {}", status, response.body());
                        }
                    } catch (Exception e) {
                        if (effort.equals(efforts[efforts.length - 1])) {
                            McChatbot.LOGGER.error("API request failed", e);
                        }
                    }
                }
            }
        }

        ModelManager.getInstance().markRateLimited(model);
        return "RATE_LIMITED";
    }

    private boolean isZh() {
        return "zh_cn".equalsIgnoreCase(Config.LANGUAGE.get());
    }

    private void showApprovalButtonsToPlayer(ServerPlayer player, List<CommandApproval.PendingCommand> pendingCommands) {
        ((ServerPlayerAccessor) player).getServerA().execute(() -> {
            boolean zh = isZh();
            StringBuilder sb = new StringBuilder();
            sb.append("§e").append(zh ? "以下指令需要你的批准：" : "The following commands require your approval:");
            for (int i = 0; i < pendingCommands.size(); i++) {
                sb.append("\n§6").append(i + 1).append(". /").append(pendingCommands.get(i).command);
            }

            var approveAll = Component.literal("§2[" + (zh ? "全部批准" : "Approve All") + "]")
                    .withStyle(style -> style
                            .withClickEvent(new ClickEvent.RunCommand("/ai approveall"))
                            .withHoverEvent(new HoverEvent.ShowText(Component.literal(zh ? "批准所有待处理指令" : "Approve all pending commands"))));
            var denyAll = Component.literal("§4[" + (zh ? "全部拒绝" : "Deny All") + "]")
                    .withStyle(style -> style
                            .withClickEvent(new ClickEvent.RunCommand("/ai denyall"))
                            .withHoverEvent(new HoverEvent.ShowText(Component.literal(zh ? "拒绝所有待处理指令" : "Deny all pending commands"))));

            player.sendSystemMessage(Component.literal(sb.toString() + " ")
                    .append(approveAll).append(" ").append(denyAll));
        });
    }

    private String executeTool(ChatSession session, ToolCall tc, ServerPlayer player) {
        try {
            var args = JsonParser.parseString(tc.arguments).getAsJsonObject();
            switch (tc.functionName) {
                case "list_packs": {
                    return KnowledgeManager.getInstance().getIndex();
                }
                case "list_knowledge": {
                    String category = args.has("category") ? args.get("category").getAsString() : null;
                    String packId = args.has("pack_id") ? args.get("pack_id").getAsString() : null;
                    if (category != null && !category.isBlank()) {
                        List<String> validCategories = List.of("aprilfools", "block", "command", "edition", "item", "mechanism", "mob", "tech", "version", "world", "other", "general", "mod", "minecraft_wiki");
                        boolean valid = false;
                        for (String vc : validCategories) {
                            if (vc.equalsIgnoreCase(category)) { valid = true; break; }
                        }
                        if (!valid) {
                            return (isZh()
                                ? "错误: 未知分类 \"" + category + "\"。可用分类: " + String.join(", ", validCategories) + "。请重试。"
                                : "ERROR: Unknown category \"" + category + "\". Valid categories: " + String.join(", ", validCategories) + ". Please retry.");
                        }
                    }
                    var files = KnowledgeManager.getInstance().listFilesByPack(packId).stream()
                            .filter(p -> category == null || category.isBlank() || p.toLowerCase().startsWith(category.toLowerCase()))
                            .collect(Collectors.toList());
                    if (files.isEmpty()) {
                        String hint = (isZh()
                            ? "分类 \"" + category + "\" 中没有文件。建议: 1) 尝试其他分类 2) 使用 list_packs() 查看可用分类 3) 尝试 list_knowledge(category: \"other\")"
                            : "No files in category \"" + category + "\". Suggestions: 1) Try a different category 2) Use list_packs() to see available categories 3) Try list_knowledge(category: \"other\")");
                        return hint;
                    }
                    StringBuilder sb = new StringBuilder();
                    if (packId != null && !packId.isBlank()) {
                        sb.append(isZh() ? "包: " + packId + "\n" : "Pack: " + packId + "\n");
                    }
                    if (category != null && !category.isBlank()) {
                        sb.append(isZh() ? "分类: " + category + " (" + files.size() + " 个文件)\n" : "Category: " + category + " (" + files.size() + " files)\n");
                    }
                    for (String f : files) {
                        sb.append("  - ").append(f).append("\n");
                    }
                    return sb.toString().trim();
                }
                case "read_knowledge": {
                    String filename = args.get("filename").getAsString();
                    if (filename == null || filename.isBlank()) {
                        return isZh() ? "错误: filename 参数为空。请提供要读取的文件名。" : "ERROR: filename parameter is empty. Please provide a filename to read.";
                    }
                    player.sendSystemMessage(Component.literal(Config.AI_PREFIX.get() + "§7" + (isZh() ? "正在读取知识库: " : "Reading knowledge: ") + filename + "..."));
                    String content = KnowledgeManager.getInstance().readByFilename(filename);
                    if (content == null) {
                        var suggestions = KnowledgeManager.getInstance().listFilesByPrefix("").stream()
                                .filter(f -> f.toLowerCase().contains(filename.toLowerCase().substring(0, Math.min(3, filename.length()))))
                                .limit(5)
                                .collect(Collectors.toList());
                        StringBuilder hint = new StringBuilder();
                        hint.append(isZh() ? "错误: 文件未找到: \"" + filename + "\"" : "ERROR: File not found: \"" + filename + "\"");
                        if (!suggestions.isEmpty()) {
                            hint.append(isZh() ? "\n你是否要找:\n" : "\nDid you mean:\n");
                            for (String s : suggestions) {
                                hint.append("  - ").append(s).append("\n");
                            }
                        }
                        hint.append(isZh() ? "\n建议: 先使用 list_knowledge(category) 列出可用文件。" : "\nSuggestion: Use list_knowledge(category) to list available files first.");
                        return hint.toString().trim();
                    }
                    return content;
                }
                case "run_command": {
                    String command = args.get("command").getAsString();
                    String result = CommandApproval.getInstance().requestApproval(command, session.playerId, session.playerName);
                    if (result.startsWith("APPROVAL_REQUIRED:")) {
                        String token = result.substring("APPROVAL_REQUIRED:".length());
                        session.addPendingCommand(new CommandApproval.PendingCommand(command, session.playerId, session.playerName, token));
                        return (isZh()
                            ? "指令 \"" + command + "\" 已加入审批队列。等待本轮所有指令收集完毕后统一审批。"
                            : "Command \"" + command + "\" queued for approval. All commands in this round will be approved together.");
                    }
                    if (result.startsWith("Error") || result.startsWith("错误")) {
                        return (isZh()
                            ? "指令执行失败: " + result + "。请检查指令语法后重试。"
                            : "Command execution failed: " + result + " . Please check syntax and retry.");
                    }
                    return result;
                }
                case "ask_player": {
                    String question = args.get("question").getAsString();
                    try {
                        String answer = session.askPlayer(question, player).get();
                        return "Player answered: " + answer;
                    } catch (Exception e) {
                        return isZh() ? "玩家未回应。" : "Player did not respond.";
                    }
                }
                default:
                    return isZh() ? "未知工具: " + tc.functionName : "Unknown tool: " + tc.functionName;
            }
        } catch (JsonSyntaxException e) {
            McChatbot.LOGGER.error("Tool JSON parse error: " + tc.functionName, e);
            return (isZh()
                ? "错误: 工具参数格式不正确。请检查参数是否为有效JSON。详情: " + e.getMessage()
                : "ERROR: Invalid tool arguments format. Check if arguments are valid JSON. Details: " + e.getMessage());
        } catch (NoSuchElementException e) {
            McChatbot.LOGGER.error("Tool missing required argument: " + tc.functionName, e);
            return (isZh()
                ? "错误: 缺少必要参数。请检查工具调用是否包含所有必需参数。工具: " + tc.functionName
                : "ERROR: Missing required argument. Check if all required parameters are provided. Tool: " + tc.functionName);
        } catch (Exception e) {
            McChatbot.LOGGER.error("Failed to execute tool: " + tc.functionName, e);
            return (isZh()
                ? "工具执行错误 [" + tc.functionName + "]: " + e.getMessage() + "。请检查参数后重试。"
                : "Tool execution error [" + tc.functionName + "]: " + e.getMessage() + ". Please check parameters and retry.");
        }
    }
}
