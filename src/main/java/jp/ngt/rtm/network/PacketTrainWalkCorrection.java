/*
 * AppleExtended-Modified
 *
 * Modifications (2026): HabQi.
 * See LICENSE.md in the project root for license details.
 */

/* AppleExtended - authoritative train interior walking position. */
package jp.ngt.rtm.network;

import io.netty.buffer.ByteBuf;
import jp.ngt.ngtlib.io.NGTLog;
import jp.ngt.ngtlib.util.NGTUtil;
import jp.ngt.rtm.entity.train.parts.EntityTrainWalkableFloor;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

import java.util.UUID;

public class PacketTrainWalkCorrection implements IMessage, IMessageHandler<PacketTrainWalkCorrection, IMessage> {
    private UUID playerUUID;
    private int carrierEntityId;
    private double localX;
    private double localY;
    private double localZ;
    private long acknowledgedInputSequence;
    private long snapshotSequence;

    public PacketTrainWalkCorrection() {
    }

    public PacketTrainWalkCorrection(UUID playerUUID, int carrierEntityId, jp.ngt.ngtlib.math.Vec3 local,
                                     long acknowledgedInputSequence, long snapshotSequence) {
        this.playerUUID = playerUUID;
        this.carrierEntityId = carrierEntityId;
        this.localX = local.getX();
        this.localY = local.getY();
        this.localZ = local.getZ();
        this.acknowledgedInputSequence = acknowledgedInputSequence;
        this.snapshotSequence = snapshotSequence;
    }

    @Override
    public void toBytes(ByteBuf buffer) {
        buffer.writeLong(this.playerUUID.getMostSignificantBits());
        buffer.writeLong(this.playerUUID.getLeastSignificantBits());
        buffer.writeInt(this.carrierEntityId);
        buffer.writeDouble(this.localX);
        buffer.writeDouble(this.localY);
        buffer.writeDouble(this.localZ);
        buffer.writeLong(this.acknowledgedInputSequence);
        buffer.writeLong(this.snapshotSequence);
    }

    @Override
    public void fromBytes(ByteBuf buffer) {
        this.playerUUID = new UUID(buffer.readLong(), buffer.readLong());
        this.carrierEntityId = buffer.readInt();
        this.localX = buffer.readDouble();
        this.localY = buffer.readDouble();
        this.localZ = buffer.readDouble();
        this.acknowledgedInputSequence = buffer.readLong();
        this.snapshotSequence = buffer.readLong();
    }

    @Override
    public IMessage onMessage(PacketTrainWalkCorrection message, MessageContext context) {
        com.anatawa12.fixRtm.ThreadUtil.runOnClientThread(() -> handle(message));
        return null;
    }

    private static void handle(PacketTrainWalkCorrection message) {
        World world = NGTUtil.getClientWorld();
        if (world == null) {
            NGTLog.debug("[TrainWalkDiag] side=C event=PACKET_DROP tick=-1 player=%s packet=CORRECTION "
                            + "floor=%d snapshot=%d ack=%d reason=no-world",
                    message.playerUUID, message.carrierEntityId, message.snapshotSequence,
                    message.acknowledgedInputSequence);
            return;
        }
        Entity entity = world.getEntityByID(message.carrierEntityId);
        EntityPlayer player = world.getPlayerEntityByUUID(message.playerUUID);
        if (entity instanceof EntityTrainWalkableFloor) {
            EntityPlayer localPlayer = NGTUtil.getClientPlayer();
            ((EntityTrainWalkableFloor) entity).queuePassengerLocalPosition(
                    message.playerUUID, message.localX, message.localY, message.localZ,
                    message.acknowledgedInputSequence, message.snapshotSequence,
                    localPlayer != null && localPlayer.getUniqueID().equals(message.playerUUID));
        } else {
            NGTLog.debug("[TrainWalkDiag] side=C event=PACKET_DROP tick=%d player=%s packet=CORRECTION "
                            + "floor=%d snapshot=%d ack=%d reason=carrier-missing "
                            + "carrierActual=%s playerFound=%s riding=%d",
                    world.getTotalWorldTime(), message.playerUUID, message.carrierEntityId,
                    message.snapshotSequence, message.acknowledgedInputSequence,
                    entity == null ? "null" : entity.getClass().getName(), player != null,
                    player == null || player.getRidingEntity() == null
                            ? -1 : player.getRidingEntity().getEntityId());
        }
    }
}
