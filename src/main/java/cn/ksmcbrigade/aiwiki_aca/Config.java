package cn.ksmcbrigade.aiwiki_aca;

import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.Arrays;
import java.util.List;

public class Config {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.ConfigValue<Boolean> USE_BUILTIN_API;
    public static final ModConfigSpec.ConfigValue<String> CUSTOM_API_BASE_URL;
    public static final ModConfigSpec.ConfigValue<String> CUSTOM_API_KEY;
    public static final ModConfigSpec.ConfigValue<String> CUSTOM_MODEL;
    public static final ModConfigSpec.ConfigValue<Integer> TOOL_CALL_MAX_ROUNDS;
    public static final ModConfigSpec.ConfigValue<Boolean> APPROVAL_REQUIRED;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> DANGEROUS_COMMANDS;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> BLACKLIST_COMMANDS;
    public static final ModConfigSpec.ConfigValue<String> AI_PREFIX;
    public static final ModConfigSpec.ConfigValue<String> LANGUAGE;
    public static final ModConfigSpec.ConfigValue<Boolean> ENABLE_MINECRAFT_WIKI;
    public static final ModConfigSpec.ConfigValue<Boolean> ENABLE_TWILIGHT_FOREST;
    public static final ModConfigSpec.ConfigValue<Boolean> DEBUG_MODE;

    static {
        BUILDER.push("api");
        USE_BUILTIN_API = BUILDER
                .comment("Use built-in API (recommended) / 使用内置API（推荐）")
                .define("use_builtin_api", true);
        CUSTOM_API_BASE_URL = BUILDER
                .comment("Custom API base URL (only used when use_builtin_api is false) / 自定义API地址（仅当use_builtin_api为false时使用）")
                .define("custom_api_base_url", "");
        CUSTOM_API_KEY = BUILDER
                .comment("Custom API key (only used when use_builtin_api is false) / 自定义API密钥（仅当use_builtin_api为false时使用）")
                .define("custom_api_key", "");
        CUSTOM_MODEL = BUILDER
                .comment("Model name for custom API (hardcoded by user) / 自定义API模型名称（用户指定）")
                .define("custom_model", "");
        BUILDER.pop();

        BUILDER.push("ai");
        TOOL_CALL_MAX_ROUNDS = BUILDER
                .comment("Maximum number of tool call rounds the AI can perform per message / AI每轮消息最大工具调用次数")
                .defineInRange("tool_call_max_rounds", 300, 1, 1000000);
        BUILDER.pop();

        BUILDER.push("command");
        APPROVAL_REQUIRED = BUILDER
                .comment("Require player approval for all AI-executed commands / 要求玩家批准所有AI执行的指令")
                .define("approval_required", false);
        DANGEROUS_COMMANDS = BUILDER
                .comment("Commands considered dangerous and always require approval / 被视为危险且始终需要批准的指令")
                .defineList("dangerous_commands", () -> Arrays.asList("/kill", "/fill", "/execute", "/setblock", "/summon", "/give", "/effect", "/gamemode", "/time", "/weather", "/difficulty"), s -> s instanceof String);
        BLACKLIST_COMMANDS = BUILDER
                .comment("Commands that the AI is never allowed to execute / AI永远不允许执行的指令")
                .defineList("blacklist_commands", () -> Arrays.asList("/ban", "/ban-ip", "/op", "/deop", "/kick", "/stop", "/save-all", "/pardon"), s -> s instanceof String);
        BUILDER.pop();

        BUILDER.push("player");
        AI_PREFIX = BUILDER
                .comment("Prefix for AI messages in chat / AI消息在聊天中的前缀")
                .define("ai_prefix", "§b[AI] §r");
        LANGUAGE = BUILDER
                .comment("Language setting (zh_cn or en_us) / 语言设置（zh_cn或en_us）")
                .define("language", "zh_cn");
        BUILDER.pop();

        BUILDER.push("knowledge");
        ENABLE_MINECRAFT_WIKI = BUILDER
                .comment("Enable built-in Minecraft Wiki knowledge pack / 启用内置Minecraft Wiki知识包")
                .define("enable_minecraft_wiki", true);
        ENABLE_TWILIGHT_FOREST = BUILDER
                .comment("Enable built-in Twilight Forest Wiki knowledge pack / 启用内置暮色森林Wiki知识包")
                .define("enable_twilight_forest", true);
        BUILDER.pop();

        BUILDER.push("debug");
        DEBUG_MODE = BUILDER
                .comment("Enable debug logging / 启用调试日志")
                .define("debug_mode", false);
        BUILDER.pop();
    }

    public static final ModConfigSpec SPEC = BUILDER.build();

    public static void register(net.neoforged.fml.ModContainer container) {
        container.registerConfig(ModConfig.Type.COMMON, SPEC, "aiwikiandcommand-common.toml");
    }
}
