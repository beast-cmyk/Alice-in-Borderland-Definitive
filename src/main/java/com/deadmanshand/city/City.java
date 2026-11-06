package com.deadmanshand.city;

/**
 * The empty city: a Tokyo-like grid of roads and blocks, towers downtown, shops and apartments further out, parks, an
 * elevated railway, abandoned cars, vending machines still glowing, billboards for brands nobody will buy again, and
 * a great scramble crossing at the centre with giant screens on every corner. The venues stand on their own fixed
 * lots. Every block of it is worked out from its coordinates alone, one column at a time, so any chunk can be built
 * on its own and always comes out the same.
 */
public final class City {
    /** The first air block above the street. */
    public static final int STREET = 64;
    public static final int PAVE = STREET - 1;
    public static final int PITCH = 64;
    public static final int ROAD = 10;
    /** The scramble crossing: centred on (5, 5), this far each way. */
    public static final int PLAZA = 26;
    /** The railway runs along the road at this block row, raised on pillars. */
    private static final int RAIL_ROW = 2;
    private static final long SEED = 0x5EED_B0DE_2020L;

    /** Where each venue stands: its lot (nothing else is built there) and its entrance. */
    public record Lot(String id, int x0, int z0, int x1, int z1, int entranceX, int entranceZ) {
        public boolean contains(int x, int z) {
            return x >= x0 && x <= x1 && z >= z0 && z <= z1;
        }
    }

    public static final Lot[] LOTS = {
            new Lot("live_or_die", 28, -158, 46, -62, 37, -66),
            new Lot("tag", -173, 73, -123, 123, -166, 120),
            new Lot("wolf_and_lambs", 173, 9, 227, 63, 200, 60),
            new Lot("distance", -66, -259, -54, -247, -60, -250),
            new Lot("three_switches", -206, -106, -194, -94, -200, -100),
            new Lot("bingo", 78, -116, 122, -67, 100, -69),
            new Lot("human_elevator", -42, 82, -14, 114, -28, 112),
            new Lot("borrowing_race", 140, -116, 189, -67, 164, -69),
            new Lot("beast_hunt", -180, -52, -131, -3, -156, -5),
            new Lot("witch_hunt", 268, -52, 317, -3, 292, -5),
            new Lot("solitary_confinement", -116, -116, -67, -67, -92, -69),
            new Lot("balance_scale", 76, -52, 125, -3, 100, -5),
            new Lot("target", 204, -116, 253, -67, 228, -69),
            new Lot("bagworm", -244, 12, -195, 61, -220, 59),
            new Lot("champion", 140, 76, 189, 125, 164, 123),
            new Lot("checkmate", -308, -52, -259, -3, -284, -5),
            new Lot("osmosis", 204, -180, 253, -131, 228, -133),
            new Lot("silent", -180, -180, -131, -131, -156, -133),
            new Lot("croquet", 332, 12, 381, 61, 356, 59),
            new Lot("mahjong", -116, 140, -67, 189, -92, 187),
            new Lot("survival", -308, -180, -259, -131, -284, -133),
            new Lot("boiling_death", 12, 204, 61, 253, 36, 251),
            new Lot("parking", -308, 76, -259, 125, -284, 123),
            new Lot("old_maid", 76, 76, 125, 125, 100, 123),
            new Lot("sacred_fortunes", -52, 140, -3, 189, -28, 187),
            new Lot("zombie_hunt", 204, 76, 253, 125, 228, 123),
            new Lot("runaway_train", 268, -116, 317, -67, 292, -69),
            new Lot("bingo_tower", -244, -52, -195, -3, -220, -5),
            new Lot("kick_the_can", 76, 140, 125, 189, 100, 187),
            new Lot("future_sugoroku", -180, 140, -131, 189, -156, 187),
            new Lot("one_last_game", 12, -244, 61, -195, 36, -197),
    };

    public interface Sink {
        void set(int y, Mat mat);
    }

    private enum Layout {TOWER, QUAD, SPLIT_X, SPLIT_Z, PARK, PARKING, OPEN, CORNER}

    private record Building(int x0, int z0, int x1, int z1, int height, Mat wall, Mat glass, float lit, int style, long seed,
                            int board, int boardSide, boolean bigBoard, int setback, int roofBoard) {
    }

