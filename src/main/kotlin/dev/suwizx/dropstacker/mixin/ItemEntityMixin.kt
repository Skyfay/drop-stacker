package dev.suwizx.dropstacker.mixin

import dev.suwizx.dropstacker.accessor.ItemEntityAccessor
import dev.suwizx.dropstacker.config.DropStackerConfig
import dev.suwizx.dropstacker.config.LabelMode
import dev.suwizx.dropstacker.stack.StackEngine
import dev.suwizx.dropstacker.stack.StackLabel
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityReference
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.storage.ValueInput
import net.minecraft.world.level.storage.ValueOutput
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod
import com.llamalad7.mixinextras.injector.wrapoperation.Operation
import net.minecraft.world.entity.player.Player
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.Shadow
import org.spongepowered.asm.mixin.Unique
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.ModifyArg
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable
import java.util.Objects
import java.util.UUID

@Mixin(ItemEntity::class)
abstract class ItemEntityMixin : ItemEntityAccessor {
    @Shadow
    abstract fun getItem(): ItemStack

    @Shadow
    private var pickupDelay = 0

    @Shadow
    private var age = 0

    @Shadow
    private var target: UUID? = null

    @Shadow
    private var thrower: EntityReference<Entity>? = null

    /** The last [StackLabel.labelKey] actually written to this entity's custom name. */
    @Unique
    private var dropstackerLabelKey: Long = Long.MIN_VALUE + 1

    /** The [StackLabel.generation] the current label was built with. */
    @Unique
    private var dropstackerLabelGeneration: Int = -1

    /** Current adaptive scan interval, grown while scans keep finding nothing. */
    @Unique
    private var dropstackerBackoff: Int = 0

    /**
     * The exact component this mixin last wrote to `customName`.
     *
     * Comparing against it is how a foreign name is detected. Tracking "the name it had when we
     * first saw it" instead would be unreliable: entity NBT is read *after* `setItem` runs, so a
     * `/summon ... {CustomName:...}` replaces our label a moment after we apply it.
     */
    @Unique
    private var dropstackerAppliedLabel: Component? = null

    @Unique
    private var dropstackerCollecting: Boolean = false

    @Unique
    private var dropstackerDiscardedWhileCollecting: Boolean = false

    // --- ItemEntityAccessor ---

    override var dropstacker_collecting: Boolean
        get() = dropstackerCollecting
        set(value) {
            dropstackerCollecting = value
        }

    override var dropstacker_discardedWhileCollecting: Boolean
        get() = dropstackerDiscardedWhileCollecting
        set(value) {
            dropstackerDiscardedWhileCollecting = value
        }

    override fun dropstacker_pickupDelay(): Int = this.pickupDelay

    override fun dropstacker_setPickupDelay(value: Int) {
        this.pickupDelay = value
    }

    override fun dropstacker_setAge(value: Int) {
        this.age = value
    }

    override fun dropstacker_hasThrower(): Boolean = this.thrower != null

    override fun dropstacker_target(): UUID? = this.target

    override fun dropstacker_setOwnership(target: UUID?, thrower: EntityReference<Entity>?) {
        this.target = target
        this.thrower = thrower
    }

    override fun dropstacker_copyStateTo(other: ItemEntity) {
        val access = other as ItemEntityAccessor
        access.dropstacker_setPickupDelay(this.pickupDelay)
        access.dropstacker_setAge(this.age)
        access.dropstacker_setOwnership(this.target, this.thrower)
    }

    override fun dropstacker_resetBackoff() {
        this.dropstackerBackoff = DropStackerConfig.scanInterval
    }

    override fun dropstacker_hasForeignName(): Boolean {
        val current = (this as Any as ItemEntity).customName ?: return false
        return current != dropstackerAppliedLabel
    }

