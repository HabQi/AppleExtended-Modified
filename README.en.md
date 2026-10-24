# AppleExtended-Modified

[简体中文](README.md) | **English** | [日本語](README.ja.md)

A personal modification of AppleExtended maintained by HabQi in 2026 for Minecraft 1.12.2 / Forge. It is based on AppleExtended commit `d40d973`; the current build version is `1.0.0`.

- [This fork](https://github.com/HabQi/AppleExtended-Modified)
- [Upstream AppleExtended](https://github.com/ringo-1234/AppleExtended)
- [Building from source](BUILDING.md)
- The original upstream Japanese README and acknowledgments are preserved at the end of this document.

AppleExtended is an unofficial modification of RealTrainMod (RTM), NGTLib, and MCTE. This repository adds the fixRTM sources needed to build the project and changes passenger interactions, vehicle synchronization, and rendering. The changes below are relative to the baseline commit listed above.

## Main changes

### Walking inside trains and moving between carriages

- Adds a walkable train floor. Passengers move in vehicle-local coordinates, with their positions updated to follow the train's position, yaw, pitch, and roll.
- To enter the walkable area, hold a ticket in your main hand and right-click the train while neither sneaking nor riding another entity. Use the normal movement keys to walk inside.
- Supports moving between adjacent coupled carriages. Both carriages must permit passage at the relevant ends, and the connection must satisfy the distance and gangway-width conditions.
- Adds client-side movement prediction, server-side input handling, position correction, and passenger-position synchronization for newly added observers.
- Adjusts passenger-position and first-person camera interpolation, roll following, and walking animations, addressing the duplicate application of vanilla movement and train-interior movement.
- Replaces the old `TrainPlatformHandler` and `PlayerCameraTrain` with the new passenger and interior-movement handling.

The walkable area is a rectangle defined in the model configuration. It does not automatically detect seats, walls, or multiple floors from the carriage model. Adjust its center, height, length, and width for each train model.

### Sitting and lying poses

Adds standing, sitting, and lying states that can be selected on a walkable floor and synchronized with other players. Pose state is cleared when dismounting, logging out, dying, or changing dimensions, among other lifecycle events.

| Action | Default key | Condition |
| --- | --- | --- |
| Switch to sitting | `G` | On a walkable floor |
| Switch to lying | `H` | On a walkable floor |
| Return to standing | Jump key, default `Space` | On a walkable floor |
| Raise the lying pose | `=` | While lying; supports holding the key |
| Lower the lying pose | `-` | While lying; supports holding the key |

Bindings can be changed in the game's controls settings. Rebind them if they conflict with another mod. Pose-key text has been added in Simplified Chinese, Traditional Chinese, Japanese, English, Spanish, and Russian.

### Boarding, dismounting, logout, and login handling

- Records candidate dismount positions and adjusts collision checks and placement when dismounting.
- Handles RTM riding relationships and recovery positions when saving player data, avoiding direct reliance on a train-entity copy in the player's saved data.
- Dismounts players when they log out. On login, uses a valid recovery position and repairs references to walkable floors.
- Adds a dedicated floor-entity tracker to manage passenger lists, initial position snapshots, and subsequent synchronization.

The recovery logic handles dismounting and login placement. It does not automatically restore the player's position as a passenger inside a train after login.

### Vehicle and rail rendering

- **Vehicle texture loading:** Decodes textures in the background, keeps OpenGL uploads on the render thread, and processes at most one queued texture upload per frame. Vehicles, bogies, and train-model display blocks share the same readiness checks.
- **Frustum culling:** Adds or adjusts visibility checks for vehicle bodies, bogies, rails, and wires to reduce rendering outside the view. Special handling covers the vehicle currently occupied by the player, invalid bounding boxes, and shader-related rendering stages.
- **Rail resource construction:** Adds task queues, geometry snapshots, background static-mesh construction, and a main-thread upload budget, together with invalidated-task handling, rebuilding, and resource release.
- **Rail LOD:** Allows model packs to provide alternative rail models selected by viewing distance. The main model remains in use when no `lodModels` are supplied.
- **Rail visibility conditions:** Checks that rail endpoints are within render distance and their chunks are loaded before drawing. Long rails may remain invisible until both ends satisfy these conditions.
- **Resource management:** Adds release handling for display lists, VBOs, VAOs, buffers, and shader programs; adjusts model-resource cleanup and depth-of-field camera shader disposal; and transforms normals with the transformation matrix.
- **Shader interfaces:** Exposes the `rtmEmissivePass` marker for RTM light passes and `rtmTemporalEntity` / `rtmViewToPreviousView` temporal-reprojection data for the train occupied by the local player. Shader packs must explicitly use these interfaces; their presence does not establish compatibility with every shader pack.
- **Passenger rendering:** Adjusts brightness for player models inside vehicles, first-person hands, and held items, restoring the relevant state after rendering.

These changes aim to reduce loading and rebuilding stalls and improve vehicle rendering. No performance benchmarks are provided in this repository; actual frame rates and model-pack compatibility require testing.

### Horns and rail removal

- Adds a dedicated playback entry point and network marker for keyboard-triggered and macro-triggered horns, preventing ordinary vehicle sounds from incorrectly using the horn's audible range.
- Changes the default audible horn range from 16 to 256 blocks. Entity sounds with a range override use linear distance attenuation.
- The `hornSoundRange` setting can be adjusted in the `sound_range` category of the AppleLib configuration. Existing configured values remain in use and are not overwritten when the default changes.
- Manually removing large rails requires an RTM iron hacksaw (`iron_hacksaw`) in the main hand. Successful removal consumes tool durability.

### Multiplayer and compatibility

- Sends vehicle movement updates to players tracking the entity and supplies vehicle state and passenger relationships to new observers.
- Handles vehicle movement packets on the client thread and uses a single state snapshot for position, orientation, and roll.
- Addresses reused client entity IDs, stale bogie references, and old entity-removal notifications interfering with tracking of replacement entities.
- Uses reflection to detect ReplayMod, reducing direct compile-time dependencies on its classes. ReplayMod playback compatibility has not been fully tested.
- Adds walking and pose packets and changes the horn packet format. Clients and servers should use the same build of this fork. AppleLib checks whether the remote version string matches the local one.

## Model-pack configuration

### Interior walkable area

Add or adjust `walkable` in the train model configuration. The following JSON fields can be merged into an existing configuration:

```json
{
  "walkable": {
    "enabled": true,
    "center": [0.0, 1.3, 0.0],
    "length": 0.0,
    "width": 3.0,
    "allowCarriageTransfer": true,
    "transferEnds": [true, true],
    "transferWidth": 1.0
  }
}
```

| Field | Default | Meaning |
| --- | --- | --- |
| `enabled` | `true` | Enables the walkable interior area |
| `center` | `[0.0, 1.3, 0.0]` | Area center in vehicle-local coordinates, in blocks |
| `length` | `0.0` | Full length of the area; values at or below 0 are derived from `trainDistance × 2` |
| `width` | `3.0` | Area width, in blocks |
| `allowCarriageTransfer` | `true` | Allows movement between carriages |
| `transferEnds` | `[true, true]` | Passage permission for RTM connection ends 0 and 1, respectively |
| `transferWidth` | `1.0` | Width of the centered gangway opening at each end, in blocks |

Models without a `walkable` entry also use the default area. For models unsuitable for interior walking, explicitly set `"walkable": { "enabled": false }`.

### Distance-based rail models and asynchronous construction

Rail configurations can supply alternative models through the `lodModels` array. Each entry contains `distance`, `modelFile`, and optional `textures`, `rendererPath`, `vertexShaderPath`, and `fragmentShaderPath` fields. `distance` is the distance from the rail core, in blocks, at which the model becomes eligible. When multiple thresholds are reached, the applicable model with the greatest threshold is selected.

Scripted rails must explicitly declare `supportsAsyncStaticMesh = true` to use the asynchronous static-mesh path. Before enabling it, confirm that the script is suitable for static geometry snapshots. Scripts without this declaration retain the original construction path. The declaration has been added to seven built-in scripts covering monorail, cable, girder, elevated, and standard rails.

## Configurable client options

The following options are in `config/fix-rtm.cfg`. Both are enabled by default:

| Category | Setting | Purpose |
| --- | --- | --- |
| `model_loading` | `asyncVehicleTextureLoading` | Background vehicle-texture decoding and uploads spread across frames |
| `better_rtm` | `vehicleFrustumCulling` | Frustum culling for vehicles and bogies |

## fixRTM sources and build maintenance

- Adds missing Java / Kotlin sources, APIs, support files, and language and texture resources under `com.anatawa12.fixRtm` and the corresponding resource directories. These additions include third-party code; original authorship and license notices are preserved.
- Adapts parts of fixRTM for Kotlin 1.4, Minecraft mappings, and Java calls, and integrates this fork's passenger recovery, rendering, and floor tracking.
- Uses Java 8, Gradle 5.6.4, ForgeGradle 3.0.197, and Kotlin 1.4.32. Bundled libraries and their transitive dependencies are collected through explicit packaging configuration.
- Merges service-discovery descriptors to preserve services such as WebP ImageIO, stores license texts and attribution separately for each dependency, and corrects the license labels for Sai and jbsdiff.
- Normal builds write to `build/libs/` and do not automatically copy files into a game or server directory. See [BUILDING.md](BUILDING.md) for manual copy commands.
- The root project does not currently enable `cr/`; its build configuration is retained for reference. The duplicate `CommandRTM` in `src/main/kotlin` is also outside the current Kotlin compilation source directories.

The original workspace's `rtm-patches` / `ngtlib-patches` directories have been removed from this repository's working tree and backed up outside the repository locally. The current project directly compiles the corresponding Java sources and does not use those patch files as build inputs.

## Usage and validation status

Use Minecraft 1.12.2 with the corresponding Forge environment and place the mod JAR you build into the instance's `mods` directory. This project includes RTM, NGTLib, MCTE, and fixRTM-related code. Avoid installing other JARs containing the same mods or classes alongside it.

The feature descriptions are based on the current sources. Main-project compilation, JAR reobfuscation, and packaged-content checks have previously been performed. Full in-game operation, multiplayer carriage transfers, logout and login recovery, ReplayMod, and different model-pack and shader combinations still require testing. Test tasks are currently disabled in the build, so a successful build does not establish that gameplay features have been validated.

## Authors and licensing

Thanks to NGT5479, anatawa12 and the relevant contributors, and AppleExtended author Applepie and upstream contributors. HabQi's subsequent modifications are marked with the year 2026. Attribution for third-party sources and resources is retained.

See [LICENSE.md](LICENSE.md), [licenses/README.md](licenses/README.md), and [NOTICE.txt](src/main/resources/NOTICE.txt) for licensing and third-party notices. Code from different sources is subject to its respective applicable terms. The upstream distribution-permission acknowledgment preserved below describes permission obtained by the upstream author; it should not be taken as evidence that this fork has obtained additional permission of the same scope.

## Original upstream README (Japanese)

The original AppleExtended README is preserved below. Its download links, feature documentation, and distribution-permission acknowledgment refer to the upstream project.

<details>
<summary>Show the original upstream Japanese README</summary>

# AppleExtended

[English](README.en.md) | [日本語](README.md)

[Minecraft](https://www.minecraft.net/ja-jp) | [forge1.12.2](https://files.minecraftforge.net/net/minecraftforge/forge/index_1.12.2.html)

# 最新版のダウンロードは[こちら](https://github.com/ringo-1234/AppleExtended/releases/latest) から

## 追加機能は[こちら](https://github.com/ringo-1234/AppleExtended/wiki/Added-Features.ja)

## この Mod はなにか

AppleExtended は RealTrainMod(RTM),NGTLib,MCTE の非公式改造物です。

## 注意事項

**当 Mod の使用による一切の責任を負いません。**

## 導入方法

当 Mod を DL し、mods フォルダに入れてください

## 詳しくは

[こちら](https://ringo-1234.github.io/Applepie-site/)<br>
[こちら](https://github.com/ringo-1234/AppleExtended/wiki)

## 謝辞

公開にあたり、配布許可を頂いた[NGT-5479](https://twitter.com/ngt5479) 様に、この場をお借りして感謝申し上げます。<br>
また、当改造物の開発にあたり、多大なる助言と支援を 頂いた[anatawa12](https://x.com/kakerigawa) 様に、この場をお借りして感謝申し上げます。

</details>
