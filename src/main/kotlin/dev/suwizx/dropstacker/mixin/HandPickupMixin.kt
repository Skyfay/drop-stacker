package dev.suwizx.dropstacker.mixin

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod
import com.llamalad7.mixinextras.injector.wrapoperation.Operation
import dev.suwizx.dropstacker.stack.StackEngine
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.animal.dolphin.Dolphin
import net.minecraft.world.entity.animal.panda.Panda
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.entity.raid.Raider
import org.spongepowered.asm.mixin.Mixin

/**
 * Mobs that take a ground stack into a hand slot in one piece only get one vanilla stack of a pile.
 *
 * An equipment slot holding more than 99 items cannot be saved, so the mob's stack would be lost on
 * the next chunk save. Other mobs already split what they pick up.
 */
@Mixin(Dolphin::class, Panda::class, Raider::class)
abstract class HandPickupMixin {
    @WrapMethod(method = ["pickUpItem"])
    private fun dropstackerPickUpOneStack(level: ServerLevel, item: ItemEntity, original: Operation<Void>) {
        StackEngine.withVanillaSizedPile(item) { original.call(level, item) }
    }
}
