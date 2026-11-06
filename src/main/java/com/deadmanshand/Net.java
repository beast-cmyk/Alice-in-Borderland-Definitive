package com.deadmanshand;

import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;

/** The few things the server tells your screen. */
public final class Net {
    /** visa ticks (-1: not over there), role word, role colour, lines. */
    public static final Identifier HUD = DeadMansHand.id("hud");
    /** A white flash over the whole screen, this many ticks long. */
    public static final Identifier FLASH = DeadMansHand.id("flash");

    private Net() {
    }

    public static void hud(ServerPlayerEntity player, int visa, Hud hud) {
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeInt(visa);
        buf.writeString(hud.role);
        buf.writeInt(hud.roleColor);
        buf.writeVarInt(hud.lines.size());
        for (String line : hud.lines) {
            buf.writeString(line);
        }
        ServerPlayNetworking.send(player, HUD, buf);
    }

    public static void flash(ServerPlayerEntity player, int ticks) {
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeVarInt(ticks);
        ServerPlayNetworking.send(player, FLASH, buf);
    }
}