    private static final Mat[] WALLS = {Mat.WALL_WHITE, Mat.WALL_LIGHT, Mat.WALL_GRAY, Mat.WALL_DARK, Mat.WALL_QUARTZ, Mat.WALL_BRICK,
            Mat.WALL_SAND, Mat.WALL_TERRA, Mat.WALL_CYAN, Mat.WALL_BROWN, Mat.WALL_STONE, Mat.WALL_LIGHT, Mat.WALL_WHITE, Mat.WALL_GRAY};
    private static final Mat[] GLASSES = {Mat.GLASS_DARK, Mat.GLASS_BLACK, Mat.GLASS_BLUE, Mat.GLASS_CYAN, Mat.GLASS_DARK, Mat.GLASS};
    private static final Mat[] CAR_COLOURS = {Mat.C_WHITE, Mat.C_WHITE, Mat.C_BLACK, Mat.C_GRAY, Mat.C_RED, Mat.C_BLUE, Mat.C_YELLOW,
            Mat.C_LIGHT_BLUE, Mat.C_GREEN, Mat.C_WHITE};
    private static final Mat[] SIGN_COLOURS = {Mat.C_RED, Mat.C_ORANGE, Mat.C_YELLOW, Mat.C_GREEN, Mat.C_BLUE, Mat.C_PURPLE, Mat.C_MAGENTA,
            Mat.C_PINK, Mat.C_CYAN, Mat.C_WHITE};

    private City() {
    }

    // --- randomness that never changes ---------------------------------------------------------------------------

    static long hash(long a, long b, long c) {
        long h = a * 0x9E3779B97F4A7C15L ^ b * 0xC2B2AE3D27D4EB4FL ^ c * 0x165667B19E3779F9L ^ SEED;
        h ^= h >>> 31;
        h *= 0xBF58476D1CE4E5B9L;
        h ^= h >>> 29;
        h *= 0x94D049BB133111EBL;
        h ^= h >>> 32;
        return h;
    }

    static int pick(long h, int n) {
        return (int) Math.floorMod(h, (long) n);
    }

    static float unit(long h) {
        return (h >>> 40) / (float) (1L << 24);
    }

    // --- the plan ----------------------------------------------------------------------------------------------

    public static Lot lot(String id) {
        for (Lot lot : LOTS) {
            if (lot.id.equals(id)) {
                return lot;
            }
        }
        throw new IllegalArgumentException(id);
    }

    private static Lot lotAt(int x, int z) {
        for (Lot lot : LOTS) {
            if (lot.contains(x, z)) {
                return lot;
            }
        }
        return null;
    }

    private static boolean blockHasLot(int bx, int bz) {
        int x0 = bx * PITCH + ROAD - 1;
        int z0 = bz * PITCH + ROAD - 1;
        int x1 = bx * PITCH + PITCH;
        int z1 = bz * PITCH + PITCH;
        for (Lot lot : LOTS) {
            if (lot.x0 <= x1 && lot.x1 >= x0 && lot.z0 <= z1 && lot.z1 >= z0) {
                return true;
            }
        }
        return false;
    }

    public static boolean inPlaza(int x, int z) {
        return Math.abs(x - 5) <= PLAZA && Math.abs(z - 5) <= PLAZA;
    }

    /** 0 downtown, 1 the middle ring, 2 the outskirts. */
    static int district(int x, int z) {
        int d = Math.max(Math.abs(x), Math.abs(z));
        return d < 230 ? 0 : d < 520 ? 1 : 2;
    }

    private static Layout layout(int bx, int bz) {
        if (blockHasLot(bx, bz)) {
            return Layout.OPEN;
        }
        if ((bx == 0 || bx == -1) && (bz == 0 || bz == -1)) {
            return Layout.CORNER;
        }
        float r = unit(hash(bx, bz, 1));
        return switch (district(bx * PITCH + 37, bz * PITCH + 37)) {
            case 0 -> r < 0.45F ? Layout.TOWER : r < 0.84F ? Layout.QUAD : r < 0.92F ? (r < 0.88F ? Layout.SPLIT_X : Layout.SPLIT_Z)
                    : r < 0.96F ? Layout.PARKING : Layout.PARK;
            case 1 -> r < 0.2F ? Layout.TOWER : r < 0.68F ? Layout.QUAD : r < 0.8F ? (r < 0.74F ? Layout.SPLIT_X : Layout.SPLIT_Z)
                    : r < 0.88F ? Layout.PARKING : Layout.PARK;
            default -> r < 0.05F ? Layout.TOWER : r < 0.6F ? Layout.QUAD : r < 0.7F ? Layout.SPLIT_X : r < 0.78F ? Layout.PARKING : Layout.PARK;
        };
    }

