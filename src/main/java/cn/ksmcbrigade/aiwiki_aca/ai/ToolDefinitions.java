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

        tools.add(createTool("get_class_source", "Decompile a loaded JVM class and return its Java source code. Use the fully qualified class name (e.g. java.lang.String)."
                + " / 反编译已加载的JVM类并返回Java源码。使用全限定类名（如 java.lang.String）。",
                "{\"type\":\"object\",\"properties\":{\"class_name\":{\"type\":\"string\",\"description\":\"Fully qualified class name (e.g. java.lang.String) / 全限定类名\"}},\"required\":[\"class_name\"]}"));

        tools.add(createTool("redefine_class",
                "Modify a loaded JVM class at runtime. The modified source MUST NOT add new fields or new methods compared to the original — "
                + "only method bodies, field initializers, constructors, and static blocks may change. "
                + "Uses MixinHotSwap as buffer: if schema-compatible the change is applied directly; "
                + "if not (added members detected) the change is rejected before attempting redefine. "
                + "SLOW: decompiles entire class. Prefer replace_class for targeted changes."
                + " / 运行时修改已加载的JVM类。修改后的源码不能新增字段或方法。会反编译整个类，速度较慢。"
                + " 定向修改请优先使用 replace_class。",
                "{\"type\":\"object\",\"properties\":{"
                + "\"class_name\":{\"type\":\"string\",\"description\":\"Fully qualified class name to redefine / 要修改的全限定类名\"},"
                + "\"new_source\":{\"type\":\"string\",\"description\":\"The full modified Java source code of the class / 修改后的完整Java源码\"}"
                + "},\"required\":[\"class_name\",\"new_source\"]}"));

        tools.add(createTool("replace_class",
                "TARGETED REDEFINE: Replace specific code blocks in a loaded JVM class. The tool decompiles the class internally, "
                + "applies all old->new string replacements, recompiles, validates schema (no new fields/methods), "
                + "and hot-swaps via MixinHotSwap. Much faster than get_class_source + redefine_class for targeted changes. "
                + "Each 'old' must exactly match text in the decompiled source."
                + " / 替换式重定义：将指定类中匹配的代码块替换为新代码块。工具内部完成反编译→替换→编译→校验→热替换。"
                + " 比 get_class_source + redefine_class 快得多。old 必须与反编译输出精确匹配。",
                "{\"type\":\"object\",\"properties\":{"
                + "\"class_name\":{\"type\":\"string\",\"description\":\"Fully qualified class name / 全限定类名\"},"
                + "\"replacements\":{\"type\":\"array\",\"description\":\"List of code block replacements to apply sequentially / 按顺序应用的替换列表\","
                + "\"items\":{\"type\":\"object\",\"properties\":{"
                + "\"old\":{\"type\":\"string\",\"description\":\"Exact code to find in decompiled source / 在反编译源码中查找的精确代码\"},"
                + "\"new\":{\"type\":\"string\",\"description\":\"Replacement code / 替换后的代码\"}"
                + "},\"required\":[\"old\",\"new\"]}}"
                + "},\"required\":[\"class_name\",\"replacements\"]}"));

        tools.add(createTool("redefine_class_no_verify",
                "NO-VERIFY redefine: same as redefine_class but SKIPS compilation pre-check and schema validation. "
                + "Still compiles internally to produce bytecode, but does not reject on compilation errors or schema changes. "
                + "MixinHotSwap handles schema changes via trampoline. USE WITH CAUTION."
                + " / 无校验重定义：与 redefine_class 相同但跳过编译预检和 schema 校验。"
                + " 仍会内部编译生成字节码，但不会因编译错误或 schema 变更而拒绝。MixinHotSwap 通过 trampoline 处理 schema 变更。请谨慎使用。",
                "{\"type\":\"object\",\"properties\":{"
                + "\"class_name\":{\"type\":\"string\",\"description\":\"Fully qualified class name / 全限定类名\"},"
                + "\"new_source\":{\"type\":\"string\",\"description\":\"The full modified Java source code / 修改后的完整Java源码\"}"
                + "},\"required\":[\"class_name\",\"new_source\"]}"));

        tools.add(createTool("replace_class_no_verify",
                "NO-VERIFY replace: same as replace_class but SKIPS compilation pre-check and schema validation. "
                + "Still compiles internally to produce bytecode, but does not reject on compilation errors or schema changes. "
                + "MixinHotSwap handles schema changes via trampoline. USE WITH CAUTION."
                + " / 无校验替换式重定义：与 replace_class 相同但跳过编译预检和 schema 校验。"
                + " 仍会内部编译生成字节码，但不会因编译错误或 schema 变更而拒绝。MixinHotSwap 通过 trampoline 处理 schema 变更。请谨慎使用。",
                "{\"type\":\"object\",\"properties\":{"
                + "\"class_name\":{\"type\":\"string\",\"description\":\"Fully qualified class name / 全限定类名\"},"
                + "\"replacements\":{\"type\":\"array\",\"description\":\"List of code block replacements / 替换列表\","
                + "\"items\":{\"type\":\"object\",\"properties\":{"
                + "\"old\":{\"type\":\"string\",\"description\":\"Code to find / 要查找的代码\"},"
                + "\"new\":{\"type\":\"string\",\"description\":\"Replacement code / 替换后的代码\"}"
                + "},\"required\":[\"old\",\"new\"]}}"
                + "},\"required\":[\"class_name\",\"replacements\"]}"));

        tools.add(createTool("get_source_bytes",
                "Get the raw bytecode of a loaded JVM class as a Base64 string. Useful for inspecting or transferring bytecode. "
                + " / 获取已加载JVM类的原始字节码，以Base64字符串返回。可用于检查或传输字节码。",
                "{\"type\":\"object\",\"properties\":{"
                + "\"class_name\":{\"type\":\"string\",\"description\":\"Fully qualified class name / 全限定类名\"}"
                + "},\"required\":[\"class_name\"]}"));

        tools.add(createTool("redefine_class_by_bytes_no_verify",
                "NO-VERIFY redefine by bytecode: redefine a loaded JVM class using raw bytecode (Base64 encoded). "
                + "SKIPS compilation pre-check and schema validation. MixinHotSwap handles schema changes via trampoline. "
                + "USE WITH CAUTION — only use when you have valid bytecode (e.g. from get_source_bytes + modifications)."
                + " / 无校验字节码重定义：通过原始字节码（Base64编码）重定义已加载的JVM类。"
                + " 跳过编译预检和 schema 校验。MixinHotSwap 通过 trampoline 处理 schema 变更。请谨慎使用。",
                "{\"type\":\"object\",\"properties\":{"
                + "\"class_name\":{\"type\":\"string\",\"description\":\"Fully qualified class name / 全限定类名\"},"
                + "\"bytes\":{\"type\":\"string\",\"description\":\"Base64-encoded class bytecode / Base64编码的类字节码\"}"
                + "},\"required\":[\"class_name\",\"bytes\"]}"));

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
