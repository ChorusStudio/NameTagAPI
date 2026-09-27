package com.perry.nametagapi.api;

/** nametag 是否穿透方块可见 */
public enum SeeThroughStatus {
    /** 永远穿透方块可见 */
    ALWAYS,
    /** 被观察者潜行时不再穿透 */
    VANILLA,
    /** 永远不穿透方块 */
    NEVER
}
