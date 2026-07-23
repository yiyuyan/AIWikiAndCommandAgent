package cn.ksmcbrigade.aiwiki_aca.knowledge;

public class PackInfo {
    public final String id;
    public final String name;
    public final String version;
    public final String language;
    public final boolean builtin;
    public final long fileCount;

    public PackInfo(String id, String name, String version, String language, boolean builtin, long fileCount) {
        this.id = id;
        this.name = name;
        this.version = version;
        this.language = language;
        this.builtin = builtin;
        this.fileCount = fileCount;
    }
}