    /** Where you wake up: the middle of the crossing. */
    public static int[] arrival(long seed) {
        return new int[]{5 + pick(seed, 17) - 8, 5 + pick(seed >>> 8, 17) - 8};
    }

    /** A spot on a pavement near (x, z), or null if it lands somewhere odd. */
    public static int[] pavement(int x, int z, long seed) {
        int bx = Math.floorDiv(x, PITCH);
        int bz = Math.floorDiv(z, PITCH);
        boolean onX = (seed & 1L) == 0L;
        int along = 14 + pick(seed >>> 4, 46);
        int side = (seed & 2L) == 0L ? 11 : 62;
        int px = onX ? bx * PITCH + along : bx * PITCH + side;
        int pz = onX ? bz * PITCH + side : bz * PITCH + along;
        if (lotAt(px, pz) != null || inPlaza(px, pz)) {
            return null;
        }
        return new int[]{px, pz};
    }

    // --- one column ----------------------------------------------------------------------------------------

    public static void column(int x, int z, Sink sink) {
        sink.set(-64, Mat.BEDROCK);
        for (int y = -63; y < PAVE; y++) {
            sink.set(y, Mat.STONE);
        }
        if (lotAt(x, z) != null) {
            sink.set(PAVE, Mat.PLAZA);
            return;
        }
        int lx = Math.floorMod(x, PITCH);
        int lz = Math.floorMod(z, PITCH);
        int bx = Math.floorDiv(x, PITCH);
        int bz = Math.floorDiv(z, PITCH);
        Layout layout = layout(bx, bz);
        if (layout == Layout.CORNER) {
            Building tower = cornerTower(bx, bz);
            if (inside(tower, x, z)) {
                building(x, z, tower, sink);
                return;
            }
        }
        if (inPlaza(x, z)) {
            plaza(x, z, sink);
            return;
        }
        if (lx < ROAD || lz < ROAD) {
            if (layout == Layout.OPEN && lotNearRoad(x, z)) {
                sink.set(PAVE, Mat.PLAZA);
                return;
            }
            road(x, z, lx, lz, sink);
            return;
        }
        if (lx < 12 || lx > 61 || lz < 12 || lz > 61) {
            sidewalk(x, z, lx, lz, sink);
            return;
        }
        switch (layout) {
            case TOWER -> {
                Building b = tower(bx, bz);
                if (inside(b, x, z)) {
                    building(x, z, b, sink);
                } else {
                    forecourt(x, z, sink);
                }
            }
            case QUAD -> {
                int sx = lx < 37 ? 0 : 1;
                int sz = lz < 37 ? 0 : 1;
                if (lx == 36 || lx == 37 || lz == 36 || lz == 37) {
                    alley(x, z, sink);
                    return;
                }
                Building b = sub(bx, bz, sx, sz, sx == 0 ? 12 : 38, sz == 0 ? 12 : 38, sx == 0 ? 35 : 61, sz == 0 ? 35 : 61);
                building(x, z, b, sink);
            }
            case SPLIT_X -> {
                if (lx == 36 || lx == 37) {
                    alley(x, z, sink);
                    return;
                }
                int sx = lx < 37 ? 0 : 1;
                building(x, z, sub(bx, bz, sx, 2, sx == 0 ? 12 : 38, 12, sx == 0 ? 35 : 61, 61), sink);
            }
            case SPLIT_Z -> {
                if (lz == 36 || lz == 37) {
                    alley(x, z, sink);
                    return;
                }
                int sz = lz < 37 ? 0 : 1;
                building(x, z, sub(bx, bz, 3, sz, 12, sz == 0 ? 12 : 38, 61, sz == 0 ? 35 : 61), sink);
            }
            case PARK -> park(x, z, lx, lz, hash(bx, bz, 9), sink);
            case PARKING -> parking(x, z, lx, lz, bx, bz, sink);
            case OPEN, CORNER -> forecourt(x, z, sink);
        }
    }

    /** Roads that run into a venue's lot stop short of it. */
    private static boolean lotNearRoad(int x, int z) {
        for (Lot lot : LOTS) {
            if (x >= lot.x0 - 1 && x <= lot.x1 + 1 && z >= lot.z0 - 1 && z <= lot.z1 + 1) {
                return true;
            }
        }
        return false;
    }

