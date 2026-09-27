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
    /** 显示的内容。 */
    Component content(Entity observee);

    /**
     * 文本不透明度，与背景颜色无关
     * <p>
     * 数值范围 0~255
     */
    default byte textOpacity(ServerPlayer observee) {
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
     * 内容刷新间隔（tick），0 或负数表示不主动刷新。
     * <p>
     * 内容是所有观察者共享的，所以实际刷新频率取所有观察者的<b>最小值</b>：
     * 只要有人要求 1 tick，就会每 tick 刷新。
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

    /** 固定文本的便捷实现。 */
    static NameTag simple(Component text) {
        return _ -> text;
    }
}
