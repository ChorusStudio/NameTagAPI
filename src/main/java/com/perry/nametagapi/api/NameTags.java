package com.perry.nametagapi.api;

import com.perry.nametagapi.impl.NameTagRegistry;
import net.minecraft.world.entity.Entity;

import java.util.List;

/** 唯一入口。所有方法都必须在服务端线程调用。 */
public final class NameTags {
    private NameTags() {
    }

    /**
     * 给实体挂一条 nametag。不会立刻发包，由 tick 统一差分下发。
     * <p>
     * 生命周期：实体被移除<b>或所在区块被卸载</b>时标签会被清掉（两者都走
     * {@code Entity#setRemoved}），对已处于该状态的实体调用本方法会被忽略并打一条 debug
     * 日志；玩家重连不保留（新会话、新实体）；玩家重生会自动搬过去（entity id 会变）；
     * 跨维度不需要重挂（实例和 id 都不变）。
     */
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

    /**
     * 强制重发该实体身上所有 nametag 的同步数据（对所有观察者）。
     * <p>
     * 只有一种实现需要它：{@link NameTag#content} 返回<b>同一个</b> MutableComponent
     * 实例并就地修改 —— 那种情况下结构化的 equals 检测不到变化。
     */
    public static void invalidate(Entity observee) {
        NameTagRegistry.invalidate(observee);
    }

    /** 当前挂着的 nametag（快照）。 */
    public static List<NameTag> of(Entity observee) {
        return NameTagRegistry.of(observee);
    }
}
