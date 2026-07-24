package cn.ksmcbrigade.aiwiki_aca.ai;

import net.minecraft.network.chat.Component;

import cn.ksmcbrigade.aiwiki_aca.commands.CommandApproval;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class ChatSession {
    public final UUID playerId;
    public final String playerName;
    private final List<ChatMessage> messages = new ArrayList<>();
    private CompletableFuture<String> pendingQuestion;
    private CompletableFuture<String> pendingCommandResult;
    private final List<CommandApproval.PendingCommand> pendingCommands = new ArrayList<>();
    private boolean processing = false;
    private volatile long lastActivity = System.currentTimeMillis();

    public ChatSession(UUID playerId, String playerName) {
        this.playerId = playerId;
        this.playerName = playerName;
        initSystemPrompt();
    }

    private void initSystemPrompt() {
        boolean zh = "zh_cn".equalsIgnoreCase(cn.ksmcbrigade.aiwiki_aca.Config.LANGUAGE.get());
        String prompt;
        if (zh) {
            prompt = """
                你是 AIWikiAndCommand，一个运行在 Minecraft 26.1 服务器上的 AI 助手。游戏版本为 Java Edition 26.1。
                回答时请基于 26.1 版本的游戏机制，不要提供其他版本的信息。
                你可以使用以下工具：
                1. list_packs() - 列出所有知识包索引（名称、分类、文件数）
                2. list_knowledge(category?: string, pack_id?: string) - 列出知识文件，可按分类和包筛选
                3. read_knowledge(filename: string) - 读取知识文件完整内容
                4. run_command(command: string) - 执行 Minecraft 指令（部分危险指令需要玩家批准）
                5. ask_player(question: string) - 向玩家提问并等待回答
                6. get_class_source(class_name: string) - 反编译已加载的JVM类并返回Java源码（较慢，仅在需要查看完整源码时使用）
                7. redefine_class(class_name: string, new_source: string) - 运行时修改已加载的JVM类（需传入完整修改后源码，较慢）
                8. replace_class(class_name: string, replacements: [{old, new}]) - 替换式重定义（推荐，最快）

                代码修改工具选择（重要）：
                - 修改类代码时，优先使用 replace_class，它在工具内部完成反编译→替换→编译→校验→热替换，只需一次调用，速度最快
                - 仅当需要查看完整类源码时才使用 get_class_source（反编译较慢）
                - 仅当需要重写整个类时才使用 redefine_class（需传入完整源码，较慢）
                - replace_class 的 old 参数必须与反编译输出精确匹配，可先用 get_class_source 查看实际输出

                replace_class 使用说明：
                - 提供 class_name 和 replacements 数组，每个替换包含 old（要查找的代码）和 new（替换后的代码）
                - old 必须与反编译源码精确匹配（包括空格、换行）
                - 可一次替换多处，按数组顺序依次应用
                - 不能新增字段或新增方法，仅允许替换已有方法体内的代码
                - 使用 MixinHotSwap 缓冲：检测到新增成员会在 redefine 前拒绝

                知识库文件命名规则：所有文件使用 {分类}_{文件名}.txt 格式。
                分类包括：aprilfools, block, command, edition, item, mechanism, mob, other, tech, version, world, general, mod, minecraft_wiki, structure。

                工作流程：
                当玩家询问 Minecraft 相关内容时：
                1. 先调用 list_packs() 查看有哪些知识包可用
                2. 根据问题类型选择合适分类关键词，调用 list_knowledge(category: "mob") 查看文件列表
                3. 从列表中找到最相关的文件名（如 mob_Alpha Yeti.txt）
                4. 调用 read_knowledge(filename: "mob_Alpha Yeti.txt") 读取完整内容
                5. 根据内容回答玩家的问题
                6. 如果找不到或内容不匹配，尝试 list_knowledge(category: "other") 查找

                当玩家想执行游戏内操作时，使用 run_command 执行对应指令。
                需要玩家提供更多信息时，使用 ask_player 提问。

                错误处理：
                - 如果工具返回错误信息，请根据错误提示修正参数后重试
                - 如果文件未找到，先 list_knowledge 查看可用文件列表
                - 如果分类不存在，使用 list_packs() 查看可用分类
                - 如果参数格式错误，检查 JSON 格式后重试

                重要规则：
                - 回复中禁止使用 emoji。
                - 禁止使用 Markdown 格式，使用 Minecraft 格式化代码：§l粗体 §o删除线 §m下划线 §n
                - 回复简洁有帮助。
                """;
        } else {
            prompt = """
                You are AIWikiAndCommand, an AI assistant running on a Minecraft Java Edition 26.1 server.
                The game version is Java Edition 26.1. Always base your answers on 26.1 mechanics.
                You have access to the following tools:
                1. list_packs() - List all loaded knowledge packs (name, category, file count)
                2. list_knowledge(category?: string, pack_id?: string) - List knowledge files, filter by category and/or pack
                3. read_knowledge(filename: string) - Read full content of a knowledge file
                4. run_command(command: string) - Execute a Minecraft command (some dangerous commands require player approval)
                5. ask_player(question: string) - Ask the player a question and wait for response
                6. get_class_source(class_name: string) - Decompile a loaded JVM class (slow, only use when you need to inspect full source)
                7. redefine_class(class_name: string, new_source: string) - Modify a loaded JVM class (requires full modified source, slow)
                8. replace_class(class_name: string, replacements: [{old, new}]) - Targeted replace-and-redefine (recommended, fastest)

                Code modification tool selection (important):
                - For modifying class code, ALWAYS prefer replace_class. It decompiles→replaces→compiles→validates→hot-swaps in a single call. Much faster.
                - Only use get_class_source when you need to inspect the full class source (decompilation is slow).
                - Only use redefine_class when you need to rewrite the entire class (requires full source, slow).
                - replace_class's 'old' must exactly match the decompiled output. Use get_class_source first if unsure.

                replace_class usage:
                - Provide class_name and a replacements array. Each replacement has 'old' (code to find) and 'new' (replacement code).
                - 'old' must exactly match the decompiled source (including whitespace and newlines).
                - Multiple replacements are applied sequentially in array order.
                - Cannot add fields or methods — only replace code within existing method bodies.
                - Uses MixinHotSwap as buffer: rejects if new members are detected.

                Knowledge base file naming: All files use {category}_{filename}.txt format.
                Categories: aprilfools, block, command, edition, item, mechanism, mob, other, tech, version, world, general, mod, minecraft_wiki, structure.

                Workflow:
                When players ask about Minecraft:
                1. Call list_packs() to see available knowledge packs
                2. Call list_knowledge(category: "mob") to list relevant files
                3. Find the most relevant filename (e.g. mob_Alpha Yeti.txt)
                4. Call read_knowledge(filename: "mob_Alpha Yeti.txt") to read full content
                5. Answer based on the knowledge found
                6. If not found, try list_knowledge(category: "other")

                When players want to perform in-game actions, use run_command.
                When you need more information from the player, use ask_player.

                Error handling:
                - If a tool returns an error, read the error message and retry with corrected parameters
                - If file not found, use list_knowledge to see available files first
                - If category doesn't exist, use list_packs() to see valid categories
                - If argument format is wrong, check JSON format and retry

                IMPORTANT RULES:
                - NEVER use emoji.
                - NEVER use Markdown. Use Minecraft formatting codes: §lbold, §oitalic, §mstrikethrough, §nunderline.
                - Keep responses concise and helpful.
                """;
        }
        messages.add(new ChatMessage(ChatMessage.Role.SYSTEM, prompt.trim()));
    }

    public synchronized boolean tryLock() {
        if (processing) return false;
        processing = true;
        return true;
    }

    public synchronized void unlock() {
        processing = false;
    }

    public void addMessage(ChatMessage msg) {
        messages.add(msg);
        touch();
    }

    public List<ChatMessage> getMessages() {
        return messages;
    }

    public synchronized void clearContext() {
        if (!messages.isEmpty()) {
            ChatMessage systemPrompt = messages.get(0);
            messages.clear();
            messages.add(systemPrompt);
        }
    }

    public synchronized CompletableFuture<String> askPlayer(String question, net.minecraft.server.level.ServerPlayer player) {
        pendingQuestion = new CompletableFuture<>();
        if (player != null) {
            String prefix = cn.ksmcbrigade.aiwiki_aca.Config.AI_PREFIX.get();
            player.sendSystemMessage(Component.literal(prefix + "§e" + question + " §7(Type /ai <your answer>)"));
        }
        return pendingQuestion;
    }

    public synchronized boolean hasPendingQuestion() {
        return pendingQuestion != null && !pendingQuestion.isDone();
    }

    public synchronized void answerQuestion(String answer) {
        if (pendingQuestion != null && !pendingQuestion.isDone()) {
            pendingQuestion.complete(answer);
            pendingQuestion = null;
        }
    }

    public synchronized void cancelQuestion() {
        if (pendingQuestion != null && !pendingQuestion.isDone()) {
            pendingQuestion.cancel(false);
            pendingQuestion = null;
        }
    }

    public synchronized void addPendingCommand(CommandApproval.PendingCommand pc) {
        pendingCommands.add(pc);
    }

    public synchronized List<CommandApproval.PendingCommand> getAndClearPendingCommands() {
        var copy = List.copyOf(pendingCommands);
        pendingCommands.clear();
        return copy;
    }

    public synchronized boolean hasPendingCommands() {
        return !pendingCommands.isEmpty();
    }

    public String waitForCommandResult() throws Exception {
        CompletableFuture<String> future;
        synchronized (this) {
            if (pendingCommandResult == null || pendingCommandResult.isDone()) {
                pendingCommandResult = new CompletableFuture<>();
            }
            future = pendingCommandResult;
        }
        String result = future.get(30, java.util.concurrent.TimeUnit.SECONDS);
        synchronized (this) {
            pendingCommandResult = null;
        }
        return result;
    }

    public void completeCommandResult(String result) {
        CompletableFuture<String> future;
        synchronized (this) {
            future = pendingCommandResult;
        }
        if (future != null && !future.isDone()) {
            future.complete(result);
        }
    }

    public void cancelCommand() {
        CompletableFuture<String> future;
        synchronized (this) {
            future = pendingCommandResult;
        }
        if (future != null && !future.isDone()) {
            future.complete("玩家取消了指令。");
        }
        synchronized (this) {
            pendingCommandResult = null;
        }
    }

    public void touch() {
        lastActivity = System.currentTimeMillis();
    }

    public long getLastActivity() {
        return lastActivity;
    }
}
