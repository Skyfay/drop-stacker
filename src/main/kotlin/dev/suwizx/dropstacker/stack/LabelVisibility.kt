package dev.suwizx.dropstacker.stack

import dev.suwizx.dropstacker.config.DropStackerConfig
import dev.suwizx.dropstacker.mixin.EntityAccessor
import net.minecraft.network.syncher.SynchedEntityData.DataValue
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.item.ItemEntity

/**
 * Hides labels behind blocks without a client mod.
 *
 * A vanilla client draws a name tag through walls unless the entity is "discrete" (the crouching
 * flag, as for a sneaking player). The flag is only added to the entity data *sent to clients*;
 * the server-side entity is untouched, so the flag cannot change how the item behaves (slime
 * bounces, sculk vibrations and so on).
 */
object LabelVisibility {
    /** Bit of `Entity.FLAG_SHIFT_KEY_DOWN` in the shared flags byte. */
    const val DISCRETE_FLAG_MASK: Int = 1 shl 1

    /** Returns [values] with the discrete flag added, for a labelled item entity when configured. */
    fun withDiscreteFlag(entity: Entity, values: List<DataValue<*>>): List<DataValue<*>> {
        if (DropStackerConfig.labelThroughWalls || entity !is ItemEntity || entity.customName == null) return values

        // Always included, not only when the flags changed: a label that appears later arrives in
        // an update that carries just the name, and the client must learn the flag along with it.
        val flagsKey = EntityAccessor.dropstacker_sharedFlags()
        val flags = (entity.entityData.get(flagsKey).toInt() or DISCRETE_FLAG_MASK).toByte()
        val discrete = DataValue.create(flagsKey, flags)

        val result = ArrayList<DataValue<*>>(values.size + 1)
        values.filterTo(result) { it.id() != discrete.id() }
        result.add(discrete)
        return result
    }
}
