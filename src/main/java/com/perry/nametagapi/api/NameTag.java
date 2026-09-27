package com.perry.nametagapi.api;

import com.perry.nametagapi.NameTagConfig;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

/**
 * 一个NameTag
 * <p>
 * 如果不需要高度自定义，只需要最基础的版本，直接 {@link NameTag#simple(Component)} 即可
 * <p>
 * 如果需要更多内容，则最好implement这个接口来进行自定义
 * <p>
 * 拿到NameTag之后，去{@link NameTags} 处附着在一个实体上即可
 */
public interface NameTag {
    /**
     * 显示的内容。逐观察者求值：同一条 nametag 可以给不同玩家显示不同内容，
     * 每个观察者按自己的 {@link #updateIntervalTicks} 独立刷新，互不影响。
     * <p>
     * {@link #updateIntervalTicks} 为 1 时这里会<b>每 tick、对该观察者</b>调用一次，
     * 所以实现要廉价、且不要有副作用（别改状态、打日志、发东西）。
     * 该玩家看不到这条 nametag（{@link #isVisible} 为 false）时不会被调用。
     */
    Component content(Entity observee, ServerPlayer observer);

    /**
     * 文本不透明度，与背景颜色无关
     * <p>
     * 数值范围 0~255
     */
    default byte textOpacity(ServerPlayer observer) {
        return (byte) 255;
    }

    /** 该观察者能不能看到这条 nametag。 */
    default boolean isVisible(Entity observee, ServerPlayer observer) {
        return true;
    }

    /** 本条相对上一条再抬高多少格。 */
    default double lineHeight(ServerPlayer observer) {
        return NameTagConfig.DEFAULT_LINE_HEIGHT;
    }

    /**
     * <b>该观察者</b>的内容刷新间隔（tick），0 表示只给他算一次。
     * <p>
     * 逐观察者生效：A 设 1、B 设 20，两边各按自己的节奏刷新，不会互相牵连。
     * <p>
     * 间隔会被缓存（最短 20 tick 重算一次，观察者集合一变立刻重算），所以运行时动态改
     * 这个返回值最多延迟 20 tick 生效。返回 0 也不会展示旧内容：一条行对该玩家
     * 「隐藏再显示」时我们会丢掉内容缓存并重算一次。
     */
    default int updateIntervalTicks(ServerPlayer observer) {
        return 1;
    }

    /** 背景色（ARGB），null 表示使用原版默认。 */
    default Integer backgroundColor(ServerPlayer observer) {
        return null;
    }

    /** 穿透方块可见性。 */
    default SeeThroughStatus seeThroughStatus(ServerPlayer observer) {
        return SeeThroughStatus.ALWAYS;
    }

    /**
     * 排放优先级：数值越大越靠近玩家头部（视觉上也越低）。
     * <p>
     * 同一被观察者上的多条 nametag 会按优先级<b>降序</b>自下而上排列；
     * 优先级相同的保持 attach 顺序（先挂的在下面）。默认 0。
     * <p>
     * 逐玩家求值，所以同一条 nametag 对不同观察者可以处在不同层。
     */
    default int priority(ServerPlayer observer) {
        return 0;
    }

    /** 固定文本的便捷实现（对所有观察者显示同一份内容）。 */
    static NameTag simple(Component text) {
        return (_, _) -> text;
    }
}
