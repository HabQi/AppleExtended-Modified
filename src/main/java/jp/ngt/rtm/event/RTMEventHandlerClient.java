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

package jp.ngt.rtm.event;

import jp.apple.train.PassengerPose;
import jp.apple.train.PassengerPoseClient;
import jp.apple.train.PassengerPoseRenderState;
import jp.apple.train.PassengerPoseState;
import jp.ngt.ngtlib.renderer.GLHelper;
import jp.ngt.ngtlib.util.NGTUtilClient;
import jp.ngt.rtm.ClientProxy;
import jp.ngt.rtm.RTMCore;
import jp.ngt.rtm.RTMSound;
import jp.ngt.rtm.block.decoration.DecorationStore;
import jp.ngt.rtm.entity.RenderBullet;
import jp.ngt.rtm.entity.npc.EntityNPC;
import jp.ngt.rtm.entity.train.parts.EntityFloor;
import jp.ngt.rtm.entity.train.parts.EntityTrainWalkableFloor;
import jp.ngt.rtm.entity.train.parts.EntityVehiclePart;
import jp.ngt.rtm.entity.vehicle.EntityVehicleBase;
import jp.ngt.rtm.gui.GuiIngameCustom;
import jp.ngt.rtm.gui.camera.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.entity.RenderLivingBase;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraftforge.client.event.*;
import net.minecraftforge.client.event.sound.SoundLoadEvent;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent.RenderTickEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import net.minecraftforge.fml.relauncher.ReflectionHelper;
import org.lwjgl.opengl.GL11;

import java.lang.reflect.Field;
import java.util.IdentityHashMap;
import java.util.Map;

@SideOnly(Side.CLIENT)
public final class RTMEventHandlerClient {
    private static final Field RIDING_ENTITY_FIELD = ReflectionHelper.findField(
            Entity.class, "ridingEntity", "field_184239_as");
    private final GuiIngameCustom guiInGame;
    private final Map<EntityPlayer, Entity> trainWalkRenderVehicles = new IdentityHashMap<>();
    private final Map<EntityPlayer, LightmapState> fullBrightPlayerLightmaps = new IdentityHashMap<>();
    private LightmapState firstPersonHandLightmap;

    public RTMEventHandlerClient(Minecraft par1) {
        this.guiInGame = new GuiIngameCustom(par1);
    }

	/*@SubscribeEvent
	public void onTick(TickEvent.RenderTickEvent event)
	{
		;
	}*/

    @SubscribeEvent
    public void onTick(RenderWorldLastEvent event)//entityRenderer.renderWorldPassの最後
    {
        byte viewMode = ClientProxy.getViewMode(NGTUtilClient.getMinecraft().player);
        if (viewMode == 4) {
            Camera.INSTANCE.onRenderWorldPost();
        }
    }

    @SubscribeEvent
    public void onRenderGui(RenderGameOverlayEvent.Pre event)//GuiIngame, GuiIngameForge
    {
        this.restoreFirstPersonHandBrightness();
        byte viewMode = ClientProxy.getViewMode(NGTUtilClient.getMinecraft().player);
        if (viewMode == 4) {
            Camera.INSTANCE.onRenderGameOverlayPre();
        }
        this.guiInGame.onRenderGui(event);
    }

