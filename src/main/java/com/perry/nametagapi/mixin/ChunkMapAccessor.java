package com.perry.nametagapi.mixin;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import net.minecraft.server.level.ChunkMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 读原版的实体追踪表（key = entity id，value = TrackedEntity）。
 * <p>
 * 泛型参数是包私有的 {@code ChunkMap$TrackedEntity}，没法在这里写出来，
 * 所以用裸类型 —— Mixin 只校验字段描述符。
 * <p>
 * <b>升级 Minecraft 版本时最先检查这里</b>：{@code entityMap} / {@code seenBy}
 * 这两个字段名（含 TrackedEntityAccessor 里的那个）是最容易随版本改动的地方。
 */
@Mixin(ChunkMap.class)
public interface ChunkMapAccessor {
    @SuppressWarnings("rawtypes")
    @Accessor("entityMap")
    Int2ObjectMap nametagapi$entityMap();
}
