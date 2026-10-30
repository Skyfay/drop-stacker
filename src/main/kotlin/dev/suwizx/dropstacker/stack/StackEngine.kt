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
        // A thrown drop keeps its throw: it only joins a pile once it has landed (or floats).
        // Mob and block drops have no thrower and still merge the moment they spawn.
        if (access.dropstacker_hasThrower() && !entity.onGround() && !entity.isInWater) return false
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

        // The biggest pile stays where it is and takes the others in, as vanilla merges into the
        // larger stack. Otherwise a single item that happens to scan first pulls a whole pile over
        // to itself, and the pile visibly jumps.
        var receiver = host
        for (other in scratch) {
            if (other.item.count > receiver.item.count) receiver = other
        }
        if (receiver !== host) {
            scratch.remove(receiver)
            scratch.add(host)
        }

        val receiverStack = receiver.item
        val startCount = receiverStack.count
        var pending = startCount
        var absorbed = 0

        for (other in scratch) {
            if (pending >= max) break
            val taken = drain(receiver, other, max - pending)
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
        receiver.item = receiverStack.copyWithCount(pending)

        mergesPerformed++
        entitiesCollapsed += absorbed
        (receiver as ItemEntityAccessor).dropstacker_resetBackoff()
        return absorbed
    }

    /**
     * Lets the piles around a freshly spawned [newcomer] take it in, then absorbs whatever is left
     * around it as before.
     *
     * The newcomer joins an existing pile rather than swallowing it, so the entity a player just
     * dropped still holds exactly what was dropped. Logging mods (Ledger) record that entity's
     * stack as the drop; when it swallowed the pile, they logged the whole pile, and piles above
     * 99 failed to serialize at all.
     */
    fun joinNeighbours(newcomer: ItemEntity) {
        if (!DropStackerConfig.enabled) return
        val level = newcomer.level() as? ServerLevel ?: return

        val stack = newcomer.item
        if (!isMergeable(newcomer, stack)) return

        val max = effectiveMax(stack)
        val target = (newcomer as ItemEntityAccessor).dropstacker_target()
        val radius = DropStackerConfig.radius
        val box = newcomer.boundingBox.inflate(radius, DropStackerConfig.verticalRadius, radius)

        scratch.clear()
        level.getEntities(
            ITEM_ENTITY_TEST,
            box,
            { other ->
                other !== newcomer &&
                    ItemStack.isSameItemSameComponents(stack, other.item) &&
                    Objects.equals(target, (other as ItemEntityAccessor).dropstacker_target()) &&
                    isMergeable(other, other.item)
            },
            scratch,
            DropStackerConfig.maxMergePerPass,
        )

        for (pile in scratch) {
            if (!newcomer.isAlive) break
            val pileStack = pile.item
            val taken = drain(pile, newcomer, max - pileStack.count)
            if (taken <= 0) continue
            // New instance, as in absorbNeighbours, so the change syncs.
            pile.item = pileStack.copyWithCount(pileStack.count + taken)
            mergesPerformed++
            if (!newcomer.isAlive) entitiesCollapsed++
            (pile as ItemEntityAccessor).dropstacker_resetBackoff()
        }
        scratch.clear()

        if (newcomer.isAlive) absorbNeighbours(newcomer)
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
     * Runs [action] — a vanilla pickup such as a player, a hopper or a mob collecting [entity] —
     * while the entity holds at most one vanilla-sized stack, then puts the rest of the pile back.
     *
     * Vanilla never sees a ground stack above `maxStackSize`, so several pickup paths move the whole
     * stack into a single slot: hoppers and `SimpleContainer` (villagers, allays, piglins) then trim
     * it to the slot limit and delete the rest, and dolphins, pandas and raiders hold it in a hand
     * slot that cannot be saved above 99. Logging mods (Ledger) record picked-up stacks with the
     * vanilla codec, which rejects counts above 99. Handing every pickup one stack at a time keeps
     * all of that intact; the pile is simply collected over several pickups.
     *
     * The count is lowered in place, not through `setItem`, so a pickup that takes nothing (a full
     * inventory standing on a pile) leaves no trace and sends no packet.
     */
    fun <T> withVanillaSizedPile(entity: ItemEntity, action: () -> T): T {
        val pile = entity.item
        val cap = pile.maxStackSize
        val access = entity as ItemEntityAccessor
        if (pile.count <= cap || access.dropstacker_collecting) return action()

        val overflow = pile.count - cap
        pile.count = cap
        // Vanilla discards the entity once it has taken everything it was shown; the rest of the
        // pile still lies here, so the entity is kept (see EntityMixin) and refilled below.
        access.dropstacker_collecting = true
        access.dropstacker_discardedWhileCollecting = false
        try {
            return action()
        } finally {
            access.dropstacker_collecting = false
            returnOverflow(entity, pile, cap, overflow, tookAll = access.dropstacker_discardedWhileCollecting)
            access.dropstacker_discardedWhileCollecting = false
        }
    }

    private fun returnOverflow(entity: ItemEntity, pile: ItemStack, shown: Int, overflow: Int, tookAll: Boolean) {
        if (!entity.isRemoved) {
            val current = entity.item
            when {
                // A pickup that discarded the entity took everything it was shown. Nothing it left
                // behind may be counted: some mobs (dolphins, pandas) keep the very same ItemStack
                // instance in their hand, and the player pickup resets its count after discarding.
                tookAll -> entity.item = pile.copyWithCount(overflow)
                // Nothing was taken: restore in place, so nothing is synced.
                current === pile && pile.count == shown -> pile.count = shown + overflow
                // Partly taken, in place or through setItem: write a new instance so the change
                // syncs and the label refreshes.
                else -> entity.item = (if (current.isEmpty) pile else current).copyWithCount(current.count + overflow)
            }
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
