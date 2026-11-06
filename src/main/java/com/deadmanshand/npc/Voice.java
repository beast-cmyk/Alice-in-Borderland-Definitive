package com.deadmanshand.npc;

import com.deadmanshand.Config;
import com.deadmanshand.DeadMansHand;
import com.deadmanshand.Visa;
import com.deadmanshand.game.Game;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.jetbrains.annotations.Nullable;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What the other players say, written by a real AI when one is set up (Groq's free models, or Ollama on your own
 * computer), otherwise by {@link Lines}. The game never waits for it. Server thread, except the HTTP callbacks, which
 * hop back onto it.
 */
public final class Voice {
    private static final URI GROQ = URI.create("https://api.groq.com/openai/v1/chat/completions");
    private static final Duration GROQ_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration OLLAMA_TIMEOUT = Duration.ofSeconds(45);
    private static final int CLOUD_PER_MINUTE = 24;
    private static final int OLLAMA_PER_MINUTE = 8;
    private static final Pattern TRY_AGAIN = Pattern.compile("try again in (?:(\\d+)m)?([\\d.]+)s");
    private static final Pattern REFUSAL = Pattern.compile("^(i'?m sorry|sorry, (but )?i can|i can'?t (help|assist|do)|i cannot|as an ai|i won'?t)",
            Pattern.CASE_INSENSITIVE);

    private static final Map<String, Long> RESTING = new HashMap<>();
    private static final Deque<Long> CLOUD_RECENT = new ArrayDeque<>();
    private static final Deque<Long> OLLAMA_RECENT = new ArrayDeque<>();
    private static final Set<UUID> BUSY = new HashSet<>();
    private static final Map<UUID, Deque<String[]>> TALK = new HashMap<>();
    private static boolean groqRejected;
    private static long ollamaDownUntil;
    private static int ollamaBusy;
    private static boolean toldKey;
    @Nullable
    private static MinecraftServer server;

    private Voice() {
    }

    public static void init() {
        ServerLifecycleEvents.SERVER_STARTED.register(started -> server = started);
        ServerLifecycleEvents.SERVER_STOPPED.register(stopped -> {
            server = null;
            BUSY.clear();
            TALK.clear();
            RESTING.clear();
            ollamaBusy = 0;
        });
    }

    private static List<String> models() {
        List<String> list = new ArrayList<>();
        if (Config.groqKey.isEmpty() || groqRejected) {
            return list;
        }
        for (String name : Config.groqModels.split(",")) {
            if (!name.isBlank()) {
                list.add(name.trim());
            }
        }
        return list;
    }

    // --- what they say -------------------------------------------------------------------------------------------

    /** A line for the moment: {@code moment} says what just happened; {@code fallback} is said if there's no AI. */
    public static void line(Contestant who, String moment, String fallback) {
        String user = "(" + moment + ") Say your next line, out loud, to the people around you.";
        if (!ask(who, null, user, false, reply -> act(who, reply != null ? reply : fallback))) {
            act(who, fallback);
        }
    }

    private static final Pattern TAG = Pattern.compile("\\[\\s*([A-Za-z][A-Za-z0-9 _-]{0,24})\\s*\\]");

    /**
     * Says {@code reply} and does what it says: a tag at the end ("[LEFT]") is a decision the game carries out, and a line
     * that plainly commits to something without the tag counts too. Returns what was said, without the tag.
     */
    static String act(Contestant who, String reply) {
        String tag = null;
        Matcher matcher = TAG.matcher(reply);
        while (matcher.find()) {
            tag = matcher.group(1).trim().toUpperCase(Locale.ROOT).replace(' ', '_');
        }
        String said = TAG.matcher(reply).replaceAll("").replaceAll("\\s+", " ").trim();
        if (!said.isEmpty()) {
            who.say(said);
        }
        Game game = who.game();
        if (game != null && game.isPlaying(who.getUuid())) {
            String choice = tag != null && game.offers(who, tag) ? tag : game.understood(who, said);
            if (choice != null && game.offers(who, choice)) {
                game.choose(who, choice);
            }
        }
        return said;
    }

