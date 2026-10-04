package com.perry.nametagapi.mixin;

import com.perry.nametagapi.impl.NameTagRegistry;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * 出站包唯一入口：把假乘客合并进 SetPassengers。
 * <p>
 * 这个回调可能在非服务端线程执行，所以 NameTagRegistry#rewrite 及其下游
 * 只能读并发快照，不能改游戏状态。
 * <p>
 * {@code name = "packet"} 依赖目标方法带参数名（Loom 从官方映射补上）。升级 Minecraft /
 * Loader 后若这里报「找不到参数」或改错了参数，优先检查这一处。
 */
@Mixin(value = ServerCommonPacketListenerImpl.class, priority = 1100)
public abstract class PacketSendMixin {
    @ModifyVariable(
            method = "send(Lnet/minecraft/network/protocol/Packet;Lio/netty/channel/ChannelFutureListener;)V",
            at = @At("HEAD"),
            argsOnly = true,
            name = "packet"
    )
    private Packet<?> nametagapi$mergePassengers(Packet<?> packet) {
        if (!(((Object) this) instanceof ServerGamePacketListenerImpl connection)) {
            return packet;
        }
        return NameTagRegistry.rewrite(connection.player, packet);
    }
}
