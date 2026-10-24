/*
 * AppleExtended-Modified
 *
 * Modifications (2026): HabQi.
 * See LICENSE.md in the project root for license details.
 */

/* AppleExtended - validated client input for train interior walking. */
package jp.ngt.rtm.network;

import io.netty.buffer.ByteBuf;
import jp.ngt.ngtlib.io.NGTLog;
import jp.ngt.rtm.entity.train.parts.EntityTrainWalkableFloor;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

public class PacketTrainWalkInput implements IMessage, IMessageHandler<PacketTrainWalkInput, IMessage> {
    private int carrierEntityId;
    private float localMoveX;
    private float localMoveZ;
    private long inputSequence;

    public PacketTrainWalkInput() {
    }

    public PacketTrainWalkInput(int carrierEntityId, float localMoveX, float localMoveZ, long inputSequence) {
        this.carrierEntityId = carrierEntityId;
        this.localMoveX = localMoveX;
        this.localMoveZ = localMoveZ;
        this.inputSequence = inputSequence;
    }

    @Override
    public void toBytes(ByteBuf buffer) {
        buffer.writeInt(this.carrierEntityId);
        buffer.writeFloat(this.localMoveX);
        buffer.writeFloat(this.localMoveZ);
        buffer.writeLong(this.inputSequence);
    }

    @Override
    public void fromBytes(ByteBuf buffer) {
        this.carrierEntityId = buffer.readInt();
        this.localMoveX = buffer.readFloat();
        this.localMoveZ = buffer.readFloat();
        this.inputSequence = buffer.readLong();
    }

    @Override
    public IMessage onMessage(PacketTrainWalkInput message, MessageContext context) {
        EntityPlayer player = context.getServerHandler().player;
        com.anatawa12.fixRtm.ThreadUtil.runOnServerThread(() -> handle(message, player));
        return null;
    }

    private static void handle(PacketTrainWalkInput message, EntityPlayer player) {
        World world = player.world;
        Entity entity = world.getEntityByID(message.carrierEntityId);
        if (entity instanceof EntityTrainWalkableFloor) {
            ((EntityTrainWalkableFloor) entity).handleInput(
                    player, message.localMoveX, message.localMoveZ, message.inputSequence);
        } else {
            NGTLog.debug("[TrainWalkDiag] side=S event=PACKET_DROP tick=%d player=%s packet=INPUT "
                            + "floor=%d seq=%d reason=carrier-not-found actual=%s riding=%d",
                    world.getTotalWorldTime(), player.getName(), message.carrierEntityId,
                    message.inputSequence, entity == null ? "null" : entity.getClass().getName(),
                    player.getRidingEntity() == null ? -1 : player.getRidingEntity().getEntityId());
        }
    }
}