    @SubscribeEvent
    public void onUpdateFOV(FOVUpdateEvent event)//EntityRenderer.updateFovModifierHand()
    {
        event.setNewfov(RTMCore.proxy.getFov(event.getEntity(), event.getFov()));
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onCameraSetup(EntityViewRenderEvent.CameraSetup event) {
        Minecraft minecraft = NGTUtilClient.getMinecraft();
        EntityPlayer player = minecraft.player;
        if (player == null || event.getEntity() != player || minecraft.gameSettings.thirdPersonView != 0) {
            return;
        }

        EntityVehicleBase<?> vehicle = this.getRiddenVehicle(player);
        if (vehicle == null || vehicle.isDead || vehicle.world != player.world) {
            return;
        }

        float partialTick = (float) event.getRenderPartialTicks();
        event.setRoll(event.getRoll() - vehicle.getInterpolatedRoll(partialTick));
    }

    private EntityVehicleBase<?> getRiddenVehicle(EntityPlayer player) {
        Entity riding = player.getRidingEntity();
        if (riding == null) {
            riding = this.trainWalkRenderVehicles.get(player);
        }
        if (riding instanceof EntityVehicleBase) {
            return (EntityVehicleBase<?>) riding;
        }
        if (riding instanceof EntityVehiclePart) {
            return ((EntityVehiclePart) riding).getParentVehicle();
        }
        return null;
    }

    @SubscribeEvent
    public void onLoadSound(SoundLoadEvent event) {
        RTMSound.registerSoundDomains();
    }

    @SubscribeEvent
    public void onTextureStitch(TextureStitchEvent.Pre event) {
        DecorationStore.INSTANCE.onTextureStitch(event.getMap());
    }

    private boolean isPlayerSittingSeat(EntityPlayer player, byte type) {
        if (player.isRiding() && player.getRidingEntity() instanceof EntityFloor) {
            if (((EntityFloor) player.getRidingEntity()).getSeatType() == type) {
                return true;
            }
        }
        return false;
    }

    /**
     * RenderLivingBase suppresses limb swing for every riding entity, even
     * when the mount explicitly requests a standing pose. Hide only the
     * rendering-time riding reference so train walkers use the normal model
     * animation path; the carrier passenger relation remains untouched.
     */
    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public void enableTrainWalkPlayerAnimation(RenderPlayerEvent.Pre event) {
        if (event.isCanceled()) {
            return;
        }

        EntityPlayer player = event.getEntityPlayer();
        Entity riding = player.getRidingEntity();
        if (riding instanceof EntityTrainWalkableFloor) {
            this.restoreTrainWalkRenderVehicle(player);
            PassengerPoseState poseState = PassengerPoseClient.getState(player);
            PassengerPoseRenderState.begin(player, (EntityTrainWalkableFloor) riding, poseState);
            if (poseState.getPose() != PassengerPose.SITTING) {
                this.trainWalkRenderVehicles.put(player, riding);
                this.setRenderingRidingEntity(player, null);
            }
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void restoreTrainWalkPlayerAnimation(RenderPlayerEvent.Post event) {
        this.restoreTrainWalkRenderVehicle(event.getEntityPlayer());
        PassengerPoseRenderState.end(event.getEntityPlayer());
    }

    /** Keeps only RTM vehicle passengers full-bright for the duration of their player render. */
    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public void enableRiddenPlayerFullBrightness(RenderPlayerEvent.Pre event) {
        if (event.isCanceled() || this.getRiddenVehicle(event.getEntityPlayer()) == null) {
            return;
        }

        EntityPlayer player = event.getEntityPlayer();
        this.fullBrightPlayerLightmaps.put(player,
                new LightmapState(OpenGlHelper.lastBrightnessX, OpenGlHelper.lastBrightnessY));
        GLHelper.setLightmapMaxBrightness();
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void restoreRiddenPlayerBrightness(RenderPlayerEvent.Post event) {
        LightmapState state = this.fullBrightPlayerLightmaps.remove(event.getEntityPlayer());
        if (state != null) {
            OpenGlHelper.setLightmapTextureCoords(
                    OpenGlHelper.lightmapTexUnit, state.brightnessX, state.brightnessY);
        }
    }

    /** First-person hands and held items use a separate renderer from the player model. */
    @SubscribeEvent(priority = EventPriority.HIGHEST, receiveCanceled = true)
    public void enableRiddenFirstPersonHandBrightness(RenderHandEvent event) {
        EntityPlayer player = NGTUtilClient.getMinecraft().player;
        if (player == null || this.getRiddenVehicle(player) == null) {
            return;
        }

        this.restoreFirstPersonHandBrightness();
        this.firstPersonHandLightmap =
                new LightmapState(OpenGlHelper.lastBrightnessX, OpenGlHelper.lastBrightnessY);
        GLHelper.setLightmapMaxBrightness();
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void restoreTrainWalkPlayerAnimationAtRenderEnd(RenderTickEvent event) {
        if (event.phase == net.minecraftforge.fml.common.gameevent.TickEvent.Phase.END) {
            this.restoreFirstPersonHandBrightness();
            if (!this.trainWalkRenderVehicles.isEmpty()) {
                for (EntityPlayer player : new java.util.ArrayList<>(this.trainWalkRenderVehicles.keySet())) {
                    this.restoreTrainWalkRenderVehicle(player);
                }
            }
            PassengerPoseRenderState.clear();
        }
    }

    private void restoreFirstPersonHandBrightness() {
        if (this.firstPersonHandLightmap != null) {
            OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit,
                    this.firstPersonHandLightmap.brightnessX, this.firstPersonHandLightmap.brightnessY);
            this.firstPersonHandLightmap = null;
        }
    }

    private void restoreTrainWalkRenderVehicle(EntityPlayer player) {
        Entity riding = this.trainWalkRenderVehicles.remove(player);
        if (riding != null && player.getRidingEntity() == null) {
            this.setRenderingRidingEntity(player, riding);
        }
    }

    private void setRenderingRidingEntity(EntityPlayer player, Entity riding) {
        try {
            RIDING_ENTITY_FIELD.set(player, riding);
        } catch (IllegalAccessException exception) {
            throw new IllegalStateException("Unable to update train-walk rendering state", exception);
        }
    }

    private static final class LightmapState {
        final float brightnessX;
        final float brightnessY;

        LightmapState(float brightnessX, float brightnessY) {
            this.brightnessX = brightnessX;
            this.brightnessY = brightnessY;
        }
    }

    @SubscribeEvent
    public void onRenderPlayer(RenderPlayerEvent.Pre event) {
        if (this.isPlayerSittingSeat(event.getEntityPlayer(), (byte) 3))//寝台
        {
            GL11.glPushMatrix();
            event.getRenderer().getMainModel().isRiding = false;
            GL11.glRotatef(90.0F, 0.0F, 0.0F, 1.0F);
            GL11.glRotatef(270.0F, 0.0F, 1.0F, 0.0F);
        }
    }

    @SubscribeEvent
    public void onRenderPlayer(RenderPlayerEvent.Post event) {
        if (this.isPlayerSittingSeat(event.getEntityPlayer(), (byte) 3))//寝台
        {
            event.getRenderer().getMainModel().isRiding = true;
            GL11.glPopMatrix();
        }

        //RenderEventHandler.INSTANCE.renderEntity(event.entity, event.renderer, event.x, event.y, event.z);

        RenderBullet.onPlayerRender(event.getEntityPlayer(), false);
    }

    @SubscribeEvent
    public void onRenderLiving(RenderLivingEvent.Pre event) {
        this.renderEntityPre(event.getEntity(), event.getRenderer(), event.getX(), event.getY(), event.getZ());
    }

    @SubscribeEvent
    public void onRenderLiving(RenderLivingEvent.Post event) {
        EntityLivingBase entity = event.getEntity();
        this.renderEntityPost(entity, event.getRenderer(), event.getX(), event.getY(), event.getZ());

        if (entity instanceof EntityNPC) {
            RenderBullet.onNPCRender((EntityNPC) entity, event.getX(), event.getY(), event.getZ());
        }
    }

    @SubscribeEvent
    public void onRenderPlayerHand(RenderHandEvent event) {
        EntityPlayer player = NGTUtilClient.getMinecraft().player;
        byte viewMode = ClientProxy.getViewMode(player);
        if (viewMode >= 0 && viewMode < 3) {
            event.setCanceled(true);
        }

        RenderBullet.onPlayerRender(player, true);
    }

    //NVD////////////////////////////////////////////////////////////////////////

    public void renderEntityPre(EntityLivingBase entity, RenderLivingBase renderer, double x, double y, double z) {
        if (!this.isPlayerWearedNVD(entity)) {
            return;
        }

        GLHelper.disableLighting();
        //GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glColor4f(1.0F, 0.5F, 0.4F, 1.0F);
        GLHelper.setLightmapMaxBrightness();
    }

    public void renderEntityPost(EntityLivingBase entity, RenderLivingBase renderer, double x, double y, double z) {
        if (!this.isPlayerWearedNVD(entity)) {
            return;
        }

        //GL11.glEnable(GL11.GL_TEXTURE_2D);
        GLHelper.enableLighting();
    }

    private boolean isPlayerWearedNVD(EntityLivingBase entity) {
        EntityPlayer viewer = NGTUtilClient.getMinecraft().player;
        return entity != viewer && ClientProxy.getViewMode(viewer) == 3;
    }

    /////////////////////////////////////////////////////////////////////////////
}
