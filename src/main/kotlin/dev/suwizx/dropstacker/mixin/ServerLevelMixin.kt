package dev.suwizx.dropstacker.mixin

import dev.suwizx.dropstacker.config.DropStackerConfig
import dev.suwizx.dropstacker.stack.StackEngine
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.item.ItemEntity
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable

/**
 * Makes merging instant.
 *
 * Merging used to be purely pull-based, so a fresh drop waited for its own tick to come round
 * before it could join a pile. Here a newly spawned item entity is offered to the world the moment
 * it is added, in the same tick it spawned: it joins the piles around it (see
 * [StackEngine.joinNeighbours]).
 *
 * `addFreshEntity` is targeted rather than Fabric's `ServerEntityEvents.ENTITY_LOAD` because it
 * fires only for genuinely new entities. Loading a chunk full of ground items goes through a
 * different path and so cannot trigger a merge storm; those are picked up by the periodic scan in
 * [ItemEntityMixin] instead.
 */
@Mixin(ServerLevel::class)
abstract class ServerLevelMixin {
    @Inject(method = ["addFreshEntity"], at = [At("RETURN")])
    private fun dropstackerOnAddFreshEntity(entity: Entity, callback: CallbackInfoReturnable<Boolean>) {
        if (callback.returnValue != true) return
        if (!DropStackerConfig.enabled || !DropStackerConfig.mergeOnSpawn) return
        if (entity !is ItemEntity) return

        StackEngine.joinNeighbours(entity)
    }
}
