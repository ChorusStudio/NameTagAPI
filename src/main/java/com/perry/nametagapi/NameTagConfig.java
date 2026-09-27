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
     * 出站 SetPassengers 的自愈重发周期（tick），&lt;= 0 表示关闭（<b>默认 0</b>）。
     * <p>
     * 只在一种服务器上才需要打开：还有别的 mod 会用「重建数组」而不是「追加」的方式
     * 写乘客包、或者绕过 packet listener 直接发 —— 那会把我们塞进去的假乘客静默丢掉，
     * 开启后每 N tick 无条件重发一次即可自动修复。
     * <p>
     * 代价是持续的冗余包，而且每次 SetPassengers 都会让客户端 eject + 重新挂载全部乘客，
     * 所以正常服务器保持 0。运行时可改（非 final）。
     */
    public static volatile int REASSERT_INTERVAL_TICKS = 0;

    /** 是否给被观察者本人显示 nametag（原版不显示自己的）。 */
    public static final boolean SHOW_SELF_NAMETAG = false;

    /** billboard 模式：始终正对相机。 */
    public static final byte BILLBOARD = (byte) Display.BillboardConstraints.CENTER.ordinal();
}
