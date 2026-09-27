package com.perry.nametagapi.mixin;

import net.minecraft.server.network.ServerPlayerConnection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Set;

/**
 * 读原版 {@code ChunkMap$TrackedEntity.seenBy} —— 这就是「谁正在追踪这个实体」的真值来源。
 * <p>
 * 目标是包私有内部类，只能用 {@code targets} 字符串来指（Loom 会做重映射）。
 */
@Mixin(targets = "net.minecraft.server.level.ChunkMap$TrackedEntity")
public interface TrackedEntityAccessor {
    @Accessor("seenBy")
    Set<ServerPlayerConnection> nametagapi$seenBy();
}
