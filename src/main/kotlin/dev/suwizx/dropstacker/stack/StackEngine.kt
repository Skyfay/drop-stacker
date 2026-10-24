package dev.suwizx.dropstacker.stack

import dev.suwizx.dropstacker.accessor.ItemEntityAccessor
import dev.suwizx.dropstacker.config.AgeInheritance
import dev.suwizx.dropstacker.config.DropStackerConfig
import dev.suwizx.dropstacker.config.ItemRules
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.entity.EntityTypeTest
import java.util.Objects

/**
 * All merge logic. This is the only place in the mod that scans for neighbours.
 *
 * The previous design was purely pull-based: every item entity polled a 5x2x5 box every 5 ticks and
 * absorbed a *single* neighbour per poll, so a 100-item pile took ~500 ticks to collapse. Here
 * merging is push-based ([ItemEntity] spawn drives it, see `ServerLevelMixin`) and each pass drains
 * the whole neighbourhood, so a pile collapses in the tick it is created.
 */
object StackEngine {
    /** Vanilla `ItemEntity.INFINITE_PICKUP_DELAY`. */
    private const val INFINITE_PICKUP_DELAY = 32767

    /** Vanilla `ItemEntity.INFINITE_LIFETIME`. */
    private const val INFINITE_LIFETIME = -32768

    /**
     * Entity NBT key holding a pile's true count when it exceeds what the vanilla item codec can
     * save (see the save/load hooks in `ItemEntityMixin`). Part of the on-disk format: never rename.
     */
    const val SAVED_COUNT_KEY = "DropStackerCount"

    /**
     * Entity NBT flag marking the saved custom name as the mod's own label rather than a real name.
     * Part of the on-disk format: never rename.
     */
    const val SAVED_LABEL_KEY = "DropStackerLabel"

    private val ITEM_ENTITY_TEST: EntityTypeTest<net.minecraft.world.entity.Entity, ItemEntity> =
        EntityTypeTest.forClass(ItemEntity::class.java)

    /**
     * Reused across calls so a scan allocates nothing. Safe as a single shared instance because
     * every caller runs on the server tick thread, and the list never escapes [absorbNeighbours].
     */
    private val scratch = ArrayList<ItemEntity>(32)

    var mergesPerformed: Long = 0L
        private set

    var entitiesCollapsed: Long = 0L
        private set

    fun resetStats() {
        mergesPerformed = 0L
        entitiesCollapsed = 0L
    }

    /** The count [stack] may be stacked to, honouring per-item overrides. */
    fun effectiveMax(stack: ItemStack): Int = ItemRules.maxStackSize(stack)

    /**
     * Vanilla's `isMergable` conditions, widened by the mod's configured maximum.
     *
     * Vanilla rejects `count >= stack.maxStackSize`; we allow up to [effectiveMax] instead. Every
     * *other* vanilla guard is kept deliberately — the previous implementation short-circuited to
     * `true` for large stacks and so skipped the infinite-lifetime and near-despawn checks.
     */
    fun isMergeable(entity: ItemEntity, stack: ItemStack): Boolean {
        if (!entity.isAlive || entity.isRemoved) return false
        val access = entity as ItemEntityAccessor
        // A deliberately named drop must not be silently consumed by a neighbouring pile.
        if (access.dropstacker_hasForeignName()) return false
        if (access.dropstacker_pickupDelay() == INFINITE_PICKUP_DELAY) return false
        if (entity.age == INFINITE_LIFETIME) return false
        if (entity.age >= DropStackerConfig.despawnTicks) return false
        if (stack.isEmpty || stack.count >= effectiveMax(stack)) return false
        return ItemRules.isStackable(stack)
    }

    /**
     * Pulls every compatible neighbour of [host] into it, up to
     * [DropStackerConfig.maxMergePerPass].
     *
     * @return how many entities were absorbed.
     */
    fun absorbNeighbours(host: ItemEntity): Int {
        if (!DropStackerConfig.enabled) return 0
        val level = host.level() as? ServerLevel ?: return 0

        val hostStack = host.item
        if (!isMergeable(host, hostStack)) return 0

        val max = effectiveMax(hostStack)
        // Vanilla only merges drops reserved for the same player; a reserved drop absorbed into a
        // public pile would become pickable by anyone (and the other way round).
        val hostTarget = (host as ItemEntityAccessor).dropstacker_target()
        val radius = DropStackerConfig.radius
        val box = host.boundingBox.inflate(radius, DropStackerConfig.verticalRadius, radius)

        scratch.clear()
        level.getEntities(
            ITEM_ENTITY_TEST,
            box,
            { other ->
                other !== host &&
                    ItemStack.isSameItemSameComponents(hostStack, other.item) &&
                    Objects.equals(hostTarget, (other as ItemEntityAccessor).dropstacker_target()) &&
                    isMergeable(other, other.item)
            },
            scratch,
            DropStackerConfig.maxMergePerPass,
        )
        if (scratch.isEmpty()) return 0

        val startCount = hostStack.count
        var pending = startCount
        var absorbed = 0

        for (other in scratch) {
            if (pending >= max) break
            val taken = drain(host, other, max - pending)
            if (taken <= 0) continue
            pending += taken
            if (!other.isAlive) absorbed++
        }
        scratch.clear()

        if (pending == startCount) return 0

        // A *new* ItemStack instance, not a mutation of the live one. SynchedEntityData.set
        // compares by equality, so mutating the tracked stack in place would leave clients showing
        // a stale count forever; vanilla's own merge builds a new stack for exactly this reason.
        // This also fires the setItem hook, which refreshes the label.
        host.item = hostStack.copyWithCount(pending)

        mergesPerformed++
        entitiesCollapsed += absorbed
        (host as ItemEntityAccessor).dropstacker_resetBackoff()
        return absorbed
    }

