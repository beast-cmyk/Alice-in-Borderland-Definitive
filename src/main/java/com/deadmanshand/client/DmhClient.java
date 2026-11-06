package com.deadmanshand.client;

import com.deadmanshand.DeadMansHand;
import com.deadmanshand.Net;
import com.deadmanshand.Visa;
import com.deadmanshand.npc.Contestant;
import com.deadmanshand.npc.Hunter;
import com.deadmanshand.npc.Motorcycle;
import com.deadmanshand.npc.Panther;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.EntityModelLayerRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawableHelper;
import net.minecraft.client.model.ModelData;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.model.ModelPartBuilder;
import net.minecraft.client.model.ModelPartData;
import net.minecraft.client.model.ModelTransform;
import net.minecraft.client.model.TexturedModelData;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.BipedEntityRenderer;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.render.entity.MobEntityRenderer;
import net.minecraft.client.render.entity.feature.ArmorFeatureRenderer;
import net.minecraft.client.render.entity.feature.FeatureRenderer;
import net.minecraft.client.render.entity.feature.FeatureRendererContext;
import net.minecraft.client.render.entity.model.BipedEntityModel;
import net.minecraft.client.render.entity.model.EntityModel;
import net.minecraft.client.render.entity.model.EntityModelLayer;
import net.minecraft.client.render.entity.model.EntityModelLayers;
import net.minecraft.client.render.entity.model.OcelotEntityModel;
import net.minecraft.client.render.entity.model.PlayerEntityModel;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3f;

import java.util.ArrayList;
import java.util.List;

/** What you see: the other players, "it", the panther, the motorcycle, your phone's screen and the white flash. */
public final class DmhClient implements ClientModInitializer {
    private static final EntityModelLayer HORSE_MASK = new EntityModelLayer(DeadMansHand.id("horse_mask"), "main");
    private static final EntityModelLayer MOTORCYCLE = new EntityModelLayer(DeadMansHand.id("motorcycle"), "main");

    private static int visa = -1;
    private static String role = "";
    private static int roleColor = 0xFFFFFF;
    private static final List<String> LINES = new ArrayList<>();
    private static int flash;
    private static int flashLength = 1;

