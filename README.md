# AppleExtended-Modified

**简体中文** | [English](README.en.md) | [日本語](README.ja.md)

HabQi 在 2026 年维护的 AppleExtended 个人修改版，面向 Minecraft 1.12.2 / Forge。基于 AppleExtended 的 `d40d973` 版本进行修改，当前构建版本为 `1.0.0`。

- [本修改版仓库](https://github.com/HabQi/AppleExtended-Modified)
- [AppleExtended 上游项目](https://github.com/ringo-1234/AppleExtended)
- [源码构建说明](BUILDING.md)
- 上游日文说明与致谢保留在本文末尾。

AppleExtended 是 RealTrainMod（RTM）、NGTLib 和 MCTE 的非官方修改项目。本仓库在其基础上补充构建所需的 fixRTM 源码，并调整乘客交互、车辆同步和渲染流程。下列内容描述本修改版相对于上述基准的改动。

## 主要改动

### 车内自由行走与跨车厢移动

- 新增列车可行走地板，乘客在车辆局部坐标内移动，并随列车的位置、转向、俯仰和侧倾更新位置。
- 主手持车票、未乘坐其他实体且未潜行时，右键列车进入可行走区域；使用通常的移动按键在车内行走。
- 支持在已连接的相邻车厢之间移动。两节车厢需要开启相应端的通行许可，且连接位置满足距离和通道宽度条件。
- 加入客户端移动预测、服务端输入处理、位置校正，以及新加入观察者的乘客位置同步。
- 调整乘客位置与第一人称相机的插值、侧倾跟随和行走动画，处理原版移动逻辑与车内移动重复执行的问题。
- 移除旧的 `TrainPlatformHandler` 和 `PlayerCameraTrain`，改用新的乘客与车内移动处理流程。

可行走区域为模型配置中的矩形范围，不会根据车厢模型自动识别座椅、墙壁或多层地板。不同车型应调整区域的中心、高度和长宽。

### 乘客坐姿与躺姿

新增站立、坐姿和躺姿状态，支持在可行走地板上切换，并将状态同步给其他玩家。姿态会在下车、退出、死亡或切换维度等情况下清理。

| 操作 | 默认按键 | 适用条件 |
| --- | --- | --- |
| 切换为坐姿 | `G` | 位于可行走地板上 |
| 切换为躺姿 | `H` | 位于可行走地板上 |
| 恢复站立 | 跳跃键，默认 `Space` | 位于可行走地板上 |
| 升高躺姿 | `=` | 当前为躺姿，可长按 |
| 降低躺姿 | `-` | 当前为躺姿，可长按 |

按键可在游戏控制设置中修改；若与其他模组冲突，请重新绑定。补充了简体中文、繁体中文、日文、英文、西班牙文和俄文的姿态按键文本。

### 上下车、退出与重新登录处理

- 记录乘客的下车候选位置，调整下车时的碰撞检查与位置放置。
- 保存玩家数据时，处理 RTM 乘坐关系与恢复位置，避免直接依赖玩家存档中的列车实体副本。
- 退出时解除列车乘坐关系；重新登录时使用有效的恢复位置，并修复可行走地板引用。
- 加入地板专用实体追踪器，管理乘客列表、初始位置快照和后续同步。

恢复逻辑用于下车和重新登录处理，不提供重新登录后自动恢复车内乘坐状态的功能。

### 车辆与轨道渲染调整

- **车辆纹理加载**：在后台解码纹理，将 OpenGL 上传保留在渲染线程，每帧最多处理一张待上传纹理；车辆、转向架及列车模型展示方块接入同一就绪检查。
- **视锥剔除**：为车体、转向架、轨道和电线加入或调整可见范围判断，减少视野外对象的绘制。当前乘坐车辆、异常包围盒和光影相关渲染阶段有专门处理。
- **轨道资源构建**：引入任务队列、几何快照、后台静态网格构建和主线程上传预算，并处理失效任务、重绘与资源释放。
- **轨道 LOD**：支持模型包按观察距离提供替代轨道模型；模型包未提供 `lodModels` 时继续使用主模型。
- **轨道显示条件**：绘制前检查轨道端点是否处于渲染距离内且区块已经加载；长轨道可能需要两端都满足条件后才显示。
- **资源管理**：补充显示列表、VBO、VAO、缓冲区及 shader program 的释放，调整模型资源清理和景深相机 shader 回收；按变换矩阵处理法线。
- **光影接口**：为 RTM 发光通道提供 `rtmEmissivePass` 标记，为本地玩家乘坐的列车提供 `rtmTemporalEntity` / `rtmViewToPreviousView` 时间重投影数据。需要光影包主动使用这些接口，不能据此认为任意光影包都已兼容。
- **乘客显示**：调整车内玩家模型、第一人称手部和手持物品的亮度，并在渲染结束后恢复相关状态。

这些改动旨在减少加载和重建造成的卡顿、改善车辆显示。仓库尚未提供性能基准，具体帧率和模型包兼容性需要实际测试。

### 鸣笛与轨道操作

- 为按键鸣笛和宏鸣笛增加独立播放入口与网络标记，避免普通车辆声音误用鸣笛音域。
- 鸣笛默认可听范围由 16 调整为 256 方块；使用音域覆盖的实体声音采用线性距离衰减。
- `hornSoundRange` 可在 AppleLib 配置的 `sound_range` 类别下调整。已有配置中的数值会继续使用，不会因默认值改变而自动覆盖。
- 玩家手动拆除大型轨道时，需要主手持 RTM 铁钢锯（`iron_hacksaw`）；成功拆除时消耗工具耐久。

### 联机与兼容性处理

- 车辆移动同步面向正在追踪该实体的玩家发送，补充新观察者的车辆状态与乘客关系同步。
- 车辆移动包切换到客户端线程处理，并让位置、方向和侧倾使用同一份状态快照。
- 处理客户端实体 ID 被新实例复用、转向架引用失效，以及旧实体移除通知影响新实体追踪的问题。
- ReplayMod 检测改为反射调用，减少对其类的直接编译依赖；这不代表已经完成 ReplayMod 回放兼容性测试。
- 新增行走及姿态数据包，鸣笛包格式也有变化；客户端与服务端应使用同一修改版构建。AppleLib 会检查远端的版本号是否与本地一致。

## 模型包配置

### 车内活动范围

在列车模型配置中添加或调整 `walkable`。以下为可并入原有配置的 JSON 字段示例：

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

| 字段 | 默认值 | 含义 |
| --- | --- | --- |
| `enabled` | `true` | 是否启用车内可行走区域 |
| `center` | `[0.0, 1.3, 0.0]` | 车辆局部坐标中的区域中心，单位为方块 |
| `length` | `0.0` | 区域全长；不大于 0 时按 `trainDistance × 2` 推导 |
| `width` | `3.0` | 区域宽度，单位为方块 |
| `allowCarriageTransfer` | `true` | 是否允许跨车厢移动 |
| `transferEnds` | `[true, true]` | RTM 连接端 0、1 各自是否允许通行 |
| `transferWidth` | `1.0` | 车厢端部居中通道的宽度，单位为方块 |

未填写 `walkable` 的车型也会使用默认区域。对于不适合开放车内行走的车型，可显式设置 `"walkable": { "enabled": false }`。

### 轨道距离模型与异步构建

轨道配置可通过 `lodModels` 数组提供替代模型，每项包含 `distance`、`modelFile`，以及可选的 `textures`、`rendererPath`、`vertexShaderPath` 和 `fragmentShaderPath`。`distance` 为距轨道核心的起用距离，单位为方块；达到多个阈值时选择适用的较远距离模型。

脚本轨道需要显式声明 `supportsAsyncStaticMesh = true` 才会进入异步静态网格路径。启用前应确认脚本适合静态几何快照；未声明的脚本保留原有构建路径。本仓库已为单轨、缆索、桥梁、高架及标准轨道等七个内置脚本加入该声明。

## 可调整的客户端选项

以下选项位于 `config/fix-rtm.cfg`，均默认开启：

| 类别 | 配置项 | 作用 |
| --- | --- | --- |
| `model_loading` | `asyncVehicleTextureLoading` | 车辆纹理后台解码与逐帧上传 |
| `better_rtm` | `vehicleFrustumCulling` | 车辆与转向架视锥剔除 |

## fixRTM 源码与构建整理

- 补充 `com.anatawa12.fixRtm` 下缺失的 Java / Kotlin 源码、API、支持文件，以及语言和纹理资源。这部分包含第三方代码，保留其作者与许可说明。
- 对部分 fixRTM 代码作 Kotlin 1.4、Minecraft 映射和 Java 调用适配，并接入本修改版的乘客恢复、渲染和地板追踪流程。
- 构建使用 Java 8、Gradle 5.6.4、ForgeGradle 3.0.197 和 Kotlin 1.4.32；内置库及其传递依赖通过明确的打包配置收集。
- 合并服务发现描述，保留 WebP ImageIO 等服务；按依赖单独保存许可证与归属声明，并修正 Sai、jbsdiff 的许可标注。
- 普通构建输出到 `build/libs/`，不会自动复制到游戏或服务器目录；手动复制方式见 [BUILDING.md](BUILDING.md)。
- 当前根工程未启用 `cr/`，其构建配置仅作保留；`src/main/kotlin` 中的同名 `CommandRTM` 也未加入当前 Kotlin 编译源目录。

原工作区中的 `rtm-patches` / `ngtlib-patches` 补丁目录已从本仓库工作区移除并在本地外部备份。当前工程直接编译相应 Java 源码，不依赖这些补丁文件参与构建。

## 使用与验证状态

使用 Minecraft 1.12.2 和对应 Forge 环境，将自行构建的模组 JAR 放入实例的 `mods` 目录。本项目包含 RTM、NGTLib、MCTE 和 fixRTM 相关代码，避免同时安装包含相同模组或类的其他 JAR。

功能说明依据当前源码整理。主工程编译、JAR 重混淆和打包内容曾完成检查；完整游戏运行、多玩家跨车厢移动、退出重登、ReplayMod、不同模型包及光影组合仍需测试。构建中的 Test 任务当前禁用，构建成功不等于完成游戏功能验证。

## 作者与许可

感谢 NGT5479、anatawa12 及相关贡献者，以及 AppleExtended 作者 Applepie 和上游贡献者。本修改版由 HabQi 标注 2026 年的后续修改，第三方源码与资源的归属保留。

许可与第三方声明见 [LICENSE.md](LICENSE.md)、[licenses/README.md](licenses/README.md) 和 [NOTICE.txt](src/main/resources/NOTICE.txt)。不同来源的代码应按各自适用条款处理。本文末尾保留的上游配布许可致谢描述的是上游作者获得的许可，不应据此推定本 Fork 已取得相同范围的额外授权。

## 上游原始说明（日文）

以下保留 AppleExtended 原始 README；其中的下载链接、功能文档和配布许可致谢均属于上游项目。

<details>
<summary>展开上游日文 README</summary>

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
