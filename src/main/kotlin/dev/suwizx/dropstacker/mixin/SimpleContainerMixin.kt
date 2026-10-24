package dev.suwizx.dropstacker.mixin

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod
import com.llamalad7.mixinextras.injector.wrapoperation.Operation
import net.minecraft.world.SimpleContainer
import net.minecraft.world.item.ItemStack
import org.spongepowered.asm.mixin.Mixin

/**
 * Adds an oversized stack one vanilla stack at a time.
 *
 * Villagers, allays and piglins put pickups into a [SimpleContainer], whose `addItem` moves a stack
 * into an empty slot in one piece; `setItem` then trims it to the slot limit and the rest was
 * deleted. Vanilla never passes an oversized stack here, so normal stacks take the original path.
 */
@Mixin(SimpleContainer::class)
abstract class SimpleContainerMixin {
    @WrapMethod(method = ["addItem"])
    private fun dropstackerAddInVanillaStacks(stack: ItemStack, original: Operation<ItemStack>): ItemStack {
        if (stack.count <= stack.maxStackSize) return original.call(stack)

        val remaining = stack.copy()
        while (!remaining.isEmpty) {
            val part = remaining.split(remaining.maxStackSize)
            val rest = original.call(part)
            if (!rest.isEmpty) return stack.copyWithCount(rest.count + remaining.count)
        }
        return ItemStack.EMPTY
    }
}
