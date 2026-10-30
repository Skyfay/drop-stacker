package dev.suwizx.dropstacker.mixin

import dev.suwizx.dropstacker.accessor.ItemEntityAccessor
import net.minecraft.world.entity.Entity
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo

/**
 * Keeps a pile alive while a pickup takes one stack of it.
 *
 * Only active inside [dev.suwizx.dropstacker.stack.StackEngine.withVanillaSizedPile], where the
 * caller has been shown part of the pile and discards the entity after taking that part.
 */
@Mixin(Entity::class)
abstract class EntityMixin {
    @Inject(method = ["discard"], at = [At("HEAD")], cancellable = true)
    private fun dropstackerKeepCollectedPile(ci: CallbackInfo) {
        val self = this as Any
        if (self is ItemEntityAccessor && self.dropstacker_collecting) {
            self.dropstacker_discardedWhileCollecting = true
            ci.cancel()
        }
    }
}
