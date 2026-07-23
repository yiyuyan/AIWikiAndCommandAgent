package cn.ksmcbrigade.aiwiki_aca.knowledge;

public class KnowledgePack {
    public final String id;
    public final String name;
    public final String version;
    public final String language;
    public final String description;
    public final String category;
    public final boolean builtin;

    public KnowledgePack(String id, String name, String version, String language, String description, String category, boolean builtin) {
        this.id = id;
        this.name = name;
        this.version = version;
        this.language = language;
        this.description = description;
        this.category = category;
        this.builtin = builtin;
    }
}
