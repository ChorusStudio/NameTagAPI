package com.perry.nametagapi.api;

import com.perry.nametagapi.impl.NameTagHolder;
import com.perry.nametagapi.impl.NameTagRegistry;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

import java.util.List;

/**
 * 唯一入口。
 * <p>
 * <b>纯服务端 mod</b>：所有方法都必须在服务端线程调用，没有任何客户端 API —— 假实体的
 * 渲染完全由原版客户端依据我们下发的包自己完成。
 */
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

    /**
     * 某个观察者当前能看到的 nametag 假实体，按渲染顺序（<b>自下而上</b>）排列。
     * <p>
     * 被观察者身上没挂 nametag、或该玩家根本没在观察它时返回空列表。
     * <p>
     * <b>服务端世界里并不存在这些实体</b>：它们只是「挂在被观察者身上的乘客」，客户端是
     * 收到 AddEntity 包之后自己把它们创建出来的。本 mod 是纯服务端 mod，不涉及任何客户端
     * 逻辑，所以这里能给的只有服务端侧的身份信息：{@link NameTagDisplay#entityId()} /
     * {@link NameTagDisplay#uuid()}，以及它对应的 {@link NameTag}。
     * <p>
     * 必须在服务端线程调用。会实时求值 {@code isVisible} / {@code priority} /
     * {@code lineHeight} / {@code textOpacity}。
     * <p>
     * 一般用不到这个，除非你知道你在做什么，否则不要调用
     */
    @Deprecated
    public static List<NameTagDisplay> displays(Entity observee, ServerPlayer observer) {
        return NameTagRegistry.displays(observee, observer);
    }

    /**
     * 当前挂着的 nametag holder
     *<p>
     * 除非你知道你在做什么，否则请不要调用，更别修改
     */
    @Deprecated
    public static NameTagHolder ofHolder(Entity observee) {
        return NameTagRegistry.ofHolder(observee);
    }
}
