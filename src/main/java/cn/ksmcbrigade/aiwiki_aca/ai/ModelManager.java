package cn.ksmcbrigade.aiwiki_aca.ai;

import com.google.gson.JsonParser;
import cn.ksmcbrigade.aiwiki_aca.Config;
import cn.ksmcbrigade.aiwiki_aca.McChatbot;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

public class ModelManager {
    private static final String BUILTIN_API_URL = "https://opencode.ai/zen/v1";
    private static final String BUILTIN_API_KEY = "public";
    private static ModelManager INSTANCE;

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private List<String> models = List.of();
    private Instant lastFetch = Instant.EPOCH;
    private final ConcurrentHashMap<String, Instant> rateLimitedModels = new ConcurrentHashMap<>();

    public static synchronized ModelManager getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new ModelManager();
        }
        return INSTANCE;
    }

    public synchronized List<String> getModels() {
        if (Instant.now().minusSeconds(86400).isAfter(lastFetch)) {
            refreshModels();
        }
        return models;
    }

    public synchronized void refreshModels() {
        List<String> fetched = new ArrayList<>();

        if (Config.USE_BUILTIN_API.get()) {
            fetchModels(BUILTIN_API_URL, BUILTIN_API_KEY, fetched);
        }

        String customModel = Config.CUSTOM_MODEL.get();
        if (customModel != null && !customModel.isBlank()) {
            if (!fetched.contains(customModel)) fetched.add(customModel);
        }

        if (!fetched.isEmpty()) {
            List<String> deepseekModels = new ArrayList<>();
            List<String> otherModels = new ArrayList<>();
            for (String id : fetched) {
                if (id.toLowerCase().contains("deepseek")) {
                    deepseekModels.add(id);
                } else {
                    otherModels.add(id);
                }
            }
            deepseekModels.sort(String::compareTo);
            otherModels.sort(String::compareTo);
            deepseekModels.addAll(otherModels);
            models = deepseekModels;
            lastFetch = Instant.now();
            McChatbot.LOGGER.info("Refreshed model list: {} models available", models.size());
        }
    }

    private void fetchModels(String baseUrl, String apiKey, List<String> out) {
        try {
            String modelsUrl = baseUrl.replaceAll("/chat/completions$", "");
            if (!modelsUrl.endsWith("/models")) {
                modelsUrl = modelsUrl + "/models";
            }

            var request = HttpRequest.newBuilder()
                    .uri(URI.create(modelsUrl))
                    .header("Authorization", "Bearer " + apiKey)
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();

            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                McChatbot.LOGGER.warn("Failed to fetch models from {}: {}", baseUrl, response.statusCode());
                return;
            }

            var json = JsonParser.parseString(response.body()).getAsJsonObject();
            var data = json.getAsJsonArray("data");
            if (data == null) return;

            for (var element : data) {
                String id = element.getAsJsonObject().get("id").getAsString();
                if (!id.endsWith("free")) continue;
                if (!out.contains(id)) {
                    out.add(id);
                }
            }
        } catch (Exception e) {
            McChatbot.LOGGER.warn("Failed to fetch models from {}", baseUrl, e);
        }
    }

    public void markRateLimited(String model) {
        rateLimitedModels.put(model, Instant.now().plusSeconds(86400));
    }

    public boolean isRateLimited(String model) {
        Instant expiry = rateLimitedModels.get(model);
        if (expiry == null) return false;
        if (Instant.now().isAfter(expiry)) {
            rateLimitedModels.remove(model);
            return false;
        }
        return true;
    }

    public void clearExpiredRateLimits() {
        Instant now = Instant.now();
        rateLimitedModels.entrySet().removeIf(entry -> now.isAfter(entry.getValue()));
    }
}
