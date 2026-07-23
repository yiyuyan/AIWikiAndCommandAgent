package cn.ksmcbrigade.aiwiki_aca.ai;

import com.google.gson.JsonObject;

public class ToolCall {
    public final String id;
    public final String type;
    public final String functionName;
    public final String arguments;

    public ToolCall(String id, String type, String functionName, String arguments) {
        this.id = id;
        this.type = type;
        this.functionName = functionName;
        this.arguments = arguments;
    }

    public JsonObject toJson() {
        JsonObject obj = new JsonObject();
        obj.addProperty("id", id);
        obj.addProperty("type", type);
        JsonObject func = new JsonObject();
        func.addProperty("name", functionName);
        func.addProperty("arguments", arguments);
        obj.add("function", func);
        return obj;
    }
}