    /** {@code player} said something to {@code who}. */
    public static void reply(Contestant who, ServerPlayerEntity player, String message) {
        String lower = message.toLowerCase(Locale.ROOT);
        if (lower.matches(".*\\b(thank|thanks|sorry|trust|together|help you|i'll help|we can do this|stay with me)\\b.*")) {
            who.trust(player.getUuid(), 0.08F);
        } else if (lower.matches(".*\\b(shut up|idiot|stupid|die|kill you|useless|coward)\\b.*")) {
            who.trust(player.getUuid(), -0.15F);
        }
        if (who.game() == null) {
            who.look(player);
        }
        who.talkedTo(player.getUuid(), player.world.getTime());
        // Asked to do something they can do: whether they will is up to them (and to how much they trust whoever asked)
        Game game = who.game();
        String asked = game != null && game.isPlaying(who.getUuid()) ? game.understood(who, lower) : null;
        boolean willing = asked != null && (who.trust(player.getUuid()) > 0.15F || who.nature == Contestant.Nature.TIMID
                || who.nature == Contestant.Nature.KIND || who.getRandom().nextFloat() < 0.35F);
        String fallback = asked != null ? (willing ? Lines.agree(who, game.allOptions(who).getOrDefault(asked,
                asked.replaceAll("_\\d+$", "_N").equals(asked) ? null : asked.toLowerCase(Locale.ROOT).replace('_', ' '))) + " [" + asked + "]" : Lines.refuse(who))
                : Lines.reply(who, player, lower);
        if (!ask(who, player, message, true, reply -> {
            String said = act(who, reply != null ? reply : fallback);
            remember(who, player, message, said);
        })) {
            String said = act(who, fallback);
            remember(who, player, message, said);
        }
    }

    private static void remember(Contestant who, ServerPlayerEntity player, String said, String answered) {
        Deque<String[]> talk = TALK.computeIfAbsent(who.getUuid(), id -> new ArrayDeque<>());
        talk.addLast(new String[]{player.getEntityName() + ": " + said, answered});
        while (talk.size() > 6) {
            talk.pollFirst();
        }
    }

    // --- asking ----------------------------------------------------------------------------------------------

    private static boolean ask(Contestant who, @Nullable ServerPlayerEntity player, String user, boolean priority, Consumer<String> done) {
        if (server == null || BUSY.contains(who.getUuid())) {
            return false;
        }
        long now = System.currentTimeMillis();
        prune(CLOUD_RECENT, now);
        prune(OLLAMA_RECENT, now);
        boolean ollamaFirst = "main".equals(Config.ollama);
        String model = ollamaFirst ? null : cloudModel(now, priority);
        boolean ollama = model == null && ollamaReady(now, priority);
        if (model == null && !ollama) {
            return false;
        }
        BUSY.add(who.getUuid());
        Consumer<String> finish = reply -> {
            BUSY.remove(who.getUuid());
            if (who.isAlive()) {
                done.accept(reply);
            }
        };
        if (model != null) {
            CLOUD_RECENT.addLast(now);
            groq(who, player, user, model, priority, finish);
        } else {
            OLLAMA_RECENT.addLast(now);
            ollama(who, player, user, finish);
        }
        return true;
    }

    private static void prune(Deque<Long> recent, long now) {
        while (!recent.isEmpty() && now - recent.peekFirst() > 60_000L) {
            recent.pollFirst();
        }
    }

    @Nullable
    private static String cloudModel(long now, boolean priority) {
        if (CLOUD_RECENT.size() >= (priority ? CLOUD_PER_MINUTE : CLOUD_PER_MINUTE - 6)) {
            return null;
        }
        for (String model : models()) {
            if (now >= RESTING.getOrDefault(model, 0L)) {
                return model;
            }
        }
        return null;
    }