    private static boolean inside(Building b, int x, int z) {
        return x >= b.x0 && x <= b.x1 && z >= b.z0 && z <= b.z1;
    }

    // --- buildings ---------------------------------------------------------------------------------------------

    private static Building tower(int bx, int bz) {
        long h = hash(bx, bz, 2);
        int district = district(bx * PITCH + 37, bz * PITCH + 37);
        int margin = pick(h, 4);
        int height = switch (district) {
            case 0 -> 48 + 4 * pick(h >>> 8, 18);
            case 1 -> 32 + 4 * pick(h >>> 8, 10);
            default -> 20 + 4 * pick(h >>> 8, 6);
        };
        int x0 = bx * PITCH + 12 + margin;
        int z0 = bz * PITCH + 12 + margin;
        int setback = height >= 64 && unit(h >>> 3) < 0.6F ? (height * 3 / 5) / 4 * 4 : 0;
        boolean board = district < 2 && unit(h >>> 5) < 0.55F;
        return new Building(x0, z0, bx * PITCH + 61 - margin, bz * PITCH + 61 - margin, height, WALLS[pick(h >>> 12, WALLS.length)],
                GLASSES[pick(h >>> 16, GLASSES.length)], district == 0 ? 0.05F : 0.03F, pick(h >>> 20, 3), h,
                board ? pick(h >>> 24, Billboards.count()) : -1, pick(h >>> 28, 4), false, setback,
                district < 2 && unit(h >>> 36) < 0.3F ? pick(h >>> 40, Billboards.count()) : -1);
    }

    private static Building sub(int bx, int bz, int sx, int sz, int lx0, int lz0, int lx1, int lz1) {
        long h = hash(bx * 4L + sx, bz * 4L + sz, 3);
        int district = district(bx * PITCH + 37, bz * PITCH + 37);
        int height = switch (district) {
            case 0 -> 16 + 4 * pick(h >>> 8, 12);
            case 1 -> 12 + 4 * pick(h >>> 8, 8);
            default -> 8 + 4 * pick(h >>> 8, 5);
        };
        int style = district == 2 ? 3 : pick(h >>> 20, 4);
        boolean board = district < 2 && unit(h >>> 5) < 0.35F;
        return new Building(bx * PITCH + lx0, bz * PITCH + lz0, bx * PITCH + lx1, bz * PITCH + lz1, height, WALLS[pick(h >>> 12, WALLS.length)],
                GLASSES[pick(h >>> 16, GLASSES.length)], district == 0 ? 0.04F : 0.025F, style, h,
                board ? pick(h >>> 24, Billboards.count()) : -1, pick(h >>> 28, 4), false, 0,
                height <= 32 && unit(h >>> 36) < 0.25F ? pick(h >>> 40, Billboards.count()) : -1);
    }

    /** The four towers around the crossing, each with a giant screen facing it. */
    private static Building cornerTower(int bx, int bz) {
        long h = hash(bx, bz, 4);
        int x0 = bx == 0 ? 34 : -52;
        int x1 = bx == 0 ? 61 : -24;
        int z0 = bz == 0 ? 34 : -52;
        int z1 = bz == 0 ? 61 : -24;
        int side = bz == 0 ? 0 : 1;     // the side facing the crossing: north for the southern towers, south for the northern
        return new Building(x0, z0, x1, z1, 72 + 4 * pick(h, 8), bx == 0 ? Mat.WALL_DARK : Mat.WALL_LIGHT, Mat.GLASS_BLACK, 0.04F, 1, h,
                pick(h >>> 8, Billboards.count()), side, true, 0, -1);
    }

    private static void building(int x, int z, Building b, Sink sink) {
        sink.set(PAVE, Mat.FLOOR);
        boolean upper = b.setback > 0 && x >= b.x0 + 4 && x <= b.x1 - 4 && z >= b.z0 + 4 && z <= b.z1 - 4;
        int lowTop = b.setback > 0 ? b.setback : b.height;
        shell(x, z, b.x0, b.z0, b.x1, b.z1, 0, lowTop, b, sink, !upper);
        if (upper) {
            shell(x, z, b.x0 + 4, b.z0 + 4, b.x1 - 4, b.z1 - 4, lowTop, b.height, b, sink, true);
        }
    }