    @Override
    public void onInitializeClient() {
        EntityModelLayerRegistry.registerModelLayer(HORSE_MASK, DmhClient::horseMask);
        EntityModelLayerRegistry.registerModelLayer(MOTORCYCLE, MotorcycleModel::data);
        EntityRendererRegistry.register(DeadMansHand.CONTESTANT, ContestantRenderer::new);
        EntityRendererRegistry.register(DeadMansHand.HUNTER, HunterRenderer::new);
        EntityRendererRegistry.register(DeadMansHand.PANTHER, PantherRenderer::new);
        EntityRendererRegistry.register(DeadMansHand.MOTORCYCLE, MotorcycleRenderer::new);

        ClientPlayNetworking.registerGlobalReceiver(Net.HUD, (client, handler, buf, sender) -> {
            int newVisa = buf.readInt();
            String newRole = buf.readString();
            int newColor = buf.readInt();
            int count = buf.readVarInt();
            List<String> lines = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                lines.add(buf.readString());
            }
            client.execute(() -> {
                visa = newVisa;
                role = newRole;
                roleColor = newColor;
                LINES.clear();
                LINES.addAll(lines);
            });
        });
        ClientPlayNetworking.registerGlobalReceiver(Net.FLASH, (client, handler, buf, sender) -> {
            int ticks = buf.readVarInt();
            client.execute(() -> {
                flash = ticks;
                flashLength = Math.max(1, ticks);
            });
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            visa = -1;
            LINES.clear();
            role = "";
            flash = 0;
        });
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (flash > 0) {
                flash--;
            }
        });
        HudRenderCallback.EVENT.register(DmhClient::hud);
    }

    private static void hud(MatrixStack matrices, float tickDelta) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.options.hudHidden && flash <= 0) {
            return;
        }
        TextRenderer text = client.textRenderer;
        int width = client.getWindow().getScaledWidth();
        int height = client.getWindow().getScaledHeight();
        if (visa >= 0 && !client.options.hudHidden) {
            String days = Visa.describe(visa);
            boolean low = visa < 6000;
            boolean blink = low && (System.currentTimeMillis() / 400L) % 2L == 0L;
            String label = "VISA  " + days;
            int w = text.getWidth(label);
            DrawableHelper.fill(matrices, width - w - 10, 4, width - 4, 18, 0x90000000);
            text.drawWithShadow(matrices, label, width - w - 7, 7, low ? (blink ? 0xFF3030 : 0xAA2020) : 0xF0F0F0);
            int y = 6;
            for (String line : LINES) {
                int lw = text.getWidth(line);
                DrawableHelper.fill(matrices, 4, y - 2, 10 + lw, y + 10, 0x80000000);
                text.drawWithShadow(matrices, line, 7, y, 0xFFFFFF);
                y += 12;
            }
            if (!role.isEmpty()) {
                matrices.push();
                matrices.scale(3.0F, 3.0F, 1.0F);
                int rw = text.getWidth(role);
                text.drawWithShadow(matrices, role, (width / 3.0F - rw) / 2.0F, 12.0F, roleColor);
                matrices.pop();
            }
        }
        if (flash > 0) {
            float t = flash / (float) flashLength;
            float alpha = t > 0.6F ? (1.0F - t) / 0.4F : t / 0.6F;
            int a = (int) (MathHelper.clamp(alpha, 0.0F, 1.0F) * 255.0F);
            DrawableHelper.fill(matrices, 0, 0, width, height, a << 24 | 0xFFFFFF);
        }
    }

    // --- the other players ----------------------------------------------------------------------------------

    static final class ContestantRenderer extends BipedEntityRenderer<Contestant, PlayerEntityModel<Contestant>> {
        private static final Identifier[] SKINS = new Identifier[Contestant.SKINS];

        static {
            for (int i = 0; i < SKINS.length; i++) {
                SKINS[i] = DeadMansHand.id("textures/entity/contestant/skin_" + i + ".png");
            }
        }

        ContestantRenderer(EntityRendererFactory.Context context) {
            super(context, new PlayerEntityModel<>(context.getPart(EntityModelLayers.PLAYER), false), 0.5F);
            addFeature(new ArmorFeatureRenderer<>(this, new BipedEntityModel<>(context.getPart(EntityModelLayers.PLAYER_INNER_ARMOR)),
                    new BipedEntityModel<>(context.getPart(EntityModelLayers.PLAYER_OUTER_ARMOR))));
        }

        @Override
        public Identifier getTexture(Contestant entity) {
            return SKINS[entity.skin()];
        }

        /** A body lies flat on its back. */
        @Override
        protected void setupTransforms(Contestant entity, MatrixStack matrices, float animationProgress, float bodyYaw, float tickDelta) {
            super.setupTransforms(entity, matrices, animationProgress, bodyYaw, tickDelta);
            if (entity.isBody()) {
                matrices.translate(0.0, 0.15, 0.9);
                matrices.multiply(Vec3f.POSITIVE_X.getDegreesQuaternion(-90.0F));
            }
        }
    }

    // --- "it" -------------------------------------------------------------------------------------------

    static final class HunterRenderer extends BipedEntityRenderer<Hunter, PlayerEntityModel<Hunter>> {
        private static final Identifier SKIN = DeadMansHand.id("textures/entity/hunter.png");

        HunterRenderer(EntityRendererFactory.Context context) {
            super(context, new PlayerEntityModel<>(context.getPart(EntityModelLayers.PLAYER), false), 0.5F);
            addFeature(new HorseMask(this, context.getPart(HORSE_MASK)));
        }

        @Override
        public Identifier getTexture(Hunter entity) {
            return SKIN;
        }
    }

    /** The horse's head, worn over its own. */
    static final class HorseMask extends FeatureRenderer<Hunter, PlayerEntityModel<Hunter>> {
        private static final Identifier TEXTURE = DeadMansHand.id("textures/entity/horse_mask.png");
        private final ModelPart mask;

        HorseMask(FeatureRendererContext<Hunter, PlayerEntityModel<Hunter>> context, ModelPart mask) {
            super(context);
            this.mask = mask;
        }

        @Override
        public void render(MatrixStack matrices, VertexConsumerProvider vertexConsumers, int light, Hunter entity, float limbAngle, float limbDistance,
                           float tickDelta, float animationProgress, float headYaw, float headPitch) {
            matrices.push();
            getContextModel().getHead().rotate(matrices);
            VertexConsumer consumer = vertexConsumers.getBuffer(RenderLayer.getEntityCutoutNoCull(TEXTURE));
            mask.render(matrices, consumer, light, OverlayTexture.DEFAULT_UV);
            matrices.pop();
        }
    }

    private static TexturedModelData horseMask() {
        ModelData data = new ModelData();
        ModelPartData root = data.getRoot();
        root.addChild("skull", ModelPartBuilder.create().uv(0, 0).cuboid(-4.5F, -9.0F, -5.0F, 9.0F, 9.0F, 10.0F), ModelTransform.NONE);
        root.addChild("snout", ModelPartBuilder.create().uv(0, 20).cuboid(-3.0F, -8.0F, -12.0F, 6.0F, 6.0F, 7.0F), ModelTransform.NONE);
        root.addChild("left_ear", ModelPartBuilder.create().uv(40, 0).cuboid(1.5F, -12.0F, 1.0F, 2.0F, 3.0F, 1.0F), ModelTransform.NONE);
        root.addChild("right_ear", ModelPartBuilder.create().uv(40, 5).cuboid(-3.5F, -12.0F, 1.0F, 2.0F, 3.0F, 1.0F), ModelTransform.NONE);
        root.addChild("mane", ModelPartBuilder.create().uv(28, 20).cuboid(-1.0F, -10.0F, -2.0F, 2.0F, 1.0F, 8.0F), ModelTransform.NONE);
        return TexturedModelData.of(data, 64, 64);
    }

    // --- the panther ---------------------------------------------------------------------------------------

    static final class PantherRenderer extends MobEntityRenderer<Panther, OcelotEntityModel<Panther>> {
        private static final Identifier[] COATS = new Identifier[Panther.Beast.values().length];

        static {
            for (Panther.Beast beast : Panther.Beast.values()) {
                COATS[beast.ordinal()] = new Identifier("minecraft", "textures/entity/cat/" + beast.coat + ".png");
            }
        }

        PantherRenderer(EntityRendererFactory.Context context) {
            super(context, new OcelotEntityModel<>(context.getPart(EntityModelLayers.OCELOT)), 0.8F);
        }

        @Override
        protected void scale(Panther entity, MatrixStack matrices, float amount) {
            float size = entity.beast().scale;
            matrices.scale(size, size, size);
        }

        @Override
        public Identifier getTexture(Panther entity) {
            return COATS[entity.beast().ordinal()];
        }
    }

    // --- the motorcycle -------------------------------------------------------------------------------------

    static final class MotorcycleRenderer extends MobEntityRenderer<Motorcycle, MotorcycleModel> {
        private static final Identifier TEXTURE = DeadMansHand.id("textures/entity/motorcycle.png");

        MotorcycleRenderer(EntityRendererFactory.Context context) {
            super(context, new MotorcycleModel(context.getPart(MOTORCYCLE)), 0.6F);
        }

        @Override
        public Identifier getTexture(Motorcycle entity) {
            return TEXTURE;
        }
    }

    static final class MotorcycleModel extends EntityModel<Motorcycle> {
        private final ModelPart root;
        private final ModelPart front;
        private final ModelPart back;

        MotorcycleModel(ModelPart root) {
            this.root = root;
            this.front = root.getChild("front_wheel");
            this.back = root.getChild("back_wheel");
        }

        static TexturedModelData data() {
            ModelData data = new ModelData();
            ModelPartData root = data.getRoot();
            root.addChild("frame", ModelPartBuilder.create().uv(0, 0).cuboid(-1.5F, 13.0F, -9.0F, 3.0F, 4.0F, 18.0F), ModelTransform.NONE);
            root.addChild("tank", ModelPartBuilder.create().uv(0, 22).cuboid(-2.5F, 10.0F, -6.0F, 5.0F, 3.0F, 6.0F), ModelTransform.NONE);
            root.addChild("seat", ModelPartBuilder.create().uv(22, 22).cuboid(-2.5F, 11.0F, 0.0F, 5.0F, 2.0F, 7.0F), ModelTransform.NONE);
            root.addChild("bars", ModelPartBuilder.create().uv(0, 31).cuboid(-6.0F, 6.0F, -9.0F, 12.0F, 1.0F, 1.0F)
                    .uv(26, 31).cuboid(-0.5F, 7.0F, -9.0F, 1.0F, 7.0F, 1.0F), ModelTransform.NONE);
            root.addChild("light", ModelPartBuilder.create().uv(30, 31).cuboid(-1.5F, 8.0F, -11.0F, 3.0F, 3.0F, 2.0F), ModelTransform.NONE);
            root.addChild("front_wheel", ModelPartBuilder.create().uv(42, 0).cuboid(-1.0F, -3.5F, -3.5F, 2.0F, 7.0F, 7.0F),
                    ModelTransform.pivot(0.0F, 20.5F, -9.0F));
            root.addChild("back_wheel", ModelPartBuilder.create().uv(42, 0).cuboid(-1.0F, -3.5F, -3.5F, 2.0F, 7.0F, 7.0F),
                    ModelTransform.pivot(0.0F, 20.5F, 9.0F));
            return TexturedModelData.of(data, 64, 48);
        }

        @Override
        public void setAngles(Motorcycle entity, float limbAngle, float limbDistance, float animationProgress, float headYaw, float headPitch) {
            float spin = limbAngle * 1.6F;
            front.pitch = spin;
            back.pitch = spin;
        }

        @Override
        public void render(MatrixStack matrices, VertexConsumer vertices, int light, int overlay, float red, float green, float blue, float alpha) {
            root.render(matrices, vertices, light, overlay, red, green, blue, alpha);
        }
    }
}
