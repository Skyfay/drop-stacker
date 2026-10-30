package dev.suwizx.dropstacker.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.suwizx.dropstacker.stack.StackEngine;
import net.minecraft.world.Container;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Hoppers and hopper minecarts collect a ground pile one vanilla stack at a time.
 *
 * Vanilla moves the whole ground stack into the first empty slot, and the slot's `setItem` trims it
 * to 64, so everything above that was deleted. Java rather than Kotlin because the target is static,
 * which needs a static handler.
 */
@Mixin(HopperBlockEntity.class)
abstract class HopperBlockEntityMixin {
    @WrapMethod(method = "addItem(Lnet/minecraft/world/Container;Lnet/minecraft/world/entity/item/ItemEntity;)Z")
    private static boolean dropstackerCollectOneStackAtATime(Container container, ItemEntity item, Operation<Boolean> original) {
        return StackEngine.INSTANCE.withVanillaSizedPile(item, () -> original.call(container, item));
    }
}
