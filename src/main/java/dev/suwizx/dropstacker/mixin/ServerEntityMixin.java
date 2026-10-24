package dev.suwizx.dropstacker.mixin;

import dev.suwizx.dropstacker.stack.LabelVisibility;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerEntity;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import java.util.List;

/**
 * Marks labelled piles as discrete in the entity data sent to clients, both when a player starts
 * tracking the entity and on every later update. See {@link LabelVisibility}.
 *
 * Java because a Kotlin shadow of a final field would need an initializer, and Mixin merges field
 * initializers into the target's constructors.
 */
@Mixin(ServerEntity.class)
abstract class ServerEntityMixin {
    @Shadow @Final private Entity entity;

    @ModifyArg(
        method = {"sendPairingData", "sendDirtyEntityData"},
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/network/protocol/game/ClientboundSetEntityDataPacket;<init>(ILjava/util/List;)V"
        ),
        index = 1
    )
    private List<SynchedEntityData.DataValue<?>> dropstackerHideLabelBehindWalls(List<SynchedEntityData.DataValue<?>> values) {
        return LabelVisibility.INSTANCE.withDiscreteFlag(entity, values);
    }
}
