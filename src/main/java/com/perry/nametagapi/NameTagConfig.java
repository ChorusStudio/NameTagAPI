package com.perry.nametagapi;

import net.minecraft.world.entity.Display;

/** 全部可调参数。 */
public final class NameTagConfig {
    private NameTagConfig() {
    }

    /** 座位之上再抬多少格：对齐原版 nametag 的观感。 */
    public static final double VANILLA_NAMETAG_GAP = 0.3D;

    /** 单行默认高度（格）。 */
    public static final double DEFAULT_LINE_HEIGHT = 0.275D;

    /** 单行最小高度，防止参数为 0 导致完全重叠。 */
    public static final double MIN_LINE_HEIGHT = 0.05D;

    /** 假实体 spawn 时先扔到地下这么深，避免「还没吸附到座位」的那一帧在原位闪现。 */
    public static final double SPAWN_Y_OFFSET = -1000.0D;

    /**
     * 出站 SetPassengers 的自愈重发周期（tick），&lt;= 0 关闭。
     * <p>
     * 目的：如果有别的 mod 用「重建数组」而不是「追加」的方式写乘客包，或者绕过
     * listener 直接发，我们塞进去的假乘客会被静默丢掉。周期重发能自动修复。
     */
    public static final int REASSERT_INTERVAL_TICKS = 20;

    /** 观察者补发现周期（tick）：处理「实体已被追踪之后才 attach nametag」的情况。 */
    public static final int DISCOVER_INTERVAL_TICKS = 20;

    /** 是否给被观察者本人显示 nametag（原版不显示自己的）。 */
    public static final boolean SHOW_SELF_NAMETAG = false;

    /** billboard 模式：始终正对相机。 */
    public static final byte BILLBOARD = (byte) Display.BillboardConstraints.CENTER.ordinal();
}
