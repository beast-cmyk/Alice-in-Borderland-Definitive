package com.deadmanshand;

import java.util.ArrayList;
import java.util.List;

/** What your phone shows on screen right now: a big role word (WOLF, LAMB...) and a few lines. */
public final class Hud {
    public String role = "";
    public int roleColor = 0xFFFFFF;
    public final List<String> lines = new ArrayList<>();

    public Hud line(String text) {
        if (lines.size() < 8) {
            lines.add(text);
        }
        return this;
    }

    public Hud role(String text, int color) {
        role = text;
        roleColor = color;
        return this;
    }

    /** "1:05" */
    public static String clock(int ticks) {
        int seconds = Math.max(0, (ticks + 19) / 20);
        return seconds / 60 + ":" + (seconds % 60 < 10 ? "0" : "") + seconds % 60;
    }
}