    private static void shell(int x, int z, int x0, int z0, int x1, int z1, int from, int to, Building b, Sink sink, boolean roof) {
        boolean west = x == x0;
        boolean east = x == x1;
        boolean north = z == z0;
        boolean south = z == z1;
        boolean edge = west || east || north || south;
        boolean corner = (west || east) && (north || south);
        int side = north ? 0 : south ? 1 : west ? 2 : east ? 3 : -1;
        int along = north || south ? x - x0 : z - z0;
        int length = north || south ? x1 - x0 : z1 - z0;
        boolean sign = edge && !corner && along == 2 && unit(b.seed >>> 44) < 0.5F && side == pick(b.seed >>> 46, 4);
        int signLength = 8 + pick(b.seed >>> 50, 10);
        for (int ly = from; ly < to; ly++) {
            int y = STREET + ly;
            Mat mat = null;
            if (!edge) {
                if (ly > 0 && ly % 4 == 0) {
                    mat = Mat.FLOOR;
                }
            } else if (corner) {
                mat = b.wall;
            } else {
                boolean pillar = along % 4 == 0;
                if (ly < 4 && from == 0) {
                    boolean door = (along == length / 2 || along == length / 2 + 1) && ly < 2;
                    if (door) {
                        mat = null;
                    } else if (ly == 3) {
                        mat = band(b, side, along);
                    } else {
                        mat = pillar ? b.wall : Mat.GLASS;
                    }
                } else {
                    int row = ly % 4;
                    boolean window = switch (b.style) {
                        case 1 -> row != 0;
                        case 2 -> row == 2;
                        default -> row == 1 || row == 2;
                    };
                    mat = pillar || !window ? b.wall : window(b, x, ly, z);
                    if (b.style == 3 && row == 3 && !pillar && (side == 1 || side == 2)) {
                        mat = Mat.BARS;     // balconies
                    }
                }
                if (sign && ly >= 5 && ly < 5 + signLength) {
                    int k = ly - 5;
                    Mat colour = SIGN_COLOURS[pick(b.seed >>> (k % 3 == 0 ? 52 : 56), SIGN_COLOURS.length)];
                    mat = k % 2 == 0 && unit(b.seed >>> 30) < 0.5F ? Billboards.glow(colour) : colour;
                }
                if (b.board >= 0 && side == b.boardSide) {
                    int width = b.bigBoard ? length - 3 : Math.min(length - 3, 34);
                    int height = b.bigBoard ? Math.max(10, width * 2 / 3) : Math.max(8, width / 2);
                    int a0 = (length - width) / 2;
                    int b0 = b.bigBoard ? 6 : Math.max(6, (to - height) / 2);
                    if (along >= a0 && along < a0 + width && ly >= b0 && ly < b0 + height && b0 + height < to) {
                        int u = along - a0;
                        boolean flip = side == 0 || side == 3;
                        mat = Billboards.pixel(b.board, flip ? width - 1 - u : u, b0 + height - 1 - ly, width, height, b.bigBoard);
                    }
                }
            }
            if (mat != null) {
                sink.set(y, mat);
            }
        }
        if (!roof) {
            return;
        }
        int y = STREET + to;
        if (edge) {
            sink.set(y, b.wall);
            sink.set(y + 1, b.wall);
            return;
        }
        sink.set(y, Mat.ROOF);
        int rx = x - x0;
        int rz = z - z0;
        // Air-conditioning units
        if (rx % 7 >= 3 && rx % 7 <= 4 && rz % 7 >= 3 && rz % 7 <= 4 && unit(hash(x0 + rx / 7, z0 + rz / 7, 21)) < 0.5F) {
            sink.set(y + 1, Mat.METAL);
            sink.set(y + 2, Mat.METAL);
        }
        // A water tank on some
        int tx = x0 + 5;
        int tz = z1 - 5;
        if (unit(b.seed >>> 33) < 0.4F && (x - tx) * (x - tx) + (z - tz) * (z - tz) <= 4) {
            sink.set(y + 1, Mat.METAL);
            for (int k = 2; k <= 5; k++) {
                sink.set(y + k, Mat.WALL_GRAY);
            }
        }
        // An aerial with a red light on the tall ones
        if (to >= 60 && x == (x0 + x1) / 2 && z == (z0 + z1) / 2) {
            for (int k = 1; k <= 10; k++) {
                sink.set(y + k, Mat.POLE);
            }
            sink.set(y + 11, Mat.LIT_ORANGE);
        }
        // A billboard on the roof, facing the street
        if (b.roofBoard >= 0 && x > x0 + 2 && x < x1 - 2 && z == z0 + 3) {
            // the back of the roof billboard: plain panel, so it never reads backwards from behind
            int width = x1 - x0 - 5;
            int height = Math.max(7, Math.min(12, width / 3));
            for (int k = 3; k < 3 + height; k++) {
                sink.set(y + k, Mat.WALL_GRAY);
            }
        }
        if (b.roofBoard >= 0 && x > x0 + 2 && x < x1 - 2 && z == z0 + 2) {
            int width = x1 - x0 - 5;
            int height = Math.max(7, Math.min(12, width / 3));
            int u = x - x0 - 3;
            if ((u % 4) == 0) {
                sink.set(y + 1, Mat.METAL);
                sink.set(y + 2, Mat.METAL);
            }
            for (int v = 0; v < height; v++) {
                sink.set(y + 3 + height - 1 - v, Billboards.pixel(b.roofBoard, width - 1 - u, v, width, height, false));
            }
        }
    }