    override fun dropstacker_refreshLabel() {
        val entity = this as Any as ItemEntity
        val count = this.getItem().count

        // Never overwrite a genuine name.
        if (dropstacker_hasForeignName()) return

        val key = StackLabel.labelKey(entity, count)
        if (key == dropstackerLabelKey && dropstackerLabelGeneration == StackLabel.generation) return
        dropstackerLabelKey = key
        dropstackerLabelGeneration = StackLabel.generation

        val label = StackLabel.build(entity, count)
        dropstackerAppliedLabel = label
        if (label == null) {
            entity.customName = null
            entity.isCustomNameVisible = false
        } else {
            entity.customName = label
            entity.isCustomNameVisible = true
        }
    }

    // --- injections ---

    @Inject(method = ["setItem"], at = [At("TAIL")])
    private fun dropstackerOnSetItem(stack: ItemStack, ci: CallbackInfo) {
        dropstacker_refreshLabel()
    }

    @Inject(method = ["tick"], at = [At("HEAD")])
    private fun dropstackerOnTick(ci: CallbackInfo) {
        val entity = this as Any as ItemEntity

        // Ordered cheapest-first: the old code read the synched ItemStack before this check, so
        // every client paid a synched-data lookup per item entity per tick for nothing.
        if (entity.level().isClientSide || !DropStackerConfig.enabled || !entity.isAlive) return

        if (StackLabel.showsTimer()) {
            // The rendered timer changes at most once per second, so checking more often than every
            // 20 ticks can only ever be wasted work. Staggered by entity id so a farm's worth of
            // drops don't all rebuild their labels on the same tick.
            if ((entity.tickCount + entity.id) % 20 == 0) {
                dropstacker_refreshLabel()
            }
        }

        if (dropstackerBackoff <= 0) dropstackerBackoff = DropStackerConfig.scanInterval
        if ((entity.tickCount + entity.id) % dropstackerBackoff != 0) return

        if (!StackEngine.isMergeable(entity, this.getItem())) return

        if (StackEngine.absorbNeighbours(entity) == 0) {
            // Nothing nearby: back off, up to maxScanInterval. This is only safe because new drops
            // push into existing piles on spawn (ServerLevelMixin) rather than waiting to be polled.
            val ceiling = DropStackerConfig.maxScanInterval.coerceAtLeast(DropStackerConfig.scanInterval)
            dropstackerBackoff = (dropstackerBackoff * 2).coerceAtMost(ceiling)
        }
    }

    /**
     * Vanilla runs its own neighbour scan every 40 ticks. [StackEngine] fully replaces it, so
     * leaving it enabled would mean two merge systems scanning the same entities.
     */
    @Inject(method = ["mergeWithNeighbours"], at = [At("HEAD")], cancellable = true)
    private fun dropstackerOnMergeWithNeighbours(ci: CallbackInfo) {
        if (DropStackerConfig.enabled) ci.cancel()
    }

    /** Widens vanilla's stack-size ceiling to the configured maximum, keeping every other guard. */
    @Inject(method = ["isMergable"], at = [At("HEAD")], cancellable = true)
    private fun dropstackerOnIsMergable(callback: CallbackInfoReturnable<Boolean>) {
        if (!DropStackerConfig.enabled) return
        val entity = this as Any as ItemEntity
        callback.returnValue = StackEngine.isMergeable(entity, this.getItem())
    }

    /**
     * Kept so callers outside the mod (other mods, or any vanilla path we do not cancel) still get
     * stack-size-aware merging rather than vanilla's 64 cap.
     */
    @Inject(method = ["tryToMerge"], at = [At("HEAD")], cancellable = true)
    private fun dropstackerOnTryToMerge(other: ItemEntity, ci: CallbackInfo) {
        if (!DropStackerConfig.enabled) return
        val entity = this as Any as ItemEntity
        val thisStack = this.getItem()
        if (!ItemStack.isSameItemSameComponents(thisStack, other.item)) return
        if (!Objects.equals(this.target, (other as ItemEntityAccessor).dropstacker_target())) return

        ci.cancel()

        val max = StackEngine.effectiveMax(thisStack)
        if (thisStack.count >= max) return
        val taken = StackEngine.drain(entity, other, max - thisStack.count)
        if (taken > 0) {
            // New instance so the synched stack actually changes; also refreshes the label.
            entity.item = thisStack.copyWithCount(thisStack.count + taken)
        }
    }

