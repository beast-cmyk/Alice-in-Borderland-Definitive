package com.deadmanshand.npc;

import com.deadmanshand.game.Game;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.random.Random;

/** What the other players say when there's no AI: short, plain, and different for each nature. */
public final class Lines {
    private Lines() {
    }

    public static String pick(Random random, String... lines) {
        return lines[random.nextInt(lines.length)];
    }

    /** An answer to {@code message} (already lower-case). */
    static String reply(Contestant who, ServerPlayerEntity player, String message) {
        Random random = who.getRandom();
        Game game = who.game();
        if (message.matches(".*\\b(hi|hello|hey|yo)\\b.*")) {
            return pick(random, "Hey. You're real, right? You're not one of them?", "Hi. I thought I was the only one left.",
                    "Keep your voice down.", "Oh thank god. Another person.");
        }
        if (message.matches(".*\\b(who are you|your name|name\\?)\\b.*")) {
            return "I'm " + who.name() + ". I was " + who.past + ". That doesn't mean much here.";
        }
        if (message.matches(".*\\b(rules?|how does|what do we do|what now)\\b.*")) {
            return game != null ? pick(random, "Check your phone, the rules are on it. Read them twice.", "Do exactly what the rules say. Nothing else.")
                    : pick(random, "Wait for a building to light up. Then you go in, or your visa runs out.", "You play, you win, you get days. That's all there is.");
        }
        if (message.matches(".*\\b(where|anyone|people|everyone)\\b.*")) {
            return pick(random, "Gone. Every single one of them.", "I've walked for hours. There's no one. Just us.",
                    "I don't know where they went. I don't think they went anywhere.");
        }
        if (message.matches(".*\\b(together|team|help|trust)\\b.*")) {
            return switch (who.nature) {
                case SELFISH -> "Sure. Until it's you or me.";
                case KIND -> "Yes. We stay together. Whatever happens.";
                case TIMID -> "Please don't leave me on my own.";
                default -> "Fine. Together. Don't make me regret it.";
            };
        }
        if (message.matches(".*\\b(scared|afraid|die|dying|dead)\\b.*")) {
            return pick(random, "Everyone's scared. The ones who aren't are already dead.", "Don't say that word. Not here.",
                    "I watched someone get hit by the laser yesterday. I can't stop seeing it.");
        }
        return switch (who.nature) {
            case BRAVE -> pick(random, "Stay sharp. We're getting out of this.", "Talk later. Move now.");
            case TIMID -> pick(random, "I can't think. I can't think straight.", "Please, can we just get through this?");
            case SELFISH -> pick(random, "Don't slow me down.", "Why should I care what you think?");
            case KIND -> pick(random, "Are you okay? Really?", "We'll get through this. We have to.");
            case CLEVER -> pick(random, "Wait. Let me think about that.", "There's always a trick. Always.");
            case RECKLESS -> pick(random, "Whatever. Let's just go.", "Thinking gets you killed. Moving keeps you alive.");
        };
    }

    /** Agreeing to do what they were asked ({@code what}: the option, in words). */
    public static String agree(Contestant who, String what) {
        Random random = who.getRandom();
        String doing = what == null ? "" : " " + what.replaceAll("\\s*\\(.*\\)", "") + ".";
        return switch (who.nature) {
            case TIMID -> pick(random, "Okay. Okay, I'll do it.", "If you say so...") + doing;
            case SELFISH -> pick(random, "Fine. But if this goes wrong, it's on you.", "Fine.") + doing;
            case CLEVER -> pick(random, "That fits. Alright.", "Yes, that's what I worked out too.") + doing;
            case RECKLESS -> pick(random, "Done. Watch me.", "Easy.") + doing;
            default -> pick(random, "Alright. I trust you.", "Okay. Together, then.") + doing;
        };
    }

    /** Not doing what they were asked. */
    public static String refuse(Contestant who) {
        Random random = who.getRandom();
        return switch (who.nature) {
            case SELFISH -> pick(random, "Why would I risk my neck on your word?", "You do it first.");
            case CLEVER -> pick(random, "No. I haven't worked it out yet.", "Not until I'm sure.");
            case BRAVE -> pick(random, "Not yet. Let me look first.", "I'll decide that myself.");
            default -> pick(random, "I... I don't know. Not yet.", "I'm not sure about that.");
        };
    }

    /** Seeing someone die. */
    public static String grief(Contestant who, String name, boolean close) {
        Random random = who.getRandom();
        if (close) {
            return switch (who.nature) {
                case SELFISH -> pick(random, "...Damn it. Damn it, " + name + ".", name + "... I told you to be careful.");
                case RECKLESS -> pick(random, "No! " + name + "! Who did this?!", "That's it. I'm ending this game.");
                default -> pick(random, name + "! No... no, no, no...", "Not " + name + ". Please, not " + name + ".", "I'm sorry, " + name + ". I'm so sorry.");
            };
        }
        return switch (who.nature) {
            case TIMID -> pick(random, "Oh god. Oh god, oh god...", "I want to go home. I just want to go home.");
            case BRAVE -> pick(random, "Keep moving. Don't look at it.", "Stay together. Nobody else dies.");
            case CLEVER -> pick(random, "Think. That's how it kills you. Remember it.", "Don't make the same mistake.");
            case SELFISH -> pick(random, "Better them than me.", "Don't look. Just don't look.");
            case RECKLESS -> pick(random, "Is that it? Is that all you've got?!", "Whatever. Let's go.");
            default -> pick(random, "They were alive a second ago...", "That poor person...", "Why is this happening?");
        };
    }
}
