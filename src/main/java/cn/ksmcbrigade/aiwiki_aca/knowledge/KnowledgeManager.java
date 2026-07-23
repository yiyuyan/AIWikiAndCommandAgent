package cn.ksmcbrigade.aiwiki_aca.knowledge;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import cn.ksmcbrigade.aiwiki_aca.Config;
import cn.ksmcbrigade.aiwiki_aca.McChatbot;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * KnowledgeManager v3: Extract built-in pack to disk on startup, then read from flat files.
 * External packs (zip/folder) are also extracted/scanned to the same directory.
 */
public class KnowledgeManager {
    private static KnowledgeManager INSTANCE;

    private final List<KnowledgePack> packs = new ArrayList<>();
    private final Map<String, String> fileContents = new LinkedHashMap<>();
    private final Map<String, String> filePackMap = new LinkedHashMap<>();
    private Path extractDir;
    private boolean loaded = false;

    public static synchronized KnowledgeManager getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new KnowledgeManager();
        }
        return INSTANCE;
    }

    public void load() {
        packs.clear();
        fileContents.clear();
        filePackMap.clear();

        try {
            extractDir = Files.createTempDirectory("aiwikiandcommand_knowledge");
            extractDir.toFile().deleteOnExit();

            // Ensure external knowledge directory exists
            File knowledgeDir = getKnowledgeDir();
            knowledgeDir.mkdirs();
            McChatbot.LOGGER.info("External knowledge dir: {}", knowledgeDir.getAbsolutePath());

            extractBuiltinPack();
            loadExternalPacks();

            loaded = true;
            long totalFiles = fileContents.size();
            McChatbot.LOGGER.info("KnowledgeManager loaded: {} packs, {} files", packs.size(), totalFiles);
            for (KnowledgePack p : packs) {
                long c = filePackMap.values().stream().filter(v -> v.equals(p.id)).count();
                McChatbot.LOGGER.info("  Pack: {} ({}) - {} files", p.name, p.id, c);
            }
        } catch (Exception e) {
            McChatbot.LOGGER.error("Failed to load knowledge base", e);
        }
    }

    private void extractBuiltinPack() throws Exception {
        // Find the JAR file containing our mod
        String jarPath = getClass().getProtectionDomain().getCodeSource().getLocation().getPath();
        if (jarPath.startsWith("/") && jarPath.length() > 3 && jarPath.charAt(2) == ':') {
            jarPath = jarPath.substring(1);
        }
        jarPath = java.net.URLDecoder.decode(jarPath, "UTF-8");

        // Strip NeoForge union/JAR-in-JAR suffix like #191!/
        if (jarPath.contains("#")) {
            jarPath = jarPath.substring(0, jarPath.indexOf('#'));
        }

        McChatbot.LOGGER.info("JAR path resolved to: {}", jarPath);

        Path jarFile = Paths.get(jarPath);
        if (!Files.exists(jarFile)) {
            McChatbot.LOGGER.warn("JAR not found at {}, trying classpath scan", jarPath);
            extractViaClasspath();
            return;
        }

        // Collect subdirectory names from the JAR
        Set<String> subdirs = new LinkedHashSet<>();
        try (ZipFile zf = new ZipFile(jarFile.toFile(), StandardCharsets.UTF_8)) {
            var entries = zf.entries();
            while (entries.hasMoreElements()) {
                ZipEntry ze = entries.nextElement();
                String name = ze.getName();
                if (!name.startsWith("data/aiwikiandcommand/knowledge/")) continue;
                String relative = name.substring("data/aiwikiandcommand/knowledge/".length());
                if (relative.contains("/")) {
                    subdirs.add(relative.substring(0, relative.indexOf('/')));
                }
            }
        }
        McChatbot.LOGGER.info("Found built-in pack subdirectories: {}", subdirs);

        for (String subdir : subdirs) {
            loadBuiltinSubdir(subdir);
        }
    }

    private void loadBuiltinSubdir(String subdir) throws Exception {
        // Check config: minecraft_wiki -> enable_minecraft_wiki, twilight_forest -> enable_twilight_forest
        String configKey = "enable_" + subdir.replace("-", "_");
        boolean enabled = true;
        switch (configKey) {
            case "enable_minecraft_wiki":
                enabled = Config.ENABLE_MINECRAFT_WIKI.get();
                break;
            case "enable_twilight_forest":
                enabled = Config.ENABLE_TWILIGHT_FOREST.get();
                break;
        }
        if (!enabled) {
            McChatbot.LOGGER.info("Built-in pack '{}' is disabled by config", subdir);
            return;
        }

        String jarPath = getClass().getProtectionDomain().getCodeSource().getLocation().getPath();
        if (jarPath.startsWith("/") && jarPath.length() > 3 && jarPath.charAt(2) == ':') {
            jarPath = jarPath.substring(1);
        }
        jarPath = java.net.URLDecoder.decode(jarPath, "UTF-8");

        // Strip NeoForge union/JAR-in-JAR suffix like #191!/
        if (jarPath.contains("#")) {
            jarPath = jarPath.substring(0, jarPath.indexOf('#'));
        }

        Path jarFile = Paths.get(jarPath);
        if (!Files.exists(jarFile)) {
            McChatbot.LOGGER.warn("JAR not found at {}, trying classpath scan for {}", jarPath, subdir);
            extractViaClasspath();
            return;
        }

        String prefix = "data/aiwikiandcommand/knowledge/" + subdir + "/";
        KnowledgePack pack = null;

        try (ZipFile zf = new ZipFile(jarFile.toFile(), StandardCharsets.UTF_8)) {
            var entries = zf.entries();
            while (entries.hasMoreElements()) {
                ZipEntry ze = entries.nextElement();
                String name = ze.getName();
                if (ze.isDirectory()) continue;
                if (!name.startsWith(prefix)) continue;

                String filename = name.substring(prefix.length());

                // Read manifest first (before .txt filter)
                if (filename.equals("_manifest.json")) {
                    try (InputStream is = zf.getInputStream(ze)) {
                        String json = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))
                                .lines().collect(Collectors.joining("\n"));
                        pack = parseManifest(json, true);
                    }
                    continue;
                }

                if (!name.endsWith(".txt")) continue;
                if (filename.startsWith("_")) continue;

                if (pack == null) {
                    // No manifest found yet, use default pack id
                    pack = new KnowledgePack(subdir, subdir, "1.0", "zh_cn",
                            "Built-in pack: " + subdir, "general", true);
                }

                try (InputStream is = zf.getInputStream(ze)) {
                    String content = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))
                            .lines().collect(Collectors.joining("\n"));
                    String key = pack.id + ":" + filename;
                    fileContents.put(key, content);
                    filePackMap.put(filename, pack.id);
                }
            }
        }

        if (pack == null) {
            pack = new KnowledgePack(subdir, subdir, "1.0", "zh_cn",
                    "Built-in pack: " + subdir, "general", true);
        }
        final KnowledgePack fp = pack;
        packs.add(fp);
        long count = filePackMap.values().stream().filter(v -> v.equals(fp.id)).count();
        McChatbot.LOGGER.info("Loaded built-in pack '{}' with {} files", fp.id, count);
    }

    private void extractViaClasspath() {
        // Fallback for development environment - scan subdirectories
        try {
            var classLoader = getClass().getClassLoader();
            var urls = classLoader.getResources("data/aiwikiandcommand/knowledge");
            while (urls.hasMoreElements()) {
                java.net.URL url = urls.nextElement();
                try {
                    Path basePath = Paths.get(url.toURI());
                    if (!Files.exists(basePath)) continue;
                    File[] subdirs = basePath.toFile().listFiles(File::isDirectory);
                    if (subdirs == null) continue;
                    for (File subdir : subdirs) {
                        loadBuiltinSubdirFromPath(subdir.getName(), subdir.toPath());
                    }
                } catch (Exception ignored) {}
            }
        } catch (Exception e) {
            McChatbot.LOGGER.warn("Classpath scan failed: {}", e.getMessage());
        }
    }

    private void loadBuiltinSubdirFromPath(String subdirName, Path subdirPath) {
        if (!isPackEnabled(subdirName)) return;

        try {
            File manifestFile = new File(subdirPath.toFile(), "_manifest.json");
            KnowledgePack pack = readManifest(manifestFile, true);
            if (pack == null) {
                pack = new KnowledgePack(subdirName, subdirName, "1.0", "zh_cn",
                        "Built-in pack: " + subdirName, "general", true);
            }
            final KnowledgePack fp = pack;
            packs.add(fp);

            Files.walk(subdirPath).filter(Files::isRegularFile).forEach(p -> {
                try {
                    String filename = subdirPath.relativize(p).toString().replace('\\', '/');
                    if (filename.startsWith("_")) return;
                    String content = Files.readString(p, StandardCharsets.UTF_8);
                    String key = fp.id + ":" + filename;
                    fileContents.put(key, content);
                    filePackMap.put(filename, fp.id);
                } catch (Exception ignored) {}
            });
        } catch (Exception e) {
            McChatbot.LOGGER.error("Failed to load built-in pack from " + subdirName, e);
        }
    }

    private boolean isPackEnabled(String subdirName) {
        switch (subdirName) {
            case "minecraft_wiki":
                return Config.ENABLE_MINECRAFT_WIKI.get();
            case "twilight_forest":
                return Config.ENABLE_TWILIGHT_FOREST.get();
            default:
                return true;
        }
    }

    private File getKnowledgeDir() {
        // Try multiple approaches to find the game directory
        try {
            Path gameDir = net.neoforged.fml.loading.FMLPaths.GAMEDIR.get();
            File dir = gameDir.resolve("aiwikiandcommand/knowledge").toFile();
            return dir;
        } catch (Exception e1) {
            try {
                var server = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
                if (server != null) {
                    return server.getServerDirectory().resolve("aiwikiandcommand/knowledge").toFile();
                }
            } catch (Exception e2) {
                // ignore
            }
            return new File("aiwikiandcommand/knowledge");
        }
    }

    public File getKnowledgeDirPublic() {
        return getKnowledgeDir();
    }

    private void loadExternalPacks() {
        File externalDir = getKnowledgeDir();
        if (!externalDir.isDirectory()) return;

        File[] entries = externalDir.listFiles();
        if (entries == null) return;

        for (File entry : entries) {
            if (entry.isDirectory()) {
                loadExternalDirPack(entry);
            } else if (entry.getName().toLowerCase().endsWith(".zip")) {
                loadExternalZipPack(entry);
            }
        }
    }

    private void loadExternalDirPack(File dir) {
        try {
            File manifestFile = new File(dir, "_manifest.json");
            KnowledgePack pack = readManifest(manifestFile, false);
            if (pack == null) {
                pack = new KnowledgePack(dir.getName(), dir.getName(), "1.0", "unknown",
                        "External pack: " + dir.getName(), "general", false);
            }
            packs.add(pack);

            File[] files = dir.listFiles();
            if (files == null) return;
            for (File f : files) {
                if (!f.isFile() || !f.getName().endsWith(".txt") || f.getName().startsWith("_")) continue;
                String content = Files.readString(f.toPath(), StandardCharsets.UTF_8);
                String key = pack.id + ":" + f.getName();
                fileContents.put(key, content);
                filePackMap.put(f.getName(), pack.id);
            }
        } catch (Exception e) {
            McChatbot.LOGGER.error("Failed to load pack from " + dir.getName(), e);
        }
    }

    private void loadExternalZipPack(File zipFile) {
        try (ZipFile zf = new ZipFile(zipFile, StandardCharsets.UTF_8)) {
            ZipEntry manifestEntry = zf.getEntry("_manifest.json");
            KnowledgePack pack;
            if (manifestEntry != null) {
                try (InputStream is = zf.getInputStream(manifestEntry)) {
                    String json = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))
                            .lines().collect(Collectors.joining("\n"));
                    pack = parseManifest(json, false);
                }
            } else {
                String name = zipFile.getName().replace(".zip", "").replace(".ZIP", "");
                pack = new KnowledgePack(name, name, "1.0", "unknown",
                        "External pack: " + name, "general", false);
            }
            packs.add(pack);

            var entries = zf.entries();
            while (entries.hasMoreElements()) {
                ZipEntry ze = entries.nextElement();
                if (ze.isDirectory() || !ze.getName().endsWith(".txt") || ze.getName().startsWith("_")) continue;
                String filename = ze.getName();
                if (filename.contains("/")) {
                    filename = filename.substring(filename.lastIndexOf('/') + 1);
                }
                try (InputStream is = zf.getInputStream(ze)) {
                    String content = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))
                            .lines().collect(Collectors.joining("\n"));
                    String key = pack.id + ":" + filename;
                    fileContents.put(key, content);
                    filePackMap.put(filename, pack.id);
                }
            }
        } catch (Exception e) {
            McChatbot.LOGGER.error("Failed to load zip pack: " + zipFile.getName(), e);
        }
    }

    private KnowledgePack readManifest(File file, boolean builtin) {
        if (!file.exists()) return null;
        try (Reader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String json = new BufferedReader(reader).lines().collect(Collectors.joining("\n"));
            return parseManifest(json, builtin);
        } catch (Exception e) {
            return null;
        }
    }

    private KnowledgePack parseManifest(String json, boolean builtin) {
        try {
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            return new KnowledgePack(
                    obj.has("id") ? obj.get("id").getAsString() : "unknown",
                    obj.has("name") ? obj.get("name").getAsString() : "Unknown",
                    obj.has("version") ? obj.get("version").getAsString() : "1.0",
                    obj.has("language") ? obj.get("language").getAsString() : "unknown",
                    obj.has("description") ? obj.get("description").getAsString() : "",
                    obj.has("category") ? obj.get("category").getAsString() : "general",
                    builtin
            );
        } catch (Exception e) {
            return null;
        }
    }

    public List<String> listFilesByPrefix(String prefix) {
        return fileContents.keySet().stream()
                .map(k -> k.contains(":") ? k.substring(k.indexOf(':') + 1) : k)
                .filter(p -> prefix == null || prefix.isBlank() || p.toLowerCase().startsWith(prefix.toLowerCase()))
                .sorted()
                .collect(Collectors.toList());
    }

    public List<String> listFilesByPack(String packId) {
        return fileContents.entrySet().stream()
                .filter(e -> {
                    String key = e.getKey();
                    if (packId == null || packId.isBlank()) return true;
                    return key.startsWith(packId + ":");
                })
                .map(e -> {
                    String key = e.getKey();
                    return key.contains(":") ? key.substring(key.indexOf(':') + 1) : key;
                })
                .sorted()
                .collect(Collectors.toList());
    }

    public String readByFilename(String filename) {
        // Try direct match first
        for (Map.Entry<String, String> entry : fileContents.entrySet()) {
            String key = entry.getKey();
            String fn = key.contains(":") ? key.substring(key.indexOf(':') + 1) : key;
            if (fn.equalsIgnoreCase(filename)) return entry.getValue();
        }
        // Try contains match
        for (Map.Entry<String, String> entry : fileContents.entrySet()) {
            String key = entry.getKey();
            String fn = key.contains(":") ? key.substring(key.indexOf(':') + 1) : key;
            if (fn.toLowerCase().contains(filename.toLowerCase())) return entry.getValue();
        }
        return null;
    }

    public String getIndex() {
        StringBuilder sb = new StringBuilder();
        for (KnowledgePack pack : packs) {
            long count = filePackMap.values().stream().filter(v -> v.equals(pack.id)).count();
            sb.append(String.format("[%s] %s v%s (%s, %d files) - %s\n",
                    pack.category, pack.name, pack.version, pack.language, count,
                    pack.builtin ? "built-in" : "external"));
        }
        return sb.toString().trim();
    }

    public boolean isLoaded() {
        return loaded;
    }
}