    /** Players pick a pile up one vanilla stack per touch; see [StackEngine.withVanillaSizedPile]. */
    @WrapMethod(method = ["playerTouch"])
    private fun dropstackerPlayerTakesOneStack(player: Player, original: Operation<Void>) {
        StackEngine.withVanillaSizedPile(this as Any as ItemEntity) { original.call(player) }
    }

    // --- persistence ---
    //
    // Vanilla's disk codec for ItemStack only accepts counts of 1..99, so a bigger pile failed to
    // encode, was saved with no `Item` at all, and was discarded as empty on the next load (world
    // restart, chunk reload, `/data merge`, portal trip). Only the disk format is patched: the
    // network codec has no such cap, so vanilla clients are unaffected.

    /** Writes a vanilla-valid copy of the stack, capped at 99. The live stack is untouched. */
    @ModifyArg(
        method = ["addAdditionalSaveData"],
        at = [
            At(
                value = "INVOKE",
                target = "Lnet/minecraft/world/level/storage/ValueOutput;store(Ljava/lang/String;Lcom/mojang/serialization/Codec;Ljava/lang/Object;)V",
            ),
        ],
        index = 2,
    )
    private fun dropstackerCapSavedStack(value: Any?): Any? {
        val stack = value as? ItemStack ?: return value
        if (stack.count <= Item.ABSOLUTE_MAX_STACK_SIZE) return stack
        return stack.copyWithCount(Item.ABSOLUTE_MAX_STACK_SIZE)
    }

    /**
     * Records the true count alongside the capped stack, and whether the saved custom name is our
     * own label. Without that flag a reloaded label looks like a foreign name: it would never be
     * updated again and the pile would never merge again.
     */
    @Inject(method = ["addAdditionalSaveData"], at = [At("TAIL")])
    private fun dropstackerOnSave(output: ValueOutput, ci: CallbackInfo) {
        val count = this.getItem().count
        if (count > Item.ABSOLUTE_MAX_STACK_SIZE) output.putInt(StackEngine.SAVED_COUNT_KEY, count)

        val name = (this as Any as ItemEntity).customName
        if (name != null && name == dropstackerAppliedLabel) output.putBoolean(StackEngine.SAVED_LABEL_KEY, true)
    }

    /**
     * Restores the true count. Only applied when the loaded stack is exactly at the cap, so an
     * admin's `/data merge ... {Item:{count:50}}` wins over the leftover key instead of being undone.
     * Deliberately not clamped to the current `maxStackSize`: lowering the config must not delete items.
     */
    @Inject(method = ["readAdditionalSaveData"], at = [At("TAIL")])
    private fun dropstackerOnLoad(input: ValueInput, ci: CallbackInfo) {
        // `Entity.load` restores the custom name before this runs, so the saved label is already set.
        val entity = this as Any as ItemEntity
        if (input.getBooleanOr(StackEngine.SAVED_LABEL_KEY, false) && entity.customName != null) {
            dropstackerAppliedLabel = entity.customName
            dropstackerLabelKey = Long.MIN_VALUE + 1
            dropstacker_refreshLabel()
        }

        val saved = input.getIntOr(StackEngine.SAVED_COUNT_KEY, 0)
        val stack = this.getItem()
        if (stack.isEmpty || stack.count != Item.ABSOLUTE_MAX_STACK_SIZE || saved <= stack.count) return
        // New instance through setItem, so the change syncs and the label refreshes.
        (this as Any as ItemEntity).item = stack.copyWithCount(saved)
    }
}