    /**
     * Takes up to [wanted] items out of [other], carrying its pickup delay and age across to [host].
     *
     * @return how many items were taken.
     */
    fun drain(host: ItemEntity, other: ItemEntity, wanted: Int): Int {
        val otherStack = other.item
        val taken = minOf(otherStack.count, wanted)
        if (taken <= 0) return 0

        val hostAccess = host as ItemEntityAccessor
        val otherAccess = other as ItemEntityAccessor

        // Keep the longest pickup delay so merging can never shorten it.
        hostAccess.dropstacker_setPickupDelay(
            maxOf(hostAccess.dropstacker_pickupDelay(), otherAccess.dropstacker_pickupDelay())
        )

        when (DropStackerConfig.ageInheritance) {
            // Lower age means more life left. Piles fed by a farm never despawn under this setting.
            AgeInheritance.YOUNGEST -> hostAccess.dropstacker_setAge(minOf(host.age, other.age))
            AgeInheritance.OLDEST -> hostAccess.dropstacker_setAge(maxOf(host.age, other.age))
            AgeInheritance.KEEP_RECEIVER -> Unit
        }

        val remaining = otherStack.count - taken
        if (remaining <= 0) {
            other.discard()
        } else {
            other.item = otherStack.copyWithCount(remaining)
        }
        return taken
    }

    /**
     * Runs [action] — a vanilla pickup such as a hopper or a mob collecting [entity] — while the
     * entity holds at most one vanilla-sized stack, then puts the rest of the pile back.
     *
     * Vanilla never sees a ground stack above `maxStackSize`, so several pickup paths move the whole
     * stack into a single slot: hoppers and `SimpleContainer` (villagers, allays, piglins) then trim
     * it to the slot limit and delete the rest, and dolphins, pandas and raiders hold it in a hand
     * slot that cannot be saved above 99. Handing them one stack at a time keeps every vanilla rule
     * intact; the pile is simply collected over several pickups.
     */
    fun <T> withVanillaSizedPile(entity: ItemEntity, action: () -> T): T {
        val pile = entity.item
        val cap = pile.maxStackSize
        val access = entity as ItemEntityAccessor
        if (pile.count <= cap || access.dropstacker_collecting) return action()

        val overflow = pile.count - cap
        entity.item = pile.copyWithCount(cap)
        // Vanilla discards the entity once it has taken everything it was shown; the rest of the
        // pile still lies here, so the entity is kept (see EntityMixin) and refilled below.
        access.dropstacker_collecting = true
        access.dropstacker_discardedWhileCollecting = false
        try {
            return action()
        } finally {
            access.dropstacker_collecting = false
            returnOverflow(entity, pile, overflow, tookAll = access.dropstacker_discardedWhileCollecting)
            access.dropstacker_discardedWhileCollecting = false
        }
    }

    private fun returnOverflow(entity: ItemEntity, pile: ItemStack, overflow: Int, tookAll: Boolean) {
        if (!entity.isRemoved) {
            // A pickup that discarded the entity took everything it was shown. What the entity
            // still holds must not be counted: some mobs (dolphins, pandas) keep the very same
            // ItemStack instance in their hand, so reading it back would duplicate those items.
            // Otherwise pickups shrink the stack in place, so read it fresh. Either way a new
            // instance is written so the change syncs and the label refreshes.
            val left = if (tookAll) ItemStack.EMPTY else entity.item
            entity.item = (if (left.isEmpty) pile else left).copyWithCount(left.count + overflow)
            return
        }

        // Removed some other way than discard(): a removed entity cannot be revived, so the rest
        // continues as a new entity in the same spot.
        val level = entity.level() as? ServerLevel ?: return
        val rest = ItemEntity(level, entity.x, entity.y, entity.z, pile.copyWithCount(overflow), 0.0, 0.0, 0.0)
        (entity as ItemEntityAccessor).dropstacker_copyStateTo(rest)
        level.addFreshEntity(rest)
    }
}