    private static Mat window(Building b, int x, int ly, int z) {
        float r = unit(hash(x, ly, z));
        if (r < b.lit) {
            return r < b.lit * 0.3F ? Mat.LIT_WHITE : Mat.LIT_WARM;
        }
        return b.glass;
    }

    /** The shop sign over the ground floor. */
    private static Mat band(Building b, int side, int along) {
        long h = hash(b.seed, side, 5);
        Mat colour = SIGN_COLOURS[pick(h, SIGN_COLOURS.length)];
        if (unit(h >>> 8) < 0.35F && along % 3 == 1) {
            return Billboards.glow(colour);
        }
        return unit(h >>> 16) < 0.3F ? b.wall : colour;
    }

    // --- streets -----------------------------------------------------------------------------------------------

    private static void road(int x, int z, int lx, int lz, Sink sink) {
        boolean alongX = lz < ROAD && lx >= ROAD;
        boolean alongZ = lx < ROAD && lz >= ROAD;
        Mat top = Mat.ASPHALT;
        if (alongX) {
            if ((lx >= 11 && lx <= 14) || (lx >= 59 && lx <= 62)) {
                top = lz % 2 == 0 ? Mat.MARK : Mat.ASPHALT;
            } else if (lz == 4) {
                top = Mat.MARK_YELLOW;
            } else if ((lz == 2 || lz == 7) && Math.floorMod(x, 8) < 4) {
                top = Mat.MARK;
            }
        } else if (alongZ) {
            if ((lz >= 11 && lz <= 14) || (lz >= 59 && lz <= 62)) {
                top = lx % 2 == 0 ? Mat.MARK : Mat.ASPHALT;
            } else if (lx == 4) {
                top = Mat.MARK_YELLOW;
            } else if ((lx == 2 || lx == 7) && Math.floorMod(z, 8) < 4) {
                top = Mat.MARK;
            }
        }
        sink.set(PAVE, top);
        if (alongX && lx >= 16 && lx <= 56) {
            car(x, z, lz, true, Math.floorDiv(z, PITCH), sink, 0.16F);
        } else if (alongZ && lz >= 16 && lz <= 56) {
            car(z, x, lx, false, Math.floorDiv(x, PITCH), sink, 0.16F);
        }
        rail(x, z, sink);
    }

    /**
     * An abandoned car, if one is parked here: {@code along} runs with the road, {@code across} is 0-9 across it.
     */
    private static void car(int along, int other, int across, boolean alongX, int row, Sink sink, float chance) {
        int lane = across >= 1 && across <= 2 ? 0 : across >= 6 && across <= 7 ? 1 : -1;
        if (lane < 0) {
            return;
        }
        int slot = Math.floorDiv(along, 8);
        long h = hash(slot, row * 3L + lane + (alongX ? 0 : 1000), 7);
        if (unit(h) >= chance) {
            return;
        }
        int cx = along - slot * 8 - 2;
        if (cx < 0 || cx > 3) {
            return;
        }
        Mat body = CAR_COLOURS[pick(h >>> 8, CAR_COLOURS.length)];
        boolean end = cx == 0 || cx == 3;
        sink.set(STREET, end ? Mat.TIRE : body);
        sink.set(STREET + 1, end ? body : Mat.CAR_GLASS);
        if (!end) {
            sink.set(STREET + 2, body);
        }
    }

