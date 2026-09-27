package com.perry.nametagapi.mixin;

import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.world.entity.Display;
import org.joml.Vector3fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Display 的同步字段全是 private static final，只能通过 accessor 拿到。
 * <p>
 * 注意 {@link #height()}：它同时被当作「行距抬升量」的载体
 * （见 {@code VirtualTextDisplay#liftOf} 与 {@code VehicleAttachmentMixin}）。
 * 对该字段赋值只会让渲染裁剪盒变大，不影响渲染结果。
 */
@Mixin(Display.class)
public interface DisplayAccessor {
    @Accessor("DATA_TRANSLATION_ID")
    static EntityDataAccessor<Vector3fc> translation() {
        throw new AssertionError();
    }

    @Accessor("DATA_BILLBOARD_RENDER_CONSTRAINTS_ID")
    static EntityDataAccessor<Byte> billboardRenderConstraints() {
        throw new AssertionError();
    }

    @Accessor("DATA_HEIGHT_ID")
    static EntityDataAccessor<Float> height() {
        throw new AssertionError();
    }

    @Accessor("DATA_WIDTH_ID")
    static EntityDataAccessor<Float> width() {
        throw new AssertionError();
    }

    @Accessor("DATA_VIEW_RANGE_ID")
    static EntityDataAccessor<Float> viewRange() {
        throw new AssertionError();
    }
}
