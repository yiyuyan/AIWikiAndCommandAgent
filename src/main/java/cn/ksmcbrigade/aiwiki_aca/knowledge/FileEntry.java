package cn.ksmcbrigade.aiwiki_aca.knowledge;

public class FileEntry {
    public final String packId;
    public final String path;
    public final String content;

    public FileEntry(String packId, String path, String content) {
        this.packId = packId;
        this.path = path;
        this.content = content;
    }
}
