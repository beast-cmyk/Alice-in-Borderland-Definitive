package com.deadmanshand;

import net.fabricmc.loader.api.FabricLoader;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** {@code config/deadmanshand.properties}: when you cross over, how long your first visa is, and the AI voices. */
public final class Config {
    private static final String FILE = "deadmanshand.properties";
    public static final String DEFAULT_GROQ_MODELS = "openai/gpt-oss-120b, openai/gpt-oss-20b, qwen/qwen3.8-27b";

    /** auto (the fireworks come on their own) or command (only /borderland enter). */
    public static String crossing = "auto";
    public static int crossingAfterDays = 1;
    public static int arrivalVisaDays = 1;
    public static int returnAfterDays = 1;
    public static boolean permadeath;
    /** Games cleared before the face cards (the second stage) start for you. */
    public static int faceCardsAfter = 5;
    /** Face cards cleared before the last games (the third stage) start for you. */
    public static int finalAfter = 4;
    public static String groqKey = "";
    public static String groqModels = DEFAULT_GROQ_MODELS;
    public static String ollama = "backup";
    public static String ollamaModel = "gemma3:4b";
    public static String ollamaUrl = "http://localhost:11434";

    private static final String TEMPLATE = """
            # Dead Man's Hand
            #
            # When the fireworks come and you wake up in the emptied world:
            #   auto    = at midnight, once you've played this world for crossing_after_days days (0 = the very first night)
            #   command = only when you type /borderland enter (needs cheats on)
            crossing=%s
            crossing_after_days=%d
            # Days on your visa when you arrive
            arrival_visa_days=%d
            # After you die over there and wake up at home: days before the fireworks can come for you again
            return_after_days=%d
            # true: dying over there is final (you stay behind as a ghost, watching)
            permadeath=%s
            # Games you must clear before the face cards (the second stage) open for you
            face_cards_after=%d
            # Face cards you must clear before the last games (the third stage) open for you
            final_after=%d
            #
            # --- AI voices for the other players (optional) ---
            # FREE: Groq. Sign up at https://console.groq.com, open "API Keys", create a key, paste it after groq_api_key=
            # The models are tried in order; when one runs into its free limit, the next takes over.
            groq_api_key=%s
            groq_models=%s
            # FREE, on your own computer: Ollama (https://ollama.com). off, backup (when Groq can't answer) or main
            ollama=%s
            ollama_model=%s
            ollama_url=%s
            """;

    private Config() {
    }

    static void load() {
        Path dir = FabricLoader.getInstance().getConfigDir();
        Path path = dir.resolve(FILE);
        Properties p = new Properties();
        try {
            if (Files.exists(path)) {
                try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                    p.load(reader);
                }
            } else {
                // First run: borrow the AI settings from Possessed Grin if you have it, so your key works here too
                Path grin = dir.resolve("possessedgrin-ai.properties");
                if (Files.exists(grin)) {
                    Properties old = new Properties();
                    try (Reader reader = Files.newBufferedReader(grin, StandardCharsets.UTF_8)) {
                        old.load(reader);
                    }
                    for (String key : new String[]{"groq_api_key", "ollama", "ollama_model", "ollama_url"}) {
                        String value = old.getProperty(key, "").trim();
                        if (!value.isEmpty()) {
                            p.setProperty(key, value);
                        }
                    }
                }
            }
        } catch (Exception e) {
            DeadMansHand.LOG.warn("Dead Man's Hand: couldn't read {}: {}", path, e.toString());
        }
        crossing = p.getProperty("crossing", "auto").trim().toLowerCase();
        crossingAfterDays = number(p, "crossing_after_days", 1);
        arrivalVisaDays = Math.max(1, number(p, "arrival_visa_days", 1));
        returnAfterDays = number(p, "return_after_days", 1);
        permadeath = "true".equalsIgnoreCase(p.getProperty("permadeath", "false").trim());
        faceCardsAfter = number(p, "face_cards_after", 5);
        finalAfter = number(p, "final_after", 4);
        groqKey = p.getProperty("groq_api_key", "").trim();
        groqModels = p.getProperty("groq_models", DEFAULT_GROQ_MODELS).trim();
        ollama = p.getProperty("ollama", "backup").trim().toLowerCase();
        ollamaModel = p.getProperty("ollama_model", "gemma3:4b").trim();
        ollamaUrl = p.getProperty("ollama_url", "http://localhost:11434").trim().replaceAll("/+$", "");
        if (!Files.exists(path)) {
            try {
                Files.createDirectories(dir);
                try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
                    writer.write(String.format(TEMPLATE, crossing, crossingAfterDays, arrivalVisaDays, returnAfterDays, permadeath,
                            faceCardsAfter, finalAfter, groqKey, groqModels, ollama, ollamaModel, ollamaUrl));
                }
            } catch (Exception e) {
                DeadMansHand.LOG.warn("Dead Man's Hand: couldn't write {}: {}", path, e.toString());
            }
        }
    }

    private static int number(Properties p, String key, int fallback) {
        try {
            return Math.max(0, Integer.parseInt(p.getProperty(key, String.valueOf(fallback)).trim()));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
