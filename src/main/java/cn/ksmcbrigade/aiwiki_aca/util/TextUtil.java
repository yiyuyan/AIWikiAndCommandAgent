package cn.ksmcbrigade.aiwiki_aca.util;

import net.minecraft.network.chat.Component;

import java.util.regex.Pattern;

public class TextUtil {
    private static final Pattern EMOJI = Pattern.compile(
            "[\\uD83C-\\uDBFF\\uDC00-\\uDFFF]|[\\u2600-\\u27BF]|[\\u2300-\\u23FF]|[\\u2B50]|[\\uFE00-\\uFE0F]|[\\u200D]");

    public static String stripEmoji(String text) {
        return EMOJI.matcher(text).replaceAll("");
    }

    public static String markdownToMinecraft(String text) {
        text = text.replace("\\u00a7", "§");
        text = text.replaceAll("\\*\\*\\*(.+?)\\*\\*\\*", "§l§o$1§r");
        text = text.replaceAll("\\*\\*(.+?)\\*\\*", "§l$1§r");
        text = text.replaceAll("(?<![*])\\*(?![*])(.+?)(?<![*])\\*(?![*])", "§o$1§r");
        text = text.replaceAll("~~(.+?)~~", "§m$1§r");
        text = text.replaceAll("__(.+?)__", "§n$1§r");
        text = text.replaceAll("`([^`]+)`", "§7$1§r");
        text = text.replaceAll("```[\\s\\S]*?```", "");
        text = text.replaceAll("(?m)^#{1,6}\\s+", "§l");
        text = text.replaceAll("(?m)^[-*]\\s+", " §7• §r");
        text = text.replaceAll("(?m)^\\d+\\.\\s+", " §7$0§r");
        return text;
    }

    public static Component prefixed(String prefix, String message) {
        return Component.literal(prefix + message);
    }

    public static Component aiMessage(String message) {
        return Component.literal("§b[AI] §r" + message);
    }

    public static Component error(String message) {
        return Component.literal("§c" + message);
    }

    public static Component success(String message) {
        return Component.literal("§a" + message);
    }
}
