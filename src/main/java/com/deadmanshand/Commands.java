package com.deadmanshand;

import com.deadmanshand.game.Game;
import com.deadmanshand.game.Games;
import com.deadmanshand.game.Kind;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;

/**
 * /borderland enter | leave | visa &lt;days&gt; | game &lt;name&gt; | start | games. Needs cheats (operator level 2).
 */
public final class Commands {
    private Commands() {
    }

    static void init() {
        // /answer: anyone, for the games where you say or choose something (a number, a suit, a card)
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(
                CommandManager.literal("answer").then(CommandManager.argument("answer", StringArgumentType.greedyString())
                        .executes(context -> {
                            Games.answer(context.getSource().getPlayerOrThrow(), StringArgumentType.getString(context, "answer"));
                            return 1;
                        }))));
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(
                CommandManager.literal("borderland").requires(source -> source.hasPermissionLevel(2))
                        .then(CommandManager.literal("enter").executes(Commands::enter))
                        .then(CommandManager.literal("leave").executes(Commands::leave))
                        .then(CommandManager.literal("games").executes(Commands::games))
                        .then(CommandManager.literal("start").executes(Commands::start))
                        .then(CommandManager.literal("visa").then(CommandManager.argument("days", IntegerArgumentType.integer(0, 999))
                                .executes(Commands::visa)))
                        .then(CommandManager.literal("game").then(CommandManager.argument("name", StringArgumentType.word())
                                .suggests((context, builder) -> {
                                    for (Kind kind : Kind.values()) {
                                        builder.suggest(kind.name().toLowerCase());
                                    }
                                    return builder.buildFuture();
                                })
                                .executes(Commands::game)))));
    }

    private static int enter(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        ServerPlayerEntity player = context.getSource().getPlayerOrThrow();
        if (Borderland.is(player.world)) {
            context.getSource().sendError(Text.literal("You're already there."));
            return 0;
        }
        Borderland.startCrossing(player);
        return 1;
    }

    private static int leave(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        ServerPlayerEntity player = context.getSource().getPlayerOrThrow();
        if (!Borderland.is(player.world)) {
            context.getSource().sendError(Text.literal("You're not over there."));
            return 0;
        }
        Borderland.sendHome(player);
        return 1;
    }

    private static int visa(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        ServerPlayerEntity player = context.getSource().getPlayerOrThrow();
        Visa.of(player).visa = IntegerArgumentType.getInteger(context, "days") * Visa.DAY;
        context.getSource().sendFeedback(Text.literal("Visa set."), false);
        return 1;
    }

    private static int games(CommandContext<ServerCommandSource> context) {
        StringBuilder text = new StringBuilder("Games:");
        for (Kind kind : Kind.values()) {
            text.append("\n  ").append(kind.name().toLowerCase()).append(" - ").append(kind.card.title()).append(", ").append(kind.title);
        }
        context.getSource().sendFeedback(Text.literal(text.toString()), false);
        return 1;
    }

    private static int game(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        ServerPlayerEntity player = context.getSource().getPlayerOrThrow();
        String name = StringArgumentType.getString(context, "name").toUpperCase();
        Kind kind;
        try {
            kind = Kind.valueOf(name);
        } catch (IllegalArgumentException e) {
            context.getSource().sendError(Text.literal("No game called " + name.toLowerCase() + ". Try /borderland games"));
            return 0;
        }
        ServerWorld borderland = Borderland.world(player.server);
        if (borderland == null || player.world != borderland) {
            context.getSource().sendError(Text.literal("Go over there first: /borderland enter"));
            return 0;
        }
        Game game = Games.open(borderland, player, kind);
        context.getSource().sendFeedback(Text.literal("A venue for " + kind.title + " is going up nearby. Follow your phone."), false);
        return game == null ? 0 : 1;
    }

    private static int start(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        ServerPlayerEntity player = context.getSource().getPlayerOrThrow();
        Game game = Games.gameOf(player);
        if (game == null || !game.closeEntryNow()) {
            context.getSource().sendError(Text.literal("You're not waiting in a venue."));
            return 0;
        }
        return 1;
    }
}
