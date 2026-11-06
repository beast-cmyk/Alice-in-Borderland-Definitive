package com.deadmanshand.city;

/**
 * The city's screens and signs: made-up brands and plain words in a blocky 5x7 font, drawn in coloured blocks. Some
 * are still lit, though nobody is left to read them.
 */
public final class Billboards {
    private record Design(String text, Mat back, Mat ink, Mat edge, boolean lit) {
    }

    private static final Design[] DESIGNS = {
            new Design("KUMO COLA", Mat.C_RED, Mat.C_WHITE, Mat.C_WHITE, true),
            new Design("NEKO BANK", Mat.C_BLUE, Mat.C_YELLOW, Mat.C_YELLOW, false),
            new Design("RAMEN 24H", Mat.C_ORANGE, Mat.C_BLACK, Mat.C_RED, true),
            new Design("KARAOKE", Mat.C_MAGENTA, Mat.C_WHITE, Mat.C_PINK, true),
            new Design("HOSHI MART", Mat.C_GREEN, Mat.C_WHITE, Mat.C_LIME, false),
            new Design("SALE 50%", Mat.C_YELLOW, Mat.C_RED, Mat.C_RED, false),
            new Design("GAME CENTER", Mat.C_PURPLE, Mat.C_CYAN, Mat.C_CYAN, true),
            new Design("HOTEL", Mat.C_BLACK, Mat.C_PINK, Mat.C_PINK, true),
            new Design("AOI DENKI", Mat.C_LIGHT_BLUE, Mat.C_WHITE, Mat.C_BLUE, false),
            new Design("IZAKAYA", Mat.C_RED, Mat.C_YELLOW, Mat.C_BLACK, true),
            new Design("SKY LINE", Mat.C_CYAN, Mat.C_BLUE, Mat.C_WHITE, false),
            new Design("CITY FM 88", Mat.C_BLACK, Mat.C_LIME, Mat.C_GREEN, true),
            new Design("SUSHI", Mat.C_WHITE, Mat.C_RED, Mat.C_BLACK, false),
            new Design("PACHINKO", Mat.C_PINK, Mat.C_PURPLE, Mat.C_MAGENTA, true),
            new Design("BEER", Mat.C_YELLOW, Mat.C_BLACK, Mat.C_ORANGE, false),
            new Design("SMILE!", Mat.C_WHITE, Mat.C_ORANGE, Mat.C_ORANGE, false),
            new Design("MIDORI TEA", Mat.C_LIME, Mat.C_GREEN, Mat.C_GREEN, false),
            new Design("TOMORROW", Mat.C_BLACK, Mat.C_WHITE, Mat.C_GRAY, true),
            new Design("DENTAL", Mat.C_WHITE, Mat.C_LIGHT_BLUE, Mat.C_BLUE, false),
            new Design("BOOKS", Mat.WALL_BROWN, Mat.C_WHITE, Mat.C_YELLOW, false),
            new Design("TAXI", Mat.C_YELLOW, Mat.C_BLACK, Mat.C_BLACK, false),
            new Design("LIVE", Mat.C_BLACK, Mat.C_RED, Mat.C_RED, true),
            new Design("NEWS", Mat.C_BLUE, Mat.C_WHITE, Mat.C_RED, true),
            new Design("CAFE", Mat.C_ORANGE, Mat.C_WHITE, Mat.C_WHITE, false),
    };

    /** 5x7 glyphs, one int per row, 5 bits (bit 4 is the leftmost column). */
    private static final int[][] FONT = new int[128][];

