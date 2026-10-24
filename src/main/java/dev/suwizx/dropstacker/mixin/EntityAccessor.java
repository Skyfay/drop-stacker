package dev.suwizx.dropstacker.mixin;

import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Java because a static accessor needs a static interface method. */
@Mixin(Entity.class)
public interface EntityAccessor {
    @Accessor("DATA_SHARED_FLAGS_ID")
    static EntityDataAccessor<Byte> dropstacker_sharedFlags() {
        throw new AssertionError();
    }
}