    private static boolean ollamaReady(long now, boolean priority) {
        if ("off".equals(Config.ollama) || now < ollamaDownUntil) {
            return false;
        }
        return priority ? ollamaBusy < 2 : ollamaBusy == 0 && OLLAMA_RECENT.size() < OLLAMA_PER_MINUTE;
    }

    private static void groq(Contestant who, @Nullable ServerPlayerEntity player, String user, String model, boolean priority, Consumer<String> done) {
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.add("messages", messages(who, player, user, true));
        if (model.startsWith("openai/gpt-oss")) {
            body.addProperty("reasoning_effort", "low");
            body.addProperty("include_reasoning", false);
            body.addProperty("max_completion_tokens", 450);
        } else {
            if (model.startsWith("qwen/")) {
                body.addProperty("reasoning_effort", "none");
                body.addProperty("reasoning_format", "hidden");
            }
            body.addProperty("max_completion_tokens", 120);
            body.addProperty("temperature", 0.85);
        }
        Http.post(GROQ, new String[]{"authorization", "Bearer " + Config.groqKey}, body.toString(), GROQ_TIMEOUT, (status, text, retryAfter) -> {
            String reply = status == 200 ? clean(openAiText(text)) : null;
            back(() -> {
                if (reply != null && !reply.isEmpty() && !REFUSAL.matcher(reply).find()) {
                    done.accept(reply);
                    return;
                }
                long now = System.currentTimeMillis();
                if (status == 429) {
                    RESTING.put(model, now + rest(retryAfter, text));
                } else if ((status == 400 || status == 404 || status == 403) && text != null && text.toLowerCase(Locale.ROOT).contains("model")) {
                    RESTING.put(model, now + 24L * 3600L * 1000L);
                    DeadMansHand.LOG.warn("Dead Man's Hand: Groq won't run {} ({}); skipping it. Check groq_models in deadmanshand.properties", model, status);
                } else if (status == 401) {
                    groqRejected = true;
                    tellKeyRejected();
                } else if (status == 200) {
                    RESTING.put(model, now + 5L * 60L * 1000L);     // it refused: let the next model play the part
                } else {
                    RESTING.put(model, now + 20_000L);
                }
                // Straight on to the next model, or to Ollama
                String next = cloudModel(now, true);
                if (next != null) {
                    groq(who, player, user, next, priority, done);
                } else if (!"off".equals(Config.ollama) && ollamaReady(now, priority)) {
                    ollama(who, player, user, done);
                } else {
                    done.accept(null);
                }
            });
        });
    }

    /** How long a model rests after "too many requests": what Groq says, or a minute. */
    private static long rest(long retryAfterSeconds, @Nullable String text) {
        if (retryAfterSeconds > 0L) {
            return Math.min(retryAfterSeconds, 6L * 3600L) * 1000L + 500L;
        }
        if (text != null) {
            Matcher matcher = TRY_AGAIN.matcher(text);
            if (matcher.find()) {
                double seconds = (matcher.group(1) != null ? Integer.parseInt(matcher.group(1)) * 60 : 0) + Double.parseDouble(matcher.group(2));
                return (long) (seconds * 1000.0) + 500L;
            }
        }
        return 60_000L;
    }

