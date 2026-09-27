package com.perry.nametagapi.mixin;

import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.world.entity.Display;
import org.joml.Vector3fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Display 的同步字段全是 private static final，只能通过 accessor 拿到。
 * <p>
 * 这里只保留真正在用的三个（accessor 不用也不会有运行时开销，但留着容易误导）：
 * <ul>
 *   <li>{@link #billboardRenderConstraints()} —— 固定 CENTER，让文字始终正对相机</li>
 *   <li>{@link #translation()} —— 行距抬升量（屏幕空间偏移）</li>
 *   <li>{@link #height()} —— 只用来撑大渲染裁剪盒，写 0 会让多行标签在屏幕边缘被裁掉</li>
 * </ul>
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
}
