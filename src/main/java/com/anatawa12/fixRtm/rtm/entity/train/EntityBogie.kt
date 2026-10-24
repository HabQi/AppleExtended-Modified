/// Copyright (c) 2020 anatawa12 and other contributors
/// This file is part of fixRTM, released under GNU LGPL v3 with few exceptions
/// See LICENSE at https://github.com/fixrtm/fixRTM for more details

package com.anatawa12.fixRtm.rtm.entity.train

import com.anatawa12.fixRtm.addEntityCrashInfoAboutModelSet
import com.anatawa12.fixRtm.network.NetworkHandler
import com.anatawa12.fixRtm.network.NotifyUntracked
import jp.ngt.rtm.entity.train.EntityBogie
import net.minecraft.crash.CrashReportCategory

@Suppress("EXTENSION_SHADOWED_BY_MEMBER", "unused")
fun EntityBogie.onRemovedFromWorld() {
    if (!world.isRemote)
        return

    // Ignore removal of an old instance when the client has already installed
    // a replacement with the same entity ID.
    val current = world.getEntityByID(entityId)
    if (current != null && current !== this && !current.isDead)
        return

    NetworkHandler.sendPacketServer(NotifyUntracked(entityId))
}

@Suppress("EXTENSION_SHADOWED_BY_MEMBER", "unused")
fun EntityBogie.addEntityCrashInfo(category: CrashReportCategory) =
    addEntityCrashInfoAboutModelSet(category) { train?.resourceState?.resourceSet?.config }
