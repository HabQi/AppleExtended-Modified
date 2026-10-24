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

package jp.apple.sound;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

@SideOnly(Side.CLIENT)
public final class SoundRangeHelper {

    private SoundRangeHelper() {
    }

    public static float calcVolume(float baseVolume, float soundRange,
                                   float x, float y, float z) {
        EntityPlayer player = Minecraft.getMinecraft().player;
        if (player == null) return baseVolume;

        float dist = distance(player, x, y, z);

        if (dist >= soundRange) return 0.0F;

        float defaultRange = 16.0F;

        if (soundRange >= defaultRange) {

            if (dist <= defaultRange) {
                return baseVolume;
            }
            return baseVolume * (soundRange - dist) / (soundRange - defaultRange);
        } else {


            return baseVolume * (soundRange - dist) / soundRange;
        }
    }

    public static float calcLinearVolume(float baseVolume, float soundRange,
                                         float x, float y, float z) {
        EntityPlayer player = Minecraft.getMinecraft().player;
        if (player == null) return baseVolume;

        return calcLinearVolumeAtDistance(baseVolume, soundRange, distance(player, x, y, z));
    }

    static float calcLinearVolumeAtDistance(float baseVolume, float soundRange, float distance) {
        if (baseVolume <= 0.0F || soundRange <= 0.0F || distance >= soundRange) {
            return 0.0F;
        }
        if (distance <= 0.0F) {
            return baseVolume;
        }
        return baseVolume * (soundRange - distance) / soundRange;
    }

    private static float distance(EntityPlayer player, float x, float y, float z) {
        float dx = (float) player.posX - x;
        float dy = (float) player.posY - y;
        float dz = (float) player.posZ - z;
        return (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}
