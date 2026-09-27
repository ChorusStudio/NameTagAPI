package com.perry.nametagapi.api;

import com.perry.nametagapi.impl.NameTagRegistry;
import net.minecraft.world.entity.Entity;

import java.util.List;

/** 唯一入口。所有方法都必须在服务端线程调用。 */
public final class NameTags {
    private NameTags() {
    }

    /** 给实体挂一条 nametag。不会立刻发包，由 tick 统一差分下发。 */
    public static void attach(Entity observee, NameTag nametag) {
        NameTagRegistry.attach(observee, nametag);
    }

    /** 摘掉指定的一条。 */
    public static void detach(Entity observee, NameTag nametag) {
        NameTagRegistry.detach(observee, nametag);
    }

    /** 摘掉全部。 */
    public static void clear(Entity observee) {
        NameTagRegistry.clear(observee);
    }

    /** 当前挂着的 nametag（快照）。 */
    public static List<NameTag> of(Entity observee) {
        return NameTagRegistry.of(observee);
    }
}
