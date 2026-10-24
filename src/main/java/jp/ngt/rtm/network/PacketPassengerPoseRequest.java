/*
 * AppleExtended-Modified
 *
 * Modifications (2026): HabQi.
 * See LICENSE.md in the project root for license details.
 */

package jp.ngt.rtm.network;

import io.netty.buffer.ByteBuf;
import jp.apple.train.PassengerPose;
import jp.apple.train.PassengerPoseManager;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

/** Client request containing no player identity; the server uses the connection owner. */
public class PacketPassengerPoseRequest
        implements IMessage, IMessageHandler<PacketPassengerPoseRequest, IMessage> {
    private static final byte SET_POSE = 0;
    private static final byte ADJUST_HEIGHT = 1;

    private byte operation;
    private byte value;

    public PacketPassengerPoseRequest() {
    }

    public PacketPassengerPoseRequest(PassengerPose pose) {
        this.operation = SET_POSE;
        this.value = pose.getNetworkId();
    }

    public static PacketPassengerPoseRequest adjustHeight(int delta) {
        PacketPassengerPoseRequest request = new PacketPassengerPoseRequest();
        request.operation = ADJUST_HEIGHT;
        request.value = (byte) delta;
        return request;
    }

    @Override
    public void toBytes(ByteBuf buffer) {
        buffer.writeByte(this.operation);
        buffer.writeByte(this.value);
    }

    @Override
    public void fromBytes(ByteBuf buffer) {
        this.operation = buffer.readByte();
        this.value = buffer.readByte();
    }

    @Override
    public IMessage onMessage(PacketPassengerPoseRequest message, MessageContext context) {
        EntityPlayerMP player = context.getServerHandler().player;
        byte operation = message.operation;
        byte value = message.value;
        com.anatawa12.fixRtm.ThreadUtil.runOnServerThread(() -> {
            if (operation == SET_POSE) {
                PassengerPoseManager.handlePoseRequest(player, PassengerPose.fromNetworkId(value));
            } else if (operation == ADJUST_HEIGHT) {
                PassengerPoseManager.handleHeightRequest(player, value);
            }
        });
        return null;
    }
}