    private static void tellKeyRejected() {
        if (toldKey || server == null) {
            return;
        }
        toldKey = true;
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            player.sendMessage(Text.literal("[Dead Man's Hand] Groq rejected your API key. Check groq_api_key in config/deadmanshand.properties.")
                    .formatted(Formatting.GRAY), false);
        }
    }

    private static void ollama(Contestant who, @Nullable ServerPlayerEntity player, String user, Consumer<String> done) {
        JsonObject body = new JsonObject();
        body.addProperty("model", Config.ollamaModel);
        body.add("messages", messages(who, player, user, false));
        body.addProperty("stream", false);
        body.addProperty("keep_alive", "30m");
        JsonObject options = new JsonObject();
        options.addProperty("num_predict", 70);
        options.addProperty("temperature", 0.85);
        options.addProperty("num_ctx", 2048);
        body.add("options", options);
        ollamaBusy++;
        URI uri;
        try {
            uri = URI.create(Config.ollamaUrl + "/api/chat");
        } catch (IllegalArgumentException e) {
            ollamaBusy--;
            ollamaDownUntil = Long.MAX_VALUE;
            done.accept(null);
            return;
        }
        Http.post(uri, new String[0], body.toString(), OLLAMA_TIMEOUT, (status, text, retryAfter) -> {
            String reply = status == 200 ? clean(ollamaText(text)) : null;
            back(() -> {
                ollamaBusy = Math.max(0, ollamaBusy - 1);
                if (status <= 0) {
                    ollamaDownUntil = System.currentTimeMillis() + 60_000L;     // not running: try again in a minute
                }
                done.accept(reply != null && !reply.isEmpty() && !REFUSAL.matcher(reply).find() ? reply : null);
            });
        });
    }

    private static void back(Runnable action) {
        MinecraftServer current = server;
        if (current != null) {
            current.execute(action);
        }
    }

    // --- the prompt ------------------------------------------------------------------------------------------

    private static final String WORLD = "A thriller. One night fireworks filled the sky; when they stopped, everyone in the world was gone except a "
            + "few strangers. The only way to stay alive is to play the deadly games that open at night, each marked by a playing card: "
            + "clubs are teamwork games, diamonds games of the mind, hearts games of betrayal, spades games of the body; the number says how "
            + "hard. Clearing one adds days to your visa. When your visa runs out, a red laser falls from the sky and kills you; losing a "
            + "game kills you the same way. You live in this world: never call it a show, a video game or Minecraft.";
    private static final String WORLD_SHORT = "A thriller. Everyone vanished one night; the few left must win deadly games marked by playing cards "
            + "to add days to their visa. If the visa runs out, or you lose, a laser from the sky kills you. You live in this world; never "
            + "call it a game show or Minecraft.";
    private static final String HOW = "How to talk: like a real, frightened person. One or two short sentences in plain everyday words, under "
            + "30 words. Answer exactly what was said to you. Let your feelings show. No quotation marks, no name label, no stage "
            + "directions, never mention AI. Dark talk is fine; never gory.";

    private static JsonArray messages(Contestant who, @Nullable ServerPlayerEntity player, String user, boolean rich) {
        JsonArray messages = new JsonArray();
        messages.add(message("system", system(who, player, rich)));
        if (player != null) {
            Deque<String[]> talk = TALK.get(who.getUuid());
            if (talk != null) {
                int skip = rich ? 0 : Math.max(0, talk.size() - 3);
                for (String[] exchange : talk) {
                    if (skip-- > 0) {
                        continue;
                    }
                    messages.add(message("user", exchange[0]));
                    messages.add(message("assistant", exchange[1]));
                }
            }
            messages.add(message("user", player.getEntityName() + ": " + clip(user, 240)));
        } else {
            messages.add(message("user", user));
        }
        return messages;
    }

    private static String system(Contestant who, @Nullable ServerPlayerEntity player, boolean rich) {
        StringBuilder prompt = new StringBuilder();
        prompt.append(rich ? WORLD : WORLD_SHORT).append("\n\n");
        prompt.append("You are ").append(who.name()).append(", ").append(who.years).append(". Before all this you were ").append(who.past)
                .append(". You are ").append(who.nature.text).append(". Your visa has ").append(Visa.describe(Math.max(0, who.visa)))
                .append(" left. Games you've survived: ").append(who.cleared).append(".\n");
        if (player != null) {
            float trust = who.trust(player.getUuid());
            prompt.append("You're talking to ").append(player.getEntityName()).append(", another player. ")
                    .append(trust > 0.4F ? "You trust them; they've been good to you." : trust < -0.2F ? "You don't trust them." : "You barely know them.")
                    .append('\n');
        }
        prompt.append("How you feel right now: ").append(who.feelings.describe()).append(" Let it show in how you talk.\n");
        int keep = rich ? 6 : 2;
        List<String> memories = new ArrayList<>();
        who.memories().forEach(memories::add);
        if (!memories.isEmpty()) {
            prompt.append("What you remember:\n");
            for (String memory : memories.subList(Math.max(0, memories.size() - keep), memories.size())) {
                prompt.append("- ").append(memory).append('\n');
            }
        }
        Game game = who.game();
        prompt.append(game != null ? "Right now: " + game.situation(who) : "Right now there's no game: you're in the empty streets, waiting for the next venue to light up.")
                .append("\n\n").append(HOW);
        Map<String, String> options = game != null && game.isPlaying(who.getUuid()) ? game.allOptions(who) : Map.of();
        if (!options.isEmpty()) {
            prompt.append("\n\nYou don't only talk: you act. If your line means you're doing one of these right now, or agreeing to do it, "
                    + "end the line with its tag in square brackets, exactly one tag, and you really will do it:\n");
            options.forEach((tag, meaning) -> prompt.append('[').append(tag).append("] ").append(meaning).append('\n'));
            prompt.append("If you're not doing any of them yet, use no tag. Never say you'll do something and leave its tag off, and never "
                    + "tag something you didn't say you'd do.");
        }
        return prompt.toString();
    }

    private static JsonObject message(String role, String content) {
        JsonObject object = new JsonObject();
        object.addProperty("role", role);
        object.addProperty("content", content);
        return object;
    }

    // --- reading answers -------------------------------------------------------------------------------------

    @Nullable
    private static String openAiText(@Nullable String json) {
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            JsonElement content = root.getAsJsonArray("choices").get(0).getAsJsonObject().getAsJsonObject("message").get("content");
            return content == null || content.isJsonNull() ? null : content.getAsString();
        } catch (RuntimeException e) {
            return null;
        }
    }

    @Nullable
    private static String ollamaText(@Nullable String json) {
        try {
            JsonElement content = JsonParser.parseString(json).getAsJsonObject().getAsJsonObject("message").get("content");
            return content == null || content.isJsonNull() ? null : content.getAsString();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** One line: no name label, no quotes, no thinking tags, no stage directions, not too long. */
    @Nullable
    static String clean(@Nullable String reply) {
        if (reply == null) {
            return null;
        }
        String line = reply.replaceAll("(?s)<think>.*?</think>", "").replace('\n', ' ').replace('\r', ' ').replaceAll("\\s+", " ").trim();
        line = line.replaceAll("\\*[^*]{0,60}\\*", "").replaceAll("\\([^)]{0,60}\\)", "").trim();
        int colon = line.indexOf(':');
        if (colon > 0 && colon < 20 && !line.substring(0, colon).contains(" ")) {
            line = line.substring(colon + 1).trim();
        }
        line = line.replace("\"", "").replace("“", "").replace("”", "").trim();
        return clip(line, 220);
    }

    private static String clip(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max - 3).trim() + "...";
    }

    /** Everything that touches Java's HTTP client. */
    private static final class Http {
        private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

        interface Done {
            void done(int status, @Nullable String body, long retryAfterSeconds);
        }

        static void post(URI uri, String[] headers, String json, Duration timeout, Done done) {
            HttpRequest.Builder request = HttpRequest.newBuilder(uri).timeout(timeout)
                    .header("content-type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8));
            for (int i = 0; i + 1 < headers.length; i += 2) {
                request.header(headers[i], headers[i + 1]);
            }
            CLIENT.sendAsync(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)).whenComplete((response, error) -> {
                if (response == null) {
                    done.done(-1, null, 0L);
                    return;
                }
                long retry = 0L;
                try {
                    retry = (long) Math.ceil(Double.parseDouble(response.headers().firstValue("retry-after").orElse("0")));
                } catch (NumberFormatException ignored) {
                    // not a number
                }
                done.done(response.statusCode(), response.body(), retry);
            });
        }
    }
}
