package dev.suwizx.dropstacker.accessor

import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityReference
import net.minecraft.world.entity.item.ItemEntity
import java.util.UUID

/**
 * Duck-typed interface mixed into every `ItemEntity` by `ItemEntityMixin`, exposing the mod's
 * per-entity state to code outside the mixin.
 *
 * Note there is deliberately no age getter: vanilla's `ItemEntity.getAge()` is already public.
 */
interface ItemEntityAccessor {
    /** Vanilla's private `pickupDelay`. */
    fun dropstacker_pickupDelay(): Int

    fun dropstacker_setPickupDelay(value: Int)

    fun dropstacker_setAge(value: Int)

    /** Whether the drop was thrown by someone (a player, or a villager or piglin handing items over). */
    fun dropstacker_hasThrower(): Boolean

    /** Vanilla's private `target`: the only player allowed to pick this drop up, if any. */
    fun dropstacker_target(): UUID?

    /**
     * True while [dev.suwizx.dropstacker.stack.StackEngine.withVanillaSizedPile] hands a pickup one
     * stack of this pile; `discard()` is ignored meanwhile, because the rest still lies here.
     */
    var dropstacker_collecting: Boolean

    /** Set when a pickup called `discard()` during collecting: it took everything it was shown. */
    var dropstacker_discardedWhileCollecting: Boolean

    /** Sets vanilla's private `target` and `thrower`. */
    fun dropstacker_setOwnership(target: UUID?, thrower: EntityReference<Entity>?)

    /** Copies pickup delay, age, target and thrower onto [other], as vanilla's own copy constructor would. */
    fun dropstacker_copyStateTo(other: ItemEntity)

    /** Rebuilds the name tag, but only if the rendered text would differ from the last one applied. */
    fun dropstacker_refreshLabel()

    /**
     * True when this entity carries a custom name the mod did not write — a name-tagged drop, a
     * `/summon` with `CustomName`, or another mod's label.
     */
    fun dropstacker_hasForeignName(): Boolean

    /**
     * Resets the adaptive scan backoff, so this entity polls its neighbourhood again at the
     * configured [dev.suwizx.dropstacker.config.DropStackerConfig.scanInterval].
     */
    fun dropstacker_resetBackoff()
}