    private static void rail(int x, int z, Sink sink) {
        int lz = z - RAIL_ROW * PITCH;
        if (lz < 2 || lz > 7) {
            return;
        }
        if ((lz == 4 || lz == 5) && Math.floorMod(x, 16) <= 1) {
            for (int y = STREET; y < STREET + 11; y++) {
                sink.set(y, Mat.WALL_GRAY);
            }
        }
        sink.set(STREET + 11, Mat.DECK);
        if (lz == 3 || lz == 6) {
            sink.set(STREET + 12, Mat.RAIL);
        } else if (lz == 2 || lz == 7) {
            sink.set(STREET + 12, Mat.BARS);
        }
    }

    private static void sidewalk(int x, int z, int lx, int lz, Sink sink) {
        boolean curb = lx == 10 || lx == 63 || lz == 10 || lz == 63;
        boolean corner = (lx <= 11 || lx >= 62) && (lz <= 11 || lz >= 62);
        sink.set(PAVE, curb ? Mat.CURB : Mat.SIDEWALK);
        if (corner) {
            if ((lx == 10 || lx == 63) && (lz == 10 || lz == 63)) {
                // Traffic lights, dark
                for (int y = STREET; y < STREET + 4; y++) {
                    sink.set(y, Mat.POLE);
                }
                sink.set(STREET + 4, Mat.C_BLACK);
                sink.set(STREET + 5, unit(hash(x, z, 11)) < 0.2F ? Mat.LIT_ORANGE : Mat.C_BLACK);
            }
            return;
        }
        int along = lx == 10 || lx == 11 || lx == 62 || lx == 63 ? z : x;
        if (curb && Math.floorMod(along, 16) == 6) {
            for (int y = STREET; y < STREET + 5; y++) {
                sink.set(y, Mat.POLE);
            }
            sink.set(STREET + 5, unit(hash(x, z, 12)) < 0.55F ? Mat.LIT_WHITE : Mat.C_GRAY);
        } else if (!curb && Math.floorMod(along, 23) == 11 && unit(hash(x, z, 13)) < 0.45F) {
            // A vending machine, still humming
            sink.set(STREET, SIGN_COLOURS[pick(hash(x, z, 14), 4)]);
            sink.set(STREET + 1, Mat.LIT_WHITE);
        }
    }

    private static void alley(int x, int z, Sink sink) {
        sink.set(PAVE, Mat.TILE);
        if (unit(hash(x, z, 15)) < 0.04F) {
            sink.set(STREET, Mat.C_GREEN);      // a bin
        }
    }

    private static void forecourt(int x, int z, Sink sink) {
        sink.set(PAVE, (x + z & 1) == 0 ? Mat.TILE : Mat.PLAZA);
        if (Math.floorMod(x, 9) == 4 && Math.floorMod(z, 9) == 4) {
            sink.set(STREET, Mat.HEDGE);
        }
    }

    /** The scramble crossing: zebra stripes every way, even corner to corner. */
    private static void plaza(int x, int z, Sink sink) {
        int dx = x - 5;
        int dz = z - 5;
        int ax = Math.abs(dx);
        int az = Math.abs(dz);
        Mat top = Mat.ASPHALT;
        if (ax > 22 && az > 22) {
            top = Mat.SIDEWALK;
        } else if (az >= 17 && az <= 21 && ax <= 15) {
            top = Math.floorMod(x, 2) == 0 ? Mat.MARK : Mat.ASPHALT;
        } else if (ax >= 17 && ax <= 21 && az <= 15) {
            top = Math.floorMod(z, 2) == 0 ? Mat.MARK : Mat.ASPHALT;
        } else if (ax <= 15 && az <= 15 && (Math.abs(dx - dz) <= 1 || Math.abs(dx + dz) <= 1)) {
            top = Math.floorMod(x + z, 2) == 0 ? Mat.MARK : Mat.ASPHALT;
        }
        sink.set(PAVE, top);
        if (ax > 22 && az > 22 && (ax == 24 || az == 24) && Math.floorMod(x + z, 6) == 0) {
            for (int y = STREET; y < STREET + 5; y++) {
                sink.set(y, Mat.POLE);
            }
            sink.set(STREET + 5, Mat.LIT_WHITE);
        }
        // A few cars abandoned where they stopped
        if (ax > 15 || az > 15) {
            car(x, z, Math.floorMod(z, 10), true, 900, sink, 0.06F);
        }
    }

