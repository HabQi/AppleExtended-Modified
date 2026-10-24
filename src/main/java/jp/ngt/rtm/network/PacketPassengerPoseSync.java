/*
 * AppleExtended-Modified
 *
 * Modifications (2026): HabQi.
 * See LICENSE.md in the project root for license details.
 */

package jp.ngt.rtm.network;

import io.netty.buffer.ByteBuf;
import jp.apple.train.PassengerPose;
import jp.apple.train.PassengerPoseClient;
import jp.apple.train.PassengerPoseState;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

import java.util.UUID;

/** Server-authoritative pose update for one player. */
public class PacketPassengerPoseSync
        implements IMessage, IMessageHandler<PacketPassengerPoseSync, IMessage> {
    private UUID playerId;
    private byte poseId;
    private byte heightStep;

    public PacketPassengerPoseSync() {
    }

    public PacketPassengerPoseSync(UUID playerId, PassengerPoseState state) {
        this.playerId = playerId;
        this.poseId = state.getPose().getNetworkId();
        this.heightStep = (byte) state.getHeightStep();
    }

    @Override
    public void toBytes(ByteBuf buffer) {
        buffer.writeLong(this.playerId.getMostSignificantBits());
        buffer.writeLong(this.playerId.getLeastSignificantBits());
        buffer.writeByte(this.poseId);
        buffer.writeByte(this.heightStep);
    }

    @Override
    public void fromBytes(ByteBuf buffer) {
        this.playerId = new UUID(buffer.readLong(), buffer.readLong());
        this.poseId = buffer.readByte();
        this.heightStep = buffer.readByte();
    }

    @Override
    public IMessage onMessage(PacketPassengerPoseSync message, MessageContext context) {
        UUID playerId = message.playerId;
        PassengerPoseState state = new PassengerPoseState(
                PassengerPose.fromNetworkId(message.poseId), message.heightStep);
        com.anatawa12.fixRtm.ThreadUtil.runOnClientThread(
                () -> PassengerPoseClient.apply(playerId, state));
        return null;
    }
}
