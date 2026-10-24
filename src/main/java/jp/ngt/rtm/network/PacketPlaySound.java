/*
 * AppleExtended
 *
 * Original code (c) 2020 anatawa12 and other contributors.
 * Modifications (c) 2026 Applepie.
 * Further modifications (2026): HabQi.
 *
 * This file is part of AppleExtended, which is a derivative work of fixRTM.
 * Both are licensed under the GNU Lesser General Public License version 3.
 * See LICENSE.md in the project root for license details.
 */

package jp.ngt.rtm.network;

import io.netty.buffer.ByteBuf;
import jp.ngt.ngtlib.network.PacketCustom;
import jp.ngt.ngtlib.util.NGTUtil;
import jp.ngt.rtm.RTMCore;
import net.minecraft.entity.Entity;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.network.ByteBufUtils;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

public class PacketPlaySound extends PacketCustom implements IMessageHandler<PacketPlaySound, IMessage> {
    private String sound;
    private float volume;
    private float pitch;
    private boolean horn;

    public PacketPlaySound() {
    }

    public PacketPlaySound(Entity par1, String sound2, float par3, float par4) {
        this(par1, sound2, par3, par4, false);
    }

    public PacketPlaySound(Entity par1, String sound2, float par3, float par4, boolean horn) {
        super(par1);
        this.sound = sound2;
        this.volume = par3;
        this.pitch = par4;
        this.horn = horn;
    }

    public PacketPlaySound(TileEntity par1, String sound2, float par3, float par4) {
        super(par1);
        this.sound = sound2;
        this.volume = par3;
        this.pitch = par4;
    }

    @Override
    public void toBytes(ByteBuf buffer) {
        super.toBytes(buffer);
        ByteBufUtils.writeUTF8String(buffer, this.sound);
        buffer.writeFloat(this.volume);
        buffer.writeFloat(this.pitch);
        buffer.writeBoolean(this.horn);
    }

    @Override
    public void fromBytes(ByteBuf buffer) {
        super.fromBytes(buffer);
        this.sound = ByteBufUtils.readUTF8String(buffer);
        this.volume = buffer.readFloat();
        this.pitch = buffer.readFloat();
        this.horn = buffer.readBoolean();
    }

    @Override
    public IMessage onMessage(PacketPlaySound message, MessageContext ctx) {
        com.anatawa12.fixRtm.ThreadUtil.runOnClientThread(() -> doMessage(message));
        return null;
    }

    private void doMessage(PacketPlaySound message) {
        World world = NGTUtil.getClientWorld();
        if (message.forEntity()) {
            Entity entity = message.getEntity(world);
            if (entity != null) {
                if (message.horn) {
                    RTMCore.proxy.playHornSound(entity, message.sound, message.volume, message.pitch);
                } else {
                    RTMCore.proxy.playSound(entity, message.sound, message.volume, message.pitch);
                }
            }
        } else {
            TileEntity entity = message.getTileEntity(world);
            if (entity != null) {
                RTMCore.proxy.playSound(entity, message.sound, message.volume, message.pitch);
            }
        }
    }
}
