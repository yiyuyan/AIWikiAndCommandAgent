package cn.ksmcbrigade.aiwiki_aca.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class ToolDefinitions {

    public static JsonArray getToolDefinitions() {
        JsonArray tools = new JsonArray();

        tools.add(createTool("list_packs", "List all loaded knowledge packs with their category and file count. Shows the index of available knowledge packs."
                + " / 列出所有知识包索引（名称、分类、文件数）",
                "{\"type\":\"object\",\"properties\":{}}"));

        tools.add(createTool("list_knowledge", "List knowledge files. Filter by category prefix and/or pack_id."
                + " Available categories: aprilfools, block, command, edition, item, mechanism, mob, tech, version, world, other, minecraft_wiki, general, mod, etc."
                + " / 列出知识文件，可按分类和/或知识包筛选",
                "{\"type\":\"object\",\"properties\":{\"category\":{\"type\":\"string\",\"description\":\"Category filter / 分类筛选\"},\"pack_id\":{\"type\":\"string\",\"description\":\"Pack ID filter (use list_packs to see IDs) / 知识包ID筛选\"}}}"));

        tools.add(createTool("read_knowledge", "Read the full content of a knowledge file by its filename."
                + " / 读取知识文件完整内容",
                "{\"type\":\"object\",\"properties\":{\"filename\":{\"type\":\"string\",\"description\":\"The filename to read (e.g. block_石头.txt) / 要读取的文件名\"}},\"required\":[\"filename\"]}"));

        tools.add(createTool("run_command", "Execute a Minecraft command as a server operator."
                + " / 以服务器管理员身份执行Minecraft指令",
                "{\"type\":\"object\",\"properties\":{\"command\":{\"type\":\"string\",\"description\":\"The command to execute (with or without leading slash) / 要执行的指令（可带或不带斜杠）\"}},\"required\":[\"command\"]}"));

        tools.add(createTool("ask_player", "Ask the player a question and wait for their response. The player responds via /ai <answer>."
                + " / 向玩家提问并等待回答。玩家通过 /ai <回答> 回复。",
                "{\"type\":\"object\",\"properties\":{\"question\":{\"type\":\"string\",\"description\":\"The question to ask the player / 要问玩家的问题\"}},\"required\":[\"question\"]}"));

        return tools;
    }

    private static JsonObject createTool(String name, String description, String parameters) {
        JsonObject tool = new JsonObject();
        tool.addProperty("type", "function");
        JsonObject func = new JsonObject();
        func.addProperty("name", name);
        func.addProperty("description", description);
        func.add("parameters", JsonParser.parseString(parameters).getAsJsonObject());
        tool.add("function", func);
        return tool;
    }
}
