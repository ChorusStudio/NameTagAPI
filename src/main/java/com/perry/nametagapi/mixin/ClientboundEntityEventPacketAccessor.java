package com.perry.nametagapi.mixin;

import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ClientboundEntityEventPacket.class)
public interface ClientboundEntityEventPacketAccessor {
    @Mutable
    @Accessor
    void setEntityId(int id);

    @Mutable
    @Accessor
    void setEventId(byte eventId);
}
