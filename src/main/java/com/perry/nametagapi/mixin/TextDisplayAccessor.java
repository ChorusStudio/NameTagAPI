package com.perry.nametagapi.mixin;

import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.world.entity.Display;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Display.TextDisplay 的同步字段 accessor。 */
@Mixin(Display.TextDisplay.class)
public interface TextDisplayAccessor {
    @Accessor("DATA_TEXT_ID")
    static EntityDataAccessor<Component> text() {
        throw new AssertionError();
    }

    @Accessor("DATA_BACKGROUND_COLOR_ID")
    static EntityDataAccessor<Integer> backgroundColor() {
        throw new AssertionError();
    }

    @Accessor("DATA_TEXT_OPACITY_ID")
    static EntityDataAccessor<Byte> textOpacity() {
        throw new AssertionError();
    }

    @Accessor("DATA_STYLE_FLAGS_ID")
    static EntityDataAccessor<Byte> styleFlags() {
        throw new AssertionError();
    }
}