    static {
        glyph('A', 14, 17, 17, 31, 17, 17, 17);
        glyph('B', 30, 17, 17, 30, 17, 17, 30);
        glyph('C', 14, 17, 16, 16, 16, 17, 14);
        glyph('D', 30, 17, 17, 17, 17, 17, 30);
        glyph('E', 31, 16, 16, 30, 16, 16, 31);
        glyph('F', 31, 16, 16, 30, 16, 16, 16);
        glyph('G', 14, 17, 16, 23, 17, 17, 15);
        glyph('H', 17, 17, 17, 31, 17, 17, 17);
        glyph('I', 14, 4, 4, 4, 4, 4, 14);
        glyph('J', 7, 2, 2, 2, 2, 18, 12);
        glyph('K', 17, 18, 20, 24, 20, 18, 17);
        glyph('L', 16, 16, 16, 16, 16, 16, 31);
        glyph('M', 17, 27, 21, 21, 17, 17, 17);
        glyph('N', 17, 17, 25, 21, 19, 17, 17);
        glyph('O', 14, 17, 17, 17, 17, 17, 14);
        glyph('P', 30, 17, 17, 30, 16, 16, 16);
        glyph('Q', 14, 17, 17, 17, 21, 18, 13);
        glyph('R', 30, 17, 17, 30, 20, 18, 17);
        glyph('S', 15, 16, 16, 14, 1, 1, 30);
        glyph('T', 31, 4, 4, 4, 4, 4, 4);
        glyph('U', 17, 17, 17, 17, 17, 17, 14);
        glyph('V', 17, 17, 17, 17, 17, 10, 4);
        glyph('W', 17, 17, 17, 21, 21, 21, 10);
        glyph('X', 17, 17, 10, 4, 10, 17, 17);
        glyph('Y', 17, 17, 10, 4, 4, 4, 4);
        glyph('Z', 31, 1, 2, 4, 8, 16, 31);
        glyph('0', 14, 17, 19, 21, 25, 17, 14);
        glyph('1', 4, 12, 4, 4, 4, 4, 14);
        glyph('2', 14, 17, 1, 2, 4, 8, 31);
        glyph('3', 31, 2, 4, 2, 1, 17, 14);
        glyph('4', 2, 6, 10, 18, 31, 2, 2);
        glyph('5', 31, 16, 30, 1, 1, 17, 14);
        glyph('6', 6, 8, 16, 30, 17, 17, 14);
        glyph('7', 31, 1, 2, 4, 8, 8, 8);
        glyph('8', 14, 17, 17, 14, 17, 17, 14);
        glyph('9', 14, 17, 17, 15, 1, 2, 12);
        glyph('%', 25, 26, 2, 4, 8, 11, 19);
        glyph('!', 4, 4, 4, 4, 4, 0, 4);
        glyph('-', 0, 0, 0, 31, 0, 0, 0);
        glyph('.', 0, 0, 0, 0, 0, 12, 12);
        glyph(' ', 0, 0, 0, 0, 0, 0, 0);
    }

    private static void glyph(char c, int... rows) {
        FONT[c] = rows;
    }

    private Billboards() {
    }

    public static int count() {
        return DESIGNS.length;
    }

    /**
     * The block at {@code (u, v)} of a {@code width x height} board showing design {@code design} ({@code v} = 0 is
     * the top row). {@code bright}: a giant screen, lit all over.
     */
    public static Mat pixel(int design, int u, int v, int width, int height, boolean bright) {
        Design d = DESIGNS[Math.floorMod(design, DESIGNS.length)];
        boolean lit = d.lit || bright;
        if (u == 0 || v == 0 || u == width - 1 || v == height - 1) {
            return lit ? glow(d.edge) : d.edge;
        }
        String text = d.text;
        int chars = text.length();
        int textWidth = chars * 6 - 1;
        int scale = Math.max(1, Math.min((width - 4) / Math.max(1, textWidth), (height - 4) / 7));
        if (textWidth * scale > width - 2) {
            // Too long for one line at this size: just the first word
            int space = text.indexOf(' ');
            if (space > 0) {
                text = text.substring(0, space);
                chars = text.length();
                textWidth = chars * 6 - 1;
                scale = Math.max(1, Math.min((width - 4) / Math.max(1, textWidth), (height - 4) / 7));
            }
        }
        if (textWidth > width) {
            // No room for words at all: the brand's round logo and a stripe
            double cu = (width - 1) / 2.0;
            double cv = (height - 1) / 2.0;
            double radius = Math.min(width, height) / 3.0;
            double du = u - cu;
            double dv = v - cv;
            if (du * du + dv * dv <= radius * radius) {
                return lit ? glow(d.ink) : d.ink;
            }
            if (v == height - 3) {
                return d.edge;
            }
            return d.back;
        }
        int left = (width - textWidth * scale) / 2;
        int top = (height - 7 * scale) / 2;
        int tu = u - left;
        int tv = v - top;
        if (tu >= 0 && tv >= 0 && tu < textWidth * scale && tv < 7 * scale) {
            int cu = tu / scale;
            int index = cu / 6;
            int col = cu % 6;
            int row = tv / scale;
            if (index < chars && col < 5) {
                char c = Character.toUpperCase(text.charAt(index));
                int[] glyph = c < 128 ? FONT[c] : null;
                if (glyph != null && (glyph[row] >> (4 - col) & 1) != 0) {
                    return lit ? glow(d.ink) : d.ink;
                }
            }
        }
        // A stripe under the words, on the taller boards
        if (height >= 14 && v == top + 7 * scale + 1 && u > 2 && u < width - 3) {
            return d.edge;
        }
        return bright ? (Math.floorMod(u * 7 + v * 3, 11) == 0 ? glow(d.back) : d.back) : d.back;
    }

    /** The lit version of a colour, where there is one. */
    static Mat glow(Mat colour) {
        return switch (colour) {
            case C_WHITE, C_LIGHT_BLUE, C_CYAN, C_GRAY -> Mat.LIT_WHITE;
            case C_RED, C_ORANGE -> Mat.LIT_ORANGE;
            case C_YELLOW -> Mat.LIT_YELLOW;
            case C_LIME, C_GREEN -> Mat.LIT_GREEN;
            case C_PINK, C_MAGENTA -> Mat.LIT_PINK;
            case C_PURPLE, C_BLUE -> Mat.LIT_PURPLE;
            default -> colour;
        };
    }
}
