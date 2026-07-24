package cn.ksmcbrigade.aiwiki_aca.ai;

import cn.ksmcbrigade.aiwiki_aca.mixin.ServerPlayerAccessor;
import cn.ksmcbrigade.aiwiki_aca.util.agent.CompilerUtils;
import cn.ksmcbrigade.aiwiki_aca.util.agent.InstUtils;
import cn.ksmcbrigade.aiwiki_aca.util.agent.MixinHotSwap;
import com.google.gson.*;
import cn.ksmcbrigade.aiwiki_aca.Config;
import cn.ksmcbrigade.aiwiki_aca.McChatbot;
import cn.ksmcbrigade.aiwiki_aca.commands.CommandApproval;
import cn.ksmcbrigade.aiwiki_aca.knowledge.KnowledgeManager;
import cn.ksmcbrigade.aiwiki_aca.util.TextUtil;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.server.level.ServerPlayer;

import java.io.File;
import java.lang.instrument.ClassDefinition;
import java.lang.instrument.Instrumentation;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.Base64;
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
                case "get_class_source": {
                    String className = args.get("class_name").getAsString();
                    player.sendSystemMessage(Component.literal(Config.AI_PREFIX.get() + "§7" + (isZh() ? "正在反编译类: " : "Decompiling class: ") + className + "..."));
                    try {
                        String result = CompilerUtils.getSource(className);
                        player.sendSystemMessage(Component.literal(Config.AI_PREFIX.get() + "§a" + (isZh() ? "反编译成功: " : "Decompiled: ") + className + " §7(" + result.length() + (isZh() ? " 字符)" : " chars)")));
                        return result;
                    } catch (Exception e) {
                        McChatbot.LOGGER.error("Failed to decompile class: {}", className, e);
                        String errMsg = (isZh()
                                ? "错误: 无法反编译类 \"" + className + "\": " + e.getMessage()
                                : "ERROR: Cannot decompile class \"" + className + "\": " + e.getMessage());
                        player.sendSystemMessage(Component.literal(Config.AI_PREFIX.get() + "§c" + (isZh() ? "反编译失败: " : "Decompile failed: ") + className + " - " + e.getMessage()));
                        return errMsg;
                    }
                }
                case "redefine_class": {
                    String className = args.get("class_name").getAsString();
                    String newSource = args.get("new_source").getAsString();
                    player.sendSystemMessage(Component.literal(Config.AI_PREFIX.get() + "§7" + (isZh() ? "正在重定义类: " : "Redefining class: ") + className + "..."));
                    try {
                        Instrumentation inst = InstUtils.getInst();
                        if (inst == null) {
                            String errMsg = isZh() ? "错误: Instrumentation 不可用。" : "ERROR: Instrumentation is not available.";
                            player.sendSystemMessage(Component.literal(Config.AI_PREFIX.get() + "§c" + errMsg));
                            return errMsg;
                        }

                        Class<?> targetClass = Class.forName(className.replace("/", "."));
                        byte[] oldBytes = InstUtils.getClassBytes(inst, targetClass);

                        CompilerUtils.CompileInfo compileResult = CompilerUtils.compile(newSource);
                        if (!compileResult.success()) {
                            String errMsg = (isZh()
                                    ? "错误: 编译失败:\n" + compileResult.info()
                                    : "ERROR: Compilation failed:\n" + compileResult.info());
                            player.sendSystemMessage(Component.literal(Config.AI_PREFIX.get() + "§c" + (isZh() ? "编译失败: " : "Compilation failed: ") + className));
                            return errMsg;
                        }

                        byte[] newBytes = compileAndGetClassBytes(newSource, className);
                        if (newBytes == null) {
                            String errMsg = (isZh()
                                    ? "错误: 无法从编译结果中提取类字节码。请确保源码中包含完整的类定义。"
                                    : "ERROR: Cannot extract class bytecode from compilation result. Make sure source contains a complete class definition.");
                            player.sendSystemMessage(Component.literal(Config.AI_PREFIX.get() + "§c" + (isZh() ? "字节码提取失败: " : "Bytecode extraction failed: ") + className));
                            return errMsg;
                        }

                        String validation = validateSchemaCompatibility(oldBytes, newBytes);
                        if (validation != null) {
                            String errMsg = (isZh()
                                    ? "错误: 修改后的源码不符合 redefineClass 要求:\n" + validation
                                    + "\n\nredefineClass 限制：不能新增字段、不能新增方法、不能修改类层级结构。仅允许修改方法体、构造器体和静态初始化块。"
                                    : "ERROR: Modified source does not meet redefineClass requirements:\n" + validation
                                    + "\n\nredefineClass restrictions: cannot add fields, cannot add methods, cannot change class hierarchy. Only method bodies, constructors, and static initializers may be modified.");
                            player.sendSystemMessage(Component.literal(Config.AI_PREFIX.get() + "§c" + (isZh() ? "Schema 校验失败: " : "Schema validation failed: ") + className));
                            return errMsg;
                        }

                        ClassDefinition pending = MixinHotSwap.replaceMixedClasses(targetClass, newBytes, inst, null);
                        if (pending != null) {
                            inst.redefineClasses(pending);
                        }

                        McChatbot.LOGGER.info("Successfully redefined class: {}", className);
                        player.sendSystemMessage(Component.literal(Config.AI_PREFIX.get() + "§a" + (isZh() ? "重定义成功: " : "Redefined: ") + className));
                        return (isZh()
                                ? "类 \"" + className + "\" 已成功重新定义。"
                                : "Class \"" + className + "\" has been successfully redefined.");
                    } catch (Exception e) {
                        McChatbot.LOGGER.error("Failed to redefine class: {}", className, e);
                        player.sendSystemMessage(Component.literal(Config.AI_PREFIX.get() + "§c" + (isZh() ? "重定义失败: " : "Redefine failed: ") + className + " - " + e.getMessage()));
                        return (isZh()
                                ? "错误: 重新定义类 \"" + className + "\" 失败: " + e.getMessage()
                                : "ERROR: Failed to redefine class \"" + className + "\": " + e.getMessage());
                    }
                }
                case "replace_class": {
                    String className = args.get("class_name").getAsString();
                    JsonArray replacementsArray = args.getAsJsonArray("replacements");
                    int replaceCount = replacementsArray != null ? replacementsArray.size() : 0;
                    player.sendSystemMessage(Component.literal(Config.AI_PREFIX.get() + "§7" + (isZh() ? "正在替换并重定义类: " : "Replacing and redefining: ") + className + " (" + replaceCount + (isZh() ? "处替换)" : " replacements)") + "..."));
                    try {
                        Instrumentation inst = InstUtils.getInst();
                        if (inst == null) {
                            String errMsg = isZh() ? "错误: Instrumentation 不可用。" : "ERROR: Instrumentation is not available.";
                            player.sendSystemMessage(Component.literal(Config.AI_PREFIX.get() + "§c" + errMsg));
                            return errMsg;
                        }

                        Class<?> targetClass = Class.forName(className.replace("/", "."));
                        byte[] oldBytes = InstUtils.getClassBytes(inst, targetClass);
                        String source = CompilerUtils.getSource(targetClass);

                        List<String> notFound = new ArrayList<>();
                        List<String> applied = new ArrayList<>();

                        for (JsonElement elem : replacementsArray) {
                            JsonObject r = elem.getAsJsonObject();
                            String oldCode = r.get("old").getAsString();
                            String newCode = r.get("new").getAsString();

                            if (source.contains(oldCode)) {
                                source = source.replace(oldCode, newCode);
                                applied.add(shortDesc(oldCode));
                            } else {
                                String replacedSource = replaceByLineMatch(source, oldCode, newCode);
                                if (replacedSource != null) {
                                    source = replacedSource;
                                    applied.add(shortDesc(oldCode));
                                } else {
                                    notFound.add(shortDesc(oldCode));
                                }
                            }
                        }

                        if (!notFound.isEmpty()) {
                            String errMsg = (isZh()
                                    ? "错误: 以下 " + notFound.size() + " 处代码块在反编译输出中未找到:\n" + String.join("\n", notFound)
                                    + "\n\n提示: 可先用 get_class_source 查看实际反编译输出，确保 old 与之精确匹配。"
                                    : "ERROR: " + notFound.size() + " code block(s) not found in decompiled output:\n" + String.join("\n", notFound)
                                    + "\n\nHint: Use get_class_source first to see the actual decompiled output and ensure 'old' matches exactly.");
                            player.sendSystemMessage(Component.literal(Config.AI_PREFIX.get() + "§c" + (isZh() ? "替换失败: 未找到 " + notFound.size() + " 处代码块" : "Replace failed: " + notFound.size() + " code block(s) not found")));
                            return errMsg;
                        }

                        byte[] newBytes = compileAndGetClassBytes(source, className);
                        if (newBytes == null) {
                            player.sendSystemMessage(Component.literal(Config.AI_PREFIX.get() + "§c" + (isZh() ? "编译失败: " : "Compilation failed: ") + className));
                            return isZh()
                                    ? "错误: 替换后代码编译失败。请检查语法。"
                                    : "ERROR: Compilation failed after replacement. Check syntax.";
                        }

                        String validation = validateSchemaCompatibility(oldBytes, newBytes);
                        if (validation != null) {
                            player.sendSystemMessage(Component.literal(Config.AI_PREFIX.get() + "§c" + (isZh() ? "Schema 校验失败: " : "Schema validation failed: ") + className));
                            return (isZh()
                                    ? "错误: 替换后代码不符合 redefineClass 要求:\n" + validation
                                    : "ERROR: Modified code does not meet redefineClass requirements:\n" + validation);
                        }

                        ClassDefinition pending = MixinHotSwap.replaceMixedClasses(targetClass, newBytes, inst, null);
                        if (pending != null) {
                            inst.redefineClasses(pending);
                        }

                        McChatbot.LOGGER.info("[replace_class] SUCCESS class={} applied={}", className, applied.size());
                        player.sendSystemMessage(Component.literal(Config.AI_PREFIX.get() + "§a" + (isZh() ? "替换成功: " : "Replaced: ") + className + " §7(" + applied.size() + (isZh() ? " 处)" : " replacements)")));
                        return (isZh()
                                ? "类 \"" + className + "\" 已成功替换并重新定义。已应用 " + applied.size() + " 处替换。"
                                : "Class \"" + className + "\" successfully replaced and redefined. " + applied.size() + " replacement(s) applied.");
                    } catch (Exception e) {
                        McChatbot.LOGGER.error("[replace_class] FAILED class={}", className, e);
                        player.sendSystemMessage(Component.literal(Config.AI_PREFIX.get() + "§c" + (isZh() ? "替换失败: " : "Replace failed: ") + className + " - " + e.getMessage()));
                        return (isZh()
                                ? "错误: 替换并重定义类 \"" + className + "\" 失败: " + e.getMessage()
                                : "ERROR: Failed to replace and redefine class \"" + className + "\": " + e.getMessage());
                    }
                }
                case "redefine_class_no_verify": {
                    String className = args.get("class_name").getAsString();
                    String newSource = args.get("new_source").getAsString();
                    player.sendSystemMessage(Component.literal(Config.AI_PREFIX.get() + "§7" + (isZh() ? "正在无校验重定义类: " : "Redefining (no-verify): ") + className + "..."));
                    try {
                        Instrumentation inst = InstUtils.getInst();
                        if (inst == null) {
                            String errMsg = isZh() ? "错误: Instrumentation 不可用。" : "ERROR: Instrumentation is not available.";
                            player.sendSystemMessage(Component.literal(Config.AI_PREFIX.get() + "§c" + errMsg));
                            return errMsg;
                        }

                        Class<?> targetClass = Class.forName(className.replace("/", "."));
                        byte[] newBytes = compileAndGetClassBytes(newSource, className);
                        if (newBytes == null) {
                            String errMsg = (isZh()
                                    ? "错误: 编译失败，无法生成字节码。"
                                    : "ERROR: Compilation failed, cannot produce bytecode.");
                            player.sendSystemMessage(Component.literal(Config.AI_PREFIX.get() + "§c" + (isZh() ? "编译失败: " : "Compilation failed: ") + className));
                            return errMsg;
                        }

                        ClassDefinition pending = MixinHotSwap.replaceMixedClasses(targetClass, newBytes, inst, null);
                        if (pending != null) {
                            inst.redefineClasses(pending);
                        }

                        McChatbot.LOGGER.info("[redefine_class_no_verify] SUCCESS class={}", className);
                        player.sendSystemMessage(Component.literal(Config.AI_PREFIX.get() + "§a" + (isZh() ? "无校验重定义成功: " : "Redefined (no-verify): ") + className));
                        return (isZh()
                                ? "类 \"" + className + "\" 已成功重新定义（无校验模式）。"
                                : "Class \"" + className + "\" successfully redefined (no-verify mode).");
                    } catch (Exception e) {
                        McChatbot.LOGGER.error("[redefine_class_no_verify] FAILED class={}", className, e);
                        player.sendSystemMessage(Component.literal(Config.AI_PREFIX.get() + "§c" + (isZh() ? "无校验重定义失败: " : "Redefine (no-verify) failed: ") + className + " - " + e.getMessage()));
                        return (isZh()
                                ? "错误: 无校验重定义类 \"" + className + "\" 失败: " + e.getMessage()
                                : "ERROR: Failed to redefine class \"" + className + "\" (no-verify): " + e.getMessage());
                    }
                }
                case "replace_class_no_verify": {
                    String className = args.get("class_name").getAsString();
                    JsonArray replacementsArray = args.getAsJsonArray("replacements");
                    int replaceCount = replacementsArray != null ? replacementsArray.size() : 0;
                    player.sendSystemMessage(Component.literal(Config.AI_PREFIX.get() + "§7" + (isZh() ? "正在无校验替换并重定义: " : "Replacing (no-verify): ") + className + " (" + replaceCount + (isZh() ? "处替换)" : " replacements)") + "..."));
                    try {
                        Instrumentation inst = InstUtils.getInst();
                        if (inst == null) {
                            String errMsg = isZh() ? "错误: Instrumentation 不可用。" : "ERROR: Instrumentation is not available.";
                            player.sendSystemMessage(Component.literal(Config.AI_PREFIX.get() + "§c" + errMsg));
                            return errMsg;
                        }

                        Class<?> targetClass = Class.forName(className.replace("/", "."));
                        String source = CompilerUtils.getSource(targetClass);

                        List<String> notFound = new ArrayList<>();
                        List<String> applied = new ArrayList<>();

                        for (JsonElement elem : replacementsArray) {
                            JsonObject r = elem.getAsJsonObject();
                            String oldCode = r.get("old").getAsString();
                            String newCode = r.get("new").getAsString();

                            if (source.contains(oldCode)) {
                                source = source.replace(oldCode, newCode);
                                applied.add(shortDesc(oldCode));
                            } else {
                                String replacedSource = replaceByLineMatch(source, oldCode, newCode);
                                if (replacedSource != null) {
                                    source = replacedSource;
                                    applied.add(shortDesc(oldCode));
                                } else {
                                    notFound.add(shortDesc(oldCode));
                                }
                            }
                        }

                        if (!notFound.isEmpty()) {
                            String errMsg = (isZh()
                                    ? "错误: 以下 " + notFound.size() + " 处代码块未找到:\n" + String.join("\n", notFound)
                                    : "ERROR: " + notFound.size() + " code block(s) not found:\n" + String.join("\n", notFound));
                            player.sendSystemMessage(Component.literal(Config.AI_PREFIX.get() + "§c" + (isZh() ? "替换失败: 未找到 " + notFound.size() + " 处代码块" : "Replace failed: " + notFound.size() + " code block(s) not found")));
                            return errMsg;
                        }

                        byte[] newBytes = compileAndGetClassBytes(source, className);
                        if (newBytes == null) {
                            String errMsg = (isZh()
                                    ? "错误: 编译失败，无法生成字节码。"
                                    : "ERROR: Compilation failed, cannot produce bytecode.");
                            player.sendSystemMessage(Component.literal(Config.AI_PREFIX.get() + "§c" + (isZh() ? "编译失败: " : "Compilation failed: ") + className));
                            return errMsg;
                        }

                        ClassDefinition pending = MixinHotSwap.replaceMixedClasses(targetClass, newBytes, inst, null);
                        if (pending != null) {
                            inst.redefineClasses(pending);
                        }

                        McChatbot.LOGGER.info("[replace_class_no_verify] SUCCESS class={} applied={}", className, applied.size());
                        player.sendSystemMessage(Component.literal(Config.AI_PREFIX.get() + "§a" + (isZh() ? "无校验替换成功: " : "Replaced (no-verify): ") + className + " §7(" + applied.size() + (isZh() ? " 处)" : " replacements)")));
                        return (isZh()
                                ? "类 \"" + className + "\" 已成功替换并重新定义（无校验模式）。已应用 " + applied.size() + " 处替换。"
                                : "Class \"" + className + "\" successfully replaced and redefined (no-verify mode). " + applied.size() + " replacement(s) applied.");
                    } catch (Exception e) {
                        McChatbot.LOGGER.error("[replace_class_no_verify] FAILED class={}", className, e);
                        player.sendSystemMessage(Component.literal(Config.AI_PREFIX.get() + "§c" + (isZh() ? "无校验替换失败: " : "Replace (no-verify) failed: ") + className + " - " + e.getMessage()));
                        return (isZh()
                                ? "错误: 无校验替换并重定义类 \"" + className + "\" 失败: " + e.getMessage()
                                : "ERROR: Failed to replace and redefine class \"" + className + "\" (no-verify): " + e.getMessage());
                    }
                }
                case "get_source_bytes": {
                    String className = args.get("class_name").getAsString();
                    player.sendSystemMessage(Component.literal(Config.AI_PREFIX.get() + "§7" + (isZh() ? "正在获取字节码: " : "Getting bytecode: ") + className + "..."));
                    try {
                        Instrumentation inst = InstUtils.getInst();
                        if (inst == null) {
                            String errMsg = isZh() ? "错误: Instrumentation 不可用。" : "ERROR: Instrumentation is not available.";
                            player.sendSystemMessage(Component.literal(Config.AI_PREFIX.get() + "§c" + errMsg));
                            return errMsg;
                        }
                        Class<?> targetClass = Class.forName(className.replace("/", "."));
                        byte[] bytes = InstUtils.getClassBytes(inst, targetClass);
                        String b64 = Base64.getEncoder().encodeToString(bytes);
                        McChatbot.LOGGER.info("[get_source_bytes] SUCCESS class={} bytes={}", className, bytes.length);
                        player.sendSystemMessage(Component.literal(Config.AI_PREFIX.get() + "§a" + (isZh() ? "字节码获取成功: " : "Got bytecode: ") + className + " §7(" + bytes.length + " bytes, " + b64.length() + " B64 chars)"));
                        return (isZh()
                                ? "类 \"" + className + "\" 的字节码（Base64，" + bytes.length + " bytes）：\n" + b64
                                : "Bytecode of class \"" + className + "\" (Base64, " + bytes.length + " bytes):\n" + b64);
                    } catch (Exception e) {
                        McChatbot.LOGGER.error("[get_source_bytes] FAILED class={}", className, e);
                        player.sendSystemMessage(Component.literal(Config.AI_PREFIX.get() + "§c" + (isZh() ? "字节码获取失败: " : "Get bytecode failed: ") + className + " - " + e.getMessage()));
                        return (isZh()
                                ? "错误: 获取类 \"" + className + "\" 字节码失败: " + e.getMessage()
                                : "ERROR: Failed to get bytecode of class \"" + className + "\": " + e.getMessage());
                    }
                }
                case "redefine_class_by_bytes_no_verify": {
                    String className = args.get("class_name").getAsString();
                    String b64 = args.get("bytes").getAsString();
                    player.sendSystemMessage(Component.literal(Config.AI_PREFIX.get() + "§7" + (isZh() ? "正在字节码重定义（无校验）: " : "Redefining by bytecode (no-verify): ") + className + "..."));
                    try {
                        Instrumentation inst = InstUtils.getInst();
                        if (inst == null) {
                            String errMsg = isZh() ? "错误: Instrumentation 不可用。" : "ERROR: Instrumentation is not available.";
                            player.sendSystemMessage(Component.literal(Config.AI_PREFIX.get() + "§c" + errMsg));
                            return errMsg;
                        }
                        byte[] newBytes = Base64.getDecoder().decode(b64);
                        Class<?> targetClass = Class.forName(className.replace("/", "."));
                        ClassDefinition pending = MixinHotSwap.replaceMixedClasses(targetClass, newBytes, inst, null);
                        if (pending != null) {
                            inst.redefineClasses(pending);
                        }
                        McChatbot.LOGGER.info("[redefine_class_by_bytes_no_verify] SUCCESS class={} bytes={}", className, newBytes.length);
                        player.sendSystemMessage(Component.literal(Config.AI_PREFIX.get() + "§a" + (isZh() ? "字节码重定义成功: " : "Redefined by bytecode (no-verify): ") + className));
                        return (isZh()
                                ? "类 \"" + className + "\" 已通过字节码成功重定义（无校验模式）。"
                                : "Class \"" + className + "\" successfully redefined by bytecode (no-verify mode).");
                    } catch (IllegalArgumentException e) {
                        McChatbot.LOGGER.error("[redefine_class_by_bytes_no_verify] FAILED (bad Base64) class={}", className, e);
                        player.sendSystemMessage(Component.literal(Config.AI_PREFIX.get() + "§c" + (isZh() ? "Base64 解码失败: " : "Base64 decode failed: ") + e.getMessage()));
                        return (isZh()
                                ? "错误: Base64 解码失败，请检查 bytes 参数。" + e.getMessage()
                                : "ERROR: Base64 decode failed. Check the bytes parameter. " + e.getMessage());
                    } catch (Exception e) {
                        McChatbot.LOGGER.error("[redefine_class_by_bytes_no_verify] FAILED class={}", className, e);
                        player.sendSystemMessage(Component.literal(Config.AI_PREFIX.get() + "§c" + (isZh() ? "字节码重定义失败: " : "Redefine by bytecode failed: ") + className + " - " + e.getMessage()));
                        return (isZh()
                                ? "错误: 通过字节码重定义类 \"" + className + "\" 失败: " + e.getMessage()
                                : "ERROR: Failed to redefine class \"" + className + "\" by bytecode: " + e.getMessage());
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

    private byte[] compileAndGetClassBytes(String source, String className) {
        String internalName = className.replace('.', '/');
        int lastSlash = internalName.lastIndexOf('/');
        String packageName = lastSlash > 0 ? internalName.substring(0, lastSlash) : "";
        String simpleName = internalName.substring(lastSlash + 1);

        for (String line : source.split("\\r?\\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("package ")) {
                packageName = trimmed.replaceFirst("package\\s+", "")
                        .replaceFirst(";\\s*$", "").trim()
                        .replace('.', '/');
                break;
            }
        }

        File tmpDir = new File(System.getProperty("java.io.tmpdir"),
                "aiwiki_compile_" + System.nanoTime());
        tmpDir.mkdirs();

        try {
            File srcFile = new File(tmpDir, simpleName + ".java");
            java.nio.file.Files.writeString(srcFile.toPath(), source);

            javax.tools.JavaCompiler compiler = javax.tools.ToolProvider.getSystemJavaCompiler();
            if (compiler == null) return null;

            List<String> options = new ArrayList<>();
            options.add("-d");
            options.add(tmpDir.getAbsolutePath());
            options.add("-proc:none");
            options.add("-source");
            options.add("25");
            options.add("-target");
            options.add("25");

            String classpath = buildFullClasspath();
            options.add("-classpath");
            options.add(classpath);

            javax.tools.StandardJavaFileManager fileManager =
                    compiler.getStandardFileManager(null, null, null);
            Iterable<? extends javax.tools.JavaFileObject> units =
                    fileManager.getJavaFileObjects(srcFile);
            javax.tools.DiagnosticCollector<javax.tools.JavaFileObject> diagnostics =
                    new javax.tools.DiagnosticCollector<>();

            javax.tools.JavaCompiler.CompilationTask task =
                    compiler.getTask(null, fileManager, diagnostics, options, null, units);
            boolean success = task.call();
            fileManager.close();

            if (!success) {
                McChatbot.LOGGER.warn("Compilation failed for class {}: {}", className,
                        diagnostics.getDiagnostics().stream()
                                .map(Object::toString)
                                .collect(Collectors.joining("\n")));
                return null;
            }

            String targetInternal = packageName.isEmpty() ? simpleName : packageName + "/" + simpleName;
            File classFile = new File(tmpDir, targetInternal + ".class");
            if (!classFile.exists()) {
                McChatbot.LOGGER.warn("Compiled class file not found at expected path: {}",
                        classFile.getAbsolutePath());
                return null;
            }
            return java.nio.file.Files.readAllBytes(classFile.toPath());
        } catch (Exception e) {
            McChatbot.LOGGER.error("Failed to compile and extract bytes for {}", className, e);
            return null;
        } finally {
            deleteDir(tmpDir);
        }
    }

    private String buildFullClasspath() {
        Set<String> paths = new LinkedHashSet<>();
        for (String entry : System.getProperty("java.class.path").split(File.pathSeparator)) {
            paths.add(entry);
        }
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        while (cl != null) {
            if (cl instanceof java.net.URLClassLoader) {
                for (java.net.URL url : ((java.net.URLClassLoader) cl).getURLs()) {
                    try {
                        paths.add(new File(url.toURI()).getAbsolutePath());
                    } catch (Exception ignored) {}
                }
            }
            cl = cl.getParent();
        }
        return String.join(File.pathSeparator, paths);
    }

    private String replaceByLineMatch(String source, String oldCode, String newCode) {
        List<String> srcLines = source.lines().collect(Collectors.toList());
        List<String> oldLines = oldCode.lines()
                .map(String::strip)
                .filter(l -> !l.isEmpty())
                .collect(Collectors.toList());
        if (oldLines.isEmpty()) return null;

        List<String> srcStripped = srcLines.stream()
                .map(String::strip)
                .collect(Collectors.toList());

        for (int i = 0; i <= srcStripped.size() - oldLines.size(); i++) {
            boolean match = true;
            for (int j = 0; j < oldLines.size(); j++) {
                if (!srcStripped.get(i + j).equals(oldLines.get(j))) {
                    match = false;
                    break;
                }
            }
            if (match) {
                int startIdx = i;
                int endIdx = i + oldLines.size() - 1;
                int realStart = -1, realEnd = -1;
                int strippedPos = 0;
                for (int k = 0; k < srcLines.size(); k++) {
                    String stripped = srcLines.get(k).strip();
                    if (stripped.isEmpty()) continue;
                    if (strippedPos == startIdx) realStart = k;
                    if (strippedPos == endIdx) {
                        realEnd = k;
                        break;
                    }
                    strippedPos++;
                }
                if (realStart == -1 || realEnd == -1) continue;

                List<String> newSrcLines = new ArrayList<>(srcLines);
                newSrcLines.subList(realStart, realEnd + 1).clear();
                String[] newCodeLines = newCode.split("\\r?\\n", -1);
                newSrcLines.addAll(realStart, Arrays.asList(newCodeLines));
                return String.join("\n", newSrcLines);
            }
        }
        return null;
    }

    private String shortDesc(String code) {
        code = code.replaceAll("\\s+", " ").trim();
        return code.length() > 50 ? code.substring(0, 47) + "..." : code;
    }

    private void deleteDir(File dir) {
        if (dir == null || !dir.exists()) return;
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isDirectory()) deleteDir(f);
                else f.delete();
            }
        }
        dir.delete();
    }

    private String validateSchemaCompatibility(byte[] oldBytes, byte[] newBytes) {
        try {
            ClassNode oldNode = readClassNode(oldBytes);
            ClassNode newNode = readClassNode(newBytes);

            StringBuilder errors = new StringBuilder();

            if (!Objects.equals(oldNode.superName, newNode.superName)) {
                errors.append(isZh()
                        ? "- 超类已更改: " + oldNode.superName + " -> " + newNode.superName + "\n"
                        : "- Superclass changed: " + oldNode.superName + " -> " + newNode.superName + "\n");
            }

            if (!Objects.equals(oldNode.interfaces, newNode.interfaces)) {
                errors.append(isZh()
                        ? "- 接口列表已更改\n"
                        : "- Interface list changed\n");
            }

            Set<String> oldFields = new HashSet<>();
            for (FieldNode f : oldNode.fields) {
                oldFields.add(f.name + ":" + f.desc);
            }
            Set<String> newFields = new HashSet<>();
            for (FieldNode f : newNode.fields) {
                newFields.add(f.name + ":" + f.desc);
            }

            Set<String> addedFields = new HashSet<>(newFields);
            addedFields.removeAll(oldFields);
            if (!addedFields.isEmpty()) {
                errors.append(isZh()
                        ? "- 新增了 " + addedFields.size() + " 个字段: " + addedFields + "\n"
                        : "- Added " + addedFields.size() + " field(s): " + addedFields + "\n");
            }

            Set<String> removedFields = new HashSet<>(oldFields);
            removedFields.removeAll(newFields);
            if (!removedFields.isEmpty()) {
                errors.append(isZh()
                        ? "- 删除了 " + removedFields.size() + " 个字段: " + removedFields + "\n"
                        : "- Removed " + removedFields.size() + " field(s): " + removedFields + "\n");
            }

            Set<String> oldMethods = new HashSet<>();
            for (MethodNode m : oldNode.methods) {
                oldMethods.add(m.name + m.desc);
            }
            Set<String> newMethods = new HashSet<>();
            for (MethodNode m : newNode.methods) {
                newMethods.add(m.name + m.desc);
            }

            Set<String> addedMethods = new HashSet<>(newMethods);
            addedMethods.removeAll(oldMethods);
            if (!addedMethods.isEmpty()) {
                errors.append(isZh()
                        ? "- 新增了 " + addedMethods.size() + " 个方法: " + addedMethods + "\n"
                        : "- Added " + addedMethods.size() + " method(s): " + addedMethods + "\n");
            }

            Set<String> removedMethods = new HashSet<>(oldMethods);
            removedMethods.removeAll(newMethods);
            if (!removedMethods.isEmpty()) {
                errors.append(isZh()
                        ? "- 删除了 " + removedMethods.size() + " 个方法: " + removedMethods + "\n"
                        : "- Removed " + removedMethods.size() + " method(s): " + removedMethods + "\n");
            }

            for (MethodNode oldM : oldNode.methods) {
                String key = oldM.name + oldM.desc;
                MethodNode newM = null;
                for (MethodNode m : newNode.methods) {
                    if ((m.name + m.desc).equals(key)) {
                        newM = m;
                        break;
                    }
                }
                if (newM != null) {
                    if ((oldM.access & ~Opcodes.ACC_SYNTHETIC) != (newM.access & ~Opcodes.ACC_SYNTHETIC)) {
                        errors.append(isZh()
                                ? "- 方法 \"" + key + "\" 的访问修饰符已更改\n"
                                : "- Access modifiers of method \"" + key + "\" changed\n");
                    }
                    if (!Objects.equals(oldM.exceptions, newM.exceptions)) {
                        errors.append(isZh()
                                ? "- 方法 \"" + key + "\" 的异常声明已更改\n"
                                : "- Exception declarations of method \"" + key + "\" changed\n");
                    }
                }
            }

            String result = errors.toString();
            return result.isEmpty() ? null : result;
        } catch (Exception e) {
            McChatbot.LOGGER.warn("Schema validation failed, allowing attempt: {}", e.getMessage());
            return null;
        }
    }

    private ClassNode readClassNode(byte[] bytes) {
        ClassReader cr = new ClassReader(bytes);
        ClassNode cn = new ClassNode();
        cr.accept(cn, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return cn;
    }
}