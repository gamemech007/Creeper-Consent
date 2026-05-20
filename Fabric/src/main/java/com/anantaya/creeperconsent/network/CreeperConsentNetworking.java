package com.anantaya.creeperconsent.network;

import com.anantaya.creeperconsent.CreeperConsentMod;
import com.anantaya.creeperconsent.CreeperConsentState;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.phys.AABB;

import java.util.List;

public final class CreeperConsentNetworking {

    public static final float EXPLOSION_RADIUS = 8.0f;

    private CreeperConsentNetworking() {}

    public static void register() {
        registerPayloadTypes();
        registerServerReceiver();
        CreeperConsentMod.LOGGER.info("Creeper Consent networking registered.");
    }

    private static void registerPayloadTypes() {
        // Need PayloadTypeRegistry docs here.
        // Your version does NOT have playS2C() / playC2S().
        //
        // Replace these with the correct methods from your docs:
        //
        PayloadTypeRegistry.clientboundPlay().register(
                OpenConsentScreenPayload.TYPE,
                OpenConsentScreenPayload.CODEC
        );

        PayloadTypeRegistry.serverboundPlay().register(
                CreeperConsentResponsePayload.TYPE,
                CreeperConsentResponsePayload.CODEC
        );
    }

    private static void registerServerReceiver() {
        ServerPlayNetworking.registerGlobalReceiver(
                CreeperConsentResponsePayload.TYPE,
                (payload, context) -> {
                    ServerPlayer player = context.player();

                    context.server().execute(() ->
                            handleConsentResponse(
                                    player,
                                    payload.creeperEntityId(),
                                    payload.allow()
                            )
                    );
                }
        );
    }

    private static void handleConsentResponse(ServerPlayer player,
                                              int creeperEntityId,
                                              boolean allow) {
        if (!(player.level() instanceof ServerLevel world)) {
            return;
        }

        Entity entity = world.getEntity(creeperEntityId);

        if (!(entity instanceof Creeper creeper)) {
            CreeperConsentMod.LOGGER.warn(
                    "Player {} sent consent response for unknown/non-creeper entity {}",
                    player.getName().getString(),
                    creeperEntityId
            );
            return;
        }

        double distanceSq = player.distanceToSqr(creeper);

        if (distanceSq > (EXPLOSION_RADIUS * EXPLOSION_RADIUS)) {
            CreeperConsentMod.LOGGER.warn(
                    "Player {} responded but is now out of range of creeper {}",
                    player.getName().getString(),
                    creeperEntityId
            );

            CreeperConsentState.clear(creeper.getUUID());
            return;
        }

        if (allow) {
            CreeperConsentMod.LOGGER.info(
                    "Player {} ALLOWED creeper {} to explode.",
                    player.getName().getString(),
                    creeperEntityId
            );

            CreeperConsentState.approveOnce(creeper.getUUID());

            // Restart the creeper fuse. When it reaches explodeCreeper(),
            // the mixin will consume the approval and allow vanilla explosion.
            creeper.setSwellDir(1);
            creeper.ignite();

            player.sendSystemMessage(
                    Component.translatable("creeper-consent-mod.message.explosion_allowed")
            );
        } else {
            CreeperConsentMod.LOGGER.info(
                    "Player {} DENIED creeper {} — removing entity.",
                    player.getName().getString(),
                    creeperEntityId
            );

            CreeperConsentState.clear(creeper.getUUID());
            creeper.discard();

            player.sendSystemMessage(
                    Component.translatable("creeper-consent-mod.message.creeper_deleted")
            );
        }
    }

    public static void sendConsentScreenToNearbyPlayers(Creeper creeper,
                                                        ServerLevel world) {
        AABB blastBox = new AABB(creeper.blockPosition()).inflate(EXPLOSION_RADIUS);

        List<ServerPlayer> nearby = world.getPlayers(
                p -> p.getBoundingBox().intersects(blastBox)
        );

        if (nearby.isEmpty()) {
            return;
        }

        creeper.setSwellDir(-1);

        OpenConsentScreenPayload payload = new OpenConsentScreenPayload(creeper.getId());

        for (ServerPlayer player : nearby) {
            ServerPlayNetworking.send(player, payload);

            CreeperConsentMod.LOGGER.info(
                    "Sent consent screen to {} for creeper {}",
                    player.getName().getString(),
                    creeper.getId()
            );
        }
    }
}