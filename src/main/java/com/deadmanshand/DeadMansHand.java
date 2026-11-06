package com.deadmanshand;

import com.deadmanshand.city.CityGenerator;
import com.deadmanshand.game.Card;
import com.deadmanshand.game.Games;
import com.deadmanshand.npc.Contestant;
import com.deadmanshand.npc.Hunter;
import com.deadmanshand.npc.Motorcycle;
import com.deadmanshand.npc.Panther;
import com.deadmanshand.npc.Voice;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroup;
import net.minecraft.util.Identifier;
import net.minecraft.util.registry.Registry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Dead Man's Hand: one night the sky fills with fireworks and you wake up in your own world, emptied of everyone.
 * Your visa says how many days you have left; the only way to get more is to win the games that open at night, each
 * marked by a playing card. Run out of time and a laser falls from the sky.
 */
public final class DeadMansHand implements ModInitializer {
    public static final String ID = "deadmanshand";
    public static final Logger LOG = LoggerFactory.getLogger(ID);

    public static final Item PHONE = new PhoneItem(new Item.Settings().group(ItemGroup.MISC).maxCount(1));
    public static final Item GUN = new Item(new Item.Settings().maxCount(1));
    /** Bingo in the dark: strike one for ten seconds of light. */
    public static final Item MATCH = new GameItem(new Item.Settings().maxCount(16));
    /** The borrowing race: what your pair has to bring back. */
    public static final Item SLIP = new GameItem(new Item.Settings().maxCount(1));
    /** One card item per suit, in {@link Card.Suit} order; the rank is in the stack's NBT. */
    public static final CardItem[] CARDS = new CardItem[Card.Suit.values().length];

    public static final EntityType<Contestant> CONTESTANT = EntityType.Builder.create(Contestant::new, SpawnGroup.MISC)
            .setDimensions(0.6F, 1.8F).maxTrackingRange(10).build(ID + ":contestant");
    public static final EntityType<Hunter> HUNTER = EntityType.Builder.create(Hunter::new, SpawnGroup.MISC)
            .setDimensions(0.6F, 1.95F).maxTrackingRange(10).build(ID + ":hunter");
    public static final EntityType<Panther> PANTHER = EntityType.Builder.create(Panther::new, SpawnGroup.MISC)
            .setDimensions(1.0F, 1.1F).maxTrackingRange(10).build(ID + ":panther");
    public static final EntityType<Motorcycle> MOTORCYCLE = EntityType.Builder.create(Motorcycle::new, SpawnGroup.MISC)
            .setDimensions(1.2F, 1.1F).maxTrackingRange(10).build(ID + ":motorcycle");

    public static Identifier id(String path) {
        return new Identifier(ID, path);
    }

    @Override
    public void onInitialize() {
        Config.load();
        Registry.register(Registry.ITEM, id("phone"), PHONE);
        Registry.register(Registry.ITEM, id("gun"), GUN);
        Registry.register(Registry.ITEM, id("match"), MATCH);
        Registry.register(Registry.ITEM, id("slip"), SLIP);
        for (Card.Suit suit : Card.Suit.values()) {
            CARDS[suit.ordinal()] = Registry.register(Registry.ITEM, id("card_" + suit.id),
                    new CardItem(suit, new Item.Settings().group(ItemGroup.MISC).maxCount(1)));
        }
        Registry.register(Registry.CHUNK_GENERATOR, id("city"), CityGenerator.CODEC);
        Registry.register(Registry.ENTITY_TYPE, id("contestant"), CONTESTANT);
        Registry.register(Registry.ENTITY_TYPE, id("hunter"), HUNTER);
        Registry.register(Registry.ENTITY_TYPE, id("panther"), PANTHER);
        Registry.register(Registry.ENTITY_TYPE, id("motorcycle"), MOTORCYCLE);
        FabricDefaultAttributeRegistry.register(CONTESTANT, Contestant.attributes());
        FabricDefaultAttributeRegistry.register(HUNTER, Hunter.attributes());
        FabricDefaultAttributeRegistry.register(PANTHER, Panther.attributes());
        FabricDefaultAttributeRegistry.register(MOTORCYCLE, Motorcycle.attributes());

        Visa.init();
        Borderland.init();
        Games.init();
        Voice.init();
        Commands.init();
        LOG.info("Dead Man's Hand: the games are ready");
    }
}
