package com.perry.nametagapi.api;

import java.util.UUID;

/**
 * 某个观察者眼里的一个 nametag「假实体」快照，是 {@link NameTags#displays} 返回的 map 里的
 * value（key 是这条 nametag 的 identifier）。
 * <p>
 * <b>服务端世界里并不存在这个实体</b>：我们只保留它的 id / uuid，真正用于渲染的
 * {@link net.minecraft.world.entity.Display.TextDisplay} 是客户端在收到 AddEntity 包之后
 * 自行创建的（作为被观察者的乘客）。
 * <p>
 * <b>本 mod 是纯服务端 mod</b>（{@code environment = server}，没有任何客户端入口），
 * 所以这里给的也只有服务端能用的 {@link #entityId()} / {@link #uuid()}：要在服务端对它做
 * 别的事（自定义包、统计、调试指令……），用这两个值自己构造即可；客户端那一侧不属于本
 * mod 的职责。
 *
 * @param nametag  这份显示对应的 {@link NameTag}（逐观察者求值的那一条）
 * @param entityId 假实体的 entity id
 * @param uuid     假实体的 uuid（AddEntity 包里下发的那个）
 * @param lift     该行的抬升量（格）：渲染时写在 Display 的 translation 上
 */
public record NameTagDisplay(NameTag nametag, int entityId, UUID uuid, float lift) {
}