    // --- greenery and parking ----------------------------------------------------------------------------------

    private static void park(int x, int z, int lx, int lz, long seed, Sink sink) {
        boolean path = Math.abs(lx - 36) <= 1 || Math.abs(lz - 36) <= 1;
        int pondX = seed % 2 == 0 ? 24 : 48;
        int pondZ = (seed >>> 1) % 2 == 0 ? 24 : 48;
        int px = lx - pondX;
        int pz = lz - pondZ;
        boolean pond = unit(seed >>> 4) < 0.5F && px * px + pz * pz * 2 < 50;
        if (pond) {
            sink.set(PAVE - 1, Mat.SAND);
            sink.set(PAVE, Mat.WATER);
            return;
        }
        sink.set(PAVE, path ? Mat.GRAVEL : Mat.GRASS);
        int tree = tree(x, z, lx, lz);
        if (tree == 1) {
            for (int y = STREET; y < STREET + 4; y++) {
                sink.set(y, Mat.LOG);
            }
            for (int y = STREET + 4; y < STREET + 7; y++) {
                sink.set(y, Mat.LEAVES);
            }
        } else if (tree == 2) {
            for (int y = STREET + 3; y < STREET + 6; y++) {
                sink.set(y, Mat.LEAVES);
            }
        } else if (tree == 3) {
            sink.set(STREET + 4, Mat.LEAVES);
            sink.set(STREET + 5, Mat.LEAVES);
        } else if (!path) {
            float r = unit(hash(x, z, 17));
            if (r < 0.3F) {
                sink.set(STREET, Mat.TALL_GRASS);
            } else if (r < 0.36F) {
                sink.set(STREET, Mat.FLOWER);
            }
        } else if (Math.floorMod(lx + lz, 13) == 0 && unit(hash(x, z, 18)) < 0.3F) {
            sink.set(STREET, Mat.BENCH);
        }
    }

    /** 1: a trunk here; 2: thick leaves; 3: the edge of a crown; 0: open sky. */
    private static int tree(int x, int z, int lx, int lz) {
        int cx = Math.floorDiv(x, 7);
        int cz = Math.floorDiv(z, 7);
        int best = 0;
        for (int i = -1; i <= 1; i++) {
            for (int j = -1; j <= 1; j++) {
                long h = hash(cx + i, cz + j, 19);
                if (unit(h) > 0.7F) {
                    continue;
                }
                int tx = (cx + i) * 7 + 1 + pick(h >>> 8, 5);
                int tz = (cz + j) * 7 + 1 + pick(h >>> 12, 5);
                int tlx = Math.floorMod(tx, PITCH);
                int tlz = Math.floorMod(tz, PITCH);
                if (tlx < 14 || tlx > 59 || tlz < 14 || tlz > 59 || Math.abs(tlx - 36) <= 2 || Math.abs(tlz - 36) <= 2
                        || Math.floorDiv(tx, PITCH) != Math.floorDiv(x, PITCH) || Math.floorDiv(tz, PITCH) != Math.floorDiv(z, PITCH)) {
                    continue;
                }
                int d = (x - tx) * (x - tx) + (z - tz) * (z - tz);
                if (d == 0) {
                    return 1;
                }
                if (d <= 2) {
                    best = 2;
                } else if (d <= 5 && best == 0) {
                    best = 3;
                }
            }
        }
        return best;
    }

    private static void parking(int x, int z, int lx, int lz, int bx, int bz, Sink sink) {
        int row = (lz - 12) % 10;
        boolean stall = row < 5;
        sink.set(PAVE, stall && (lx - 12) % 3 == 0 ? Mat.MARK : Mat.ASPHALT);
        if (stall && (lx - 12) % 3 != 0 && row >= 1 && row <= 4) {
            int stallIndex = (lx - 13) / 3;
            long h = hash(bx * 31L + stallIndex, bz * 31L + (lz - 12) / 10, 23);
            if (unit(h) < 0.35F) {
                Mat body = CAR_COLOURS[pick(h >>> 8, CAR_COLOURS.length)];
                boolean end = row == 1 || row == 4;
                sink.set(STREET, end ? Mat.TIRE : body);
                sink.set(STREET + 1, end ? body : Mat.CAR_GLASS);
                if (!end) {
                    sink.set(STREET + 2, body);
                }
            }
        }
    }
}
