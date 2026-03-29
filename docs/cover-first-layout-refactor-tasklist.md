# Track Text Layout Migration and Cover-First Layout Refactor Task List

## 1. 文档定位

这份文档是当前 `CoverArtForAndroid` 元数据文字系统的单一执行计划。

它覆盖两层工作：

1. 已经落地的 `TextView -> Scene Renderer` 迁移结果的后续整理。
2. 本轮新的 `Cover-First Adaptive Layout` 布局基础重构。

结论先写清楚：

- 不要把这份文档和旧的迁移里程碑并行执行。
- 旧迁移计划中凡是涉及 `bounds`、`text area`、`ScreenAdapter`、字号策略、portrait/landscape 布局假设的部分，统一以本文件为准。
- 当前代码里 `TrackTextLayoutPolicy`、`AndroidTrackTextLayoutEngine`、`TrackTextSceneView` 已经存在，因此本轮工作的重点不是“再造一套 scene renderer”，而是修正 scene renderer 所依赖的布局地基。

## 2. 产品目标

本轮改造只服务四个目标：

1. 封面最大化。
2. 文字区高度和宽度按内容自适应，形成呼吸感。
3. 兼容主流屏幕比例和分辨率。
4. 重点优化 `16:9` 竖屏画屏在远距离观看时的可读性。

如果某项实现和以上四点冲突，以这四点为准，不以“沿用旧布局比例”或“保持旧视觉细节”优先。

## 2.1 当前状态（2026-03-29）

当前分支已经完成了这份计划里大部分 `P0` 地基工作：

- `MainActivity.ScreenAdapter` 已引入 `LayoutSpec`，并统一封面尺寸、边距、gap 与文字预算。
- portrait / landscape 两条布局路径都已改为让 `TrackTextSceneView` 作为 metadata 容器唯一 child。
- `resolveTrackTextAvailableBounds()` 已按“预算上限 + 当前容器可用高度”模式重写，不再把上一帧容器高度锁回下一次测量。
- `TrackTextScene.contentWidthPx` 已进入布局消费，竖屏短标题可收缩，转场期间也有宽度冻结策略。
- `TrackTextLayoutPolicy` 已切到 `shortEdgePx` + density 感知的字号策略，并补了低密度大屏与中英文混排测试。

当前仍属于后续 backlog 的内容：

- `measurement-first` / `TextMeasurer` 抽象还没落地。
- `breakStrategy` / `hyphenationFrequency` 与二段式 shrink-wrap 还没落地。
- `16:10`、`4:3` 和低对比封面还需要物理设备 QA。
- 旧固定比例常量的彻底清理与 resolver 提取仍在 `P2`。

## 3. 当前代码现状

当前代码已经具备 scene-based 文本渲染基础，并且第一轮 `Cover-First Adaptive Layout` 地基已经落地。

相关文件：

- `app/src/main/java/com/example/roonplayer/MainActivity.kt`
- `app/src/main/java/com/example/roonplayer/ui/text/TrackTextLayoutModels.kt`
- `app/src/main/java/com/example/roonplayer/ui/text/TrackTextLayoutPolicy.kt`
- `app/src/main/java/com/example/roonplayer/ui/text/AndroidTrackTextLayoutEngine.kt`
- `app/src/main/java/com/example/roonplayer/ui/text/TrackTextScene.kt`
- `app/src/main/java/com/example/roonplayer/ui/text/TrackTextSceneView.kt`
- `app/src/test/java/com/example/roonplayer/ui/text/TrackTextLayoutPolicyTest.kt`

当前已经落地的结构变化：

1. `MainActivity.ScreenAdapter` 已通过 `LayoutSpec` 统一输出 cover、margin、gap 和 text budget。
2. `applyPortraitLayout()` 和 `applyLandscapeLayout()` 都已按新的 metadata 容器结构重写。
3. `resolveTrackTextAvailableBounds()` 已不再把上一首歌的容器高度锁进下一次测量。
4. `TrackTextLayoutPolicy.responsiveFontSizeSp()` 已改为 `shortEdgePx` + density 感知的可读性策略。
5. 竖屏 `separator` 已移除，层次感由 gap、封面阴影和底部渐变承担。
6. `statusText` overlay 与 metadata palette 的所有权已经显式分离，metadata 容器转场期间也有宽度冻结。

当前剩余的结构性问题：

1. `TrackTextLayoutPolicy` 的 shrink 决策仍基于平均 glyph 宽度估算，而不是 measurement-first 实测。
2. `AndroidTrackTextLayoutEngine` 还没有接入 `breakStrategy` / `hyphenationFrequency` 或二段式 shrink-wrap。
3. `LayoutSpec` resolver 仍内嵌在 `MainActivity.ScreenAdapter`，旧固定比例常量还需要在 `P2` 继续清理。
4. `16:10` / `4:3` / 低对比封面场景仍缺少一轮物理设备 QA。

## 4. 核心问题定义

首轮实现已经解决了“同一个文字区预算被重复硬编码在多个地方”的主要问题：
`LayoutSpec` 现在是布局预算的主来源，`TrackTextScreenMetrics` 负责纯排版视角的屏幕输入。

当前剩余根因转成两点：

1. shrink 决策仍然主要依赖估算，而不是实测结果。
2. shrink-wrap 仍是 P0 级单次宽度收缩，尚未进入 balanced line / 二段式重测。

结果是：

- 超长标题的收缩已经可预测，但还没到最佳均衡换行。
- 某些多行标题在容器 shrink 后右侧仍会保留空白。
- 不同比例设备上的最终表现仍需要更多物理设备 QA。

后续工作必须继续保持预算单一来源，不允许重新回到多个模块各自推导文本空间的状态。

## 5. 设计原则

### 5.1 Cover-First

先求封面最大可用空间，再给文字分配可读但不浪费的预算。

### 5.2 Short-Edge Driven

边距、间距、字号基准都基于短边计算，而不是直接写死某个分辨率下的像素值。

### 5.3 Dynamic Height Budget

文字容器可以 `WRAP_CONTENT`，但文字系统仍然必须知道“最多能占多高”。
这个预算来自布局规格，不来自上一帧容器高度。

### 5.4 Basic Shrink-Wrap in P0

呼吸感不只来自高度自适应，也来自宽度收缩。
因此基础 `contentWidthPx` 必须进入 P0，而不是拖到后续视觉优化。

### 5.5 Readability First for 16:9 Portrait

远距离可读性不是 P1 打磨项，而是 P0 的前置输入。
先定标题/副标题最小字号和行距，再反推封面与文字预算。

### 5.6 Measurement-First Later

P0 先完成布局地基收敛。
P1 再推进 `measurement-first`，逐步削弱基于平均字符宽度的估算逻辑。

### 5.7 Container Structure Contract First

在允许 portrait / landscape / scrim 相关任务并行之前，必须先冻结容器结构契约。
至少要明确：

- portrait metadata 容器的 id / tag / parent
- landscape metadata 容器的 id / tag / parent
- scrim 绑定到哪个真实容器
- `resolveTrackTextAvailableBounds()` 的宽度从哪个 view 读取
- 高度预算从哪个 `LayoutSpec` 字段读取
- `TrackTextSceneView` 是否始终是 metadata 容器的唯一 child

### 5.8 Transition Geometry Stability

引入 `contentWidthPx` 后，metadata container 的宽度可能在 source scene 和 target scene 之间变化。
转场期间必须优先保证几何稳定性，而不是实时追求最终宽度。
推荐规则：

- 转场中冻结 metadata container 宽度
- 宽度取 `max(sourceContentWidthPx, targetContentWidthPx, minWidthBudget)`
- 转场结束后再提交最终 shrink-wrap 宽度

## 6. 与旧迁移计划的关系

这份文档不是旧迁移计划的附录，而是它在布局层面的替代版本。

执行规则：

1. 不再按旧文档中“Milestone 1 policy -> Milestone 2 engine”独立推进布局相关任务。
2. 先完成本文件的 P0，再做任何进一步的排版精度优化。
3. 旧计划中已完成的 scene renderer、palette、transition 基础设施继续保留，不重复建设。
4. 如果旧文档中的任务描述和本文件冲突，以本文件为准。

## 7. 成功标准

### 7.1 视觉成功标准

1. 竖屏封面显著大于旧版，优先接近屏幕宽度上限。
2. 竖屏移除分隔线后，封面与文字之间仍然有清晰但不生硬的分离感。
3. 短标题时文字容器明显收缩，不再占满整块宽度。
4. 长标题时字体和行数变化可预期，不出现明显突变。
5. `16:9` 竖屏画屏在远距离看标题和艺术家仍可辨认。

### 7.2 架构成功标准

1. 布局预算由单一 `LayoutSpec` 或等价结构提供。
2. `TrackTextLayoutPolicy` 不再绑定固定比例的文字区高度。
3. `resolveTrackTextAvailableBounds()` 不再把上一首歌的容器高度锁进下一次测量。
4. scrim 和容器结构一致，不留悬空旧链路。
5. metadata 容器结构契约在布局重写前已明确。
6. 转场期间 metadata container 宽度保持稳定，不因 source/target scene 宽度不同而抖动。

## 8. 范围与非目标

本轮范围内：

- `ScreenAdapter` / `LayoutSpec` 设计与落地
- portrait / landscape 布局重写
- 远距离字号和行距基线
- `TrackTextBounds` 的正确传递
- `contentWidthPx` 基础 shrink-wrap
- scrim 清理或重接
- 必要的单元测试和手工 QA

本轮非目标：

- 歌词系统
- 任意字体加载系统
- 大规模 transition 视觉重设计
- 自定义复杂断词算法
- 新建第二套 metadata renderer

## 9. 执行总顺序

严格执行以下顺序，不要任意打乱：

```text
P0-A 远距离可读性基线
  -> P0-B LayoutSpec 合同
    -> P0-C ScreenAdapter / Resolver 落地
      -> P0-D Portrait Layout
      -> P0-E Landscape Layout
        -> P0-F Scrim 与 overlay 层级同步清理
      -> P0-G Bounds 修正
        -> P0-I contentWidthPx 基础 shrink-wrap
          -> P0-H Typography Policy 修正
P1-J Measurement-first 设计
P1-K 行平衡与二段式 shrink-wrap
P1-L 动画 token 复核
P2-M 清理旧 token 与提取类
P2-N 收尾验证
```

其中：

- `P0-D` 和 `P0-E` 可以在 `P0-C` 之后并行起草。
- `P0-F` 不应早于 portrait 容器结构定稿；它依赖真实容器 id / tag / parent 契约。
- `P0-G` 必须在 `P0-D` / `P0-E` 之后，因为它依赖新的容器结构。
- `P0-I` 必须在 `P0-G` 之后，因为它需要真实的 bounds 链路先跑通。
- `P0-H` 放在 `P0-I` 之后，优先先看清 shrink-wrap 链路是否成立，再调字号系数。
- `P1-K` 不得早于 `P1-J`。

## 10. Phase P0：布局地基重构

### P0-A 远距离可读性基线

目标：先定义阅读基线，再做空间分配。

步骤：

1. 冻结核心目标设备：
   - `1080 x 1920` 竖屏画屏
   - `1920 x 1080` 横屏电视
   - `16:10` 平板
   - `4:3` 平板
2. 冻结样本文本：
   - 短标题
   - 超长英文标题
   - 中英文混排
   - 空 album
   - 长 artist / 长 album
3. 定义 P0 的最小可读基线：
   - 标题最小字号
   - 艺术家最小字号
   - 专辑最小字号
   - 最小行距
   - 最大行数
4. 定义文字最小可读高度预算 `minReadableTextHeightPx`。
5. 记录“当高度预算不够时由封面让步”的规则。
6. 增加一个专门的 QA 组合：深色封面 + 短歌名，用于验证移除 separator 后的层次感。

交付物：

- 一组固定 QA 样本
- 一组明确的最小字号与行距基线
- 一条 cover 与 text 的让步规则
- 一份 metadata 容器结构契约草案

验收标准：

- 团队对“能看清”和“不能看清”有统一定义。
- 后续 `LayoutSpec` 计算可以直接消费这些基线。

### P0-B 定义 LayoutSpec 合同

目标：收敛布局预算的唯一来源。

建议数据模型：

```kotlin
data class LayoutSpec(
    val screenWidthPx: Int,
    val screenHeightPx: Int,
    val density: Float,
    val shortEdgePx: Int,
    val isLandscape: Boolean,
    val outerMarginPx: Int,
    val topMarginPx: Int,
    val bottomMarginPx: Int,
    val gapPx: Int,
    val coverSizePx: Int,
    val maxTextWidthPx: Int,
    val maxTextHeightPx: Int,
    val minReadableTextHeightPx: Int,
    val minLandscapeTextWidthPx: Int
)
```

定义要求：

1. 输入至少包含宽、高、density、方向。
2. 预留将来接入 insets 的空间，不要把 `displayMetrics` 写死为永远可信的唯一输入。
3. `maxTextHeightPx` 是预算上限，不是容器历史高度。
4. `minReadableTextHeightPx` 是保底值，当预算不足时必须回压封面。
5. `density` 进入 `LayoutSpec`，避免“布局预算靠 `LayoutSpec`，字号预算再从别处补读”的双来源状态。
6. `portraitCoverWidthRatio` 不是 `LayoutSpec` 输出字段，而是 resolver 的配置输入；输出里只保留真正被消费的 `coverSizePx`。
7. 在 `LayoutSpec` 之外保留 `TrackTextScreenMetrics` 作为纯排版视角的屏幕物理契约；二者职责必须明确分离，不能重复推导同一预算。
8. 在本阶段同时产出 metadata 容器结构契约，供 `P0-D` / `P0-E` / `P0-F` 共用。

交付物：

- `LayoutSpec` 数据结构
- 对每个字段的文字说明
- 预算优先级说明
- metadata 容器结构契约：
  - portrait / landscape 容器 id / tag / parent
  - scrim 绑定对象
  - bounds 读取 view
  - `TrackTextSceneView` child 约束
  - `statusText` overlay 层级要求

验收标准：

- `MainActivity`、`ScreenAdapter`、`TrackTextLayoutPolicy` 不再各自定义自己的 text area 概念。

### P0-C 重写 ScreenAdapter 或等价 Resolver

目标：用短边系统和 `LayoutSpec` 替代固定比例尺寸。

步骤：

1. 增加 `shortEdge`、`longEdge`、`spacingXs/Sm/Md/Lg`。
2. 把 portrait 和 landscape 的封面计算统一收口到一个 resolver 中。
3. portrait 下优先按宽度推导封面，但保留 `minReadableTextHeightPx`。
4. landscape 下优先按高度推导封面，但保留 `minLandscapeTextWidthPx`。
5. 删除旧的 `getTextAreaSize()` 在布局意义上的权威性。
6. 把 `portraitCoverWidthRatio` 保持为 resolver 内部可调配置，而不是外部消费的布局输出。
7. 如无必要，P0 先保持 `ScreenAdapter` 仍在 `MainActivity` 内部，避免边改布局边搬类。
8. 如后续复用明显，再在 P2 提取为独立 resolver。

文件：

- `app/src/main/java/com/example/roonplayer/MainActivity.kt`

验收标准：

- 布局层只从 `LayoutSpec` 读取 cover、margin、gap、text budget。

### P0-D 重写竖屏布局

目标：实现真正的 `Cover-First` 竖屏布局。

步骤：

1. 移除 `separator`。
2. 封面容器顶部对齐，水平居中。
3. 封面尺寸来自 `LayoutSpec.coverSizePx`。
4. 文字容器改为 `WRAP_CONTENT` 高度。
5. 文字容器宽度先使用 `LayoutSpec.maxTextWidthPx`，后续由 `contentWidthPx` 收缩。
6. 用 `gapPx` 替代分隔线完成封面和文字区的视觉分离。
7. 不增加新的装饰物；如视觉分离不足，优先调 `gap`、line background、封面阴影，而不是再加线。
8. 确保 `TrackTextSceneView` 仍然是容器唯一 child。
9. 验证 `statusText` overlay 在新布局下的位置、可见性和层级不受影响。

文件：

- `app/src/main/java/com/example/roonplayer/MainActivity.kt`

验收标准：

- 竖屏不再出现分隔线。
- 竖屏短标题时下方自然留白明显。
- 竖屏长标题时不出现容器固定高度导致的压缩感。
- `statusText` overlay 不被封面、文字容器或新层级关系遮挡。

### P0-E 重写横屏布局

目标：让横屏封面优先按高度吃空间，但不给文字区造成灾难性压缩。

步骤：

1. 封面尺寸来自 `LayoutSpec.coverSizePx`。
2. 横屏文字区宽度来自“总宽度减去封面、边距和 gap 后的剩余空间”。
3. 增加 `minLandscapeTextWidthPx` 下限。
4. 当剩余宽度低于下限时，由封面退让，而不是继续压文字。
5. 文字容器保持 `WRAP_CONTENT` 高度并垂直居中。

文件：

- `app/src/main/java/com/example/roonplayer/MainActivity.kt`

验收标准：

- `16:9` 横屏下封面更大。
- `4:3` 横屏或近方屏设备上，文字区仍保持基本可读。

### P0-F scrim 与旧链路清理

目标：防止布局重写后旧 scrim 逻辑静默失效。

步骤：

1. 审查 `text_container` tag 与真实容器结构是否一致。
2. 决定 scrim 的命运：
   - 如果保留，就绑定到新的真实容器。
   - 如果取消，就删除旧的透明度更新链路。
3. 不允许留一个运行时不崩溃、但永远找不到容器的分支。
4. 把 `statusText` overlay 层级检查提升为 P0 gate，而不是仅仅保留到 QA 阶段。

文件：

- `app/src/main/java/com/example/roonplayer/MainActivity.kt`

验收标准：

- scrim 要么明确有效，要么明确删除。
- `statusText` overlay 在新容器结构下层级稳定，且 `mainLayout` rebuild 后不会偶发被遮挡。

### P0-G 修正 TrackTextBounds 传递

目标：让文字布局预算来自当前布局规范，而不是上一帧残留。

步骤：

1. `resolveTrackTextAvailableBounds()` 的宽度优先取容器实测值。
2. 高度改为：
   - 首帧：使用 `LayoutSpec.maxTextHeightPx`
   - 后续：使用 `min(LayoutSpec.maxTextHeightPx, 容器当前可用高度)`
3. 不再直接把 `container.height` 当成下一首歌的预算上限。
4. 区分“容器实际高度”和“文字允许申请的最大高度”。

文件：

- `app/src/main/java/com/example/roonplayer/MainActivity.kt`

验收标准：

- 标题从短切换到长时，文字预算不会被上一首歌的矮容器锁死。

### P0-H 修正 Typography Policy

目标：移除对固定比例文字区的依赖，把低密度大屏可读性提升到 P0 水平。

步骤：

1. 给 `TrackTextScreenMetrics` 增加 `shortEdgePx`。
2. 重写 `responsiveFontSizeSp()`：
   - 使用 `shortEdgePx` 作为主尺度
   - 放大低密度大屏
   - 弱化旧的固定 `35%/65%` 比例约束
3. 保留动态高度预算 shrink，但输入改为 `LayoutSpec.maxTextHeightPx`。
4. 提升最小字号。
5. 提升基础行距。
6. 重新核对 title / artist / album 的让步顺序。

文件：

- `app/src/main/java/com/example/roonplayer/ui/text/TrackTextLayoutModels.kt`
- `app/src/main/java/com/example/roonplayer/ui/text/TrackTextLayoutPolicy.kt`

验收标准：

- `1080p` 低密度屏下标题不再偏小。
- 字号和行距能支撑远距离观看。

### P0-I 增加基础 shrink-wrap

目标：让呼吸感在 P0 就出现，而不是只得到一个“高度会变但宽度仍然很满”的容器。

步骤：

1. 在 `TrackTextScene` 增加 `contentWidthPx`。
2. 在 `AndroidTrackTextLayoutEngine` 计算 scene 时同步产出内容最大宽度。
3. 在竖屏布局中把文字容器宽度设置为：
   - `min(contentWidthPx + horizontalPadding, LayoutSpec.maxTextWidthPx)`
4. 先做基础 shrink-wrap，不在 P0 引入二段式重测。
5. 如果首次内容宽度已经接近最大宽度，不需要额外处理。
6. 显式接受 P0 的已知限制：容器收缩后文字不重新排版，因此某些多行标题在较短行右侧仍会保留空白；这不是 defect，属于 P1-K 的优化范围。
7. 为避免转场期间容器宽度抖动，在 active transition 中冻结 metadata container 宽度，宽度取 source/target/content budget 的最大值，转场结束后再提交最终 shrink-wrap 宽度。

文件：

- `app/src/main/java/com/example/roonplayer/ui/text/TrackTextScene.kt`
- `app/src/main/java/com/example/roonplayer/ui/text/AndroidTrackTextLayoutEngine.kt`
- `app/src/main/java/com/example/roonplayer/MainActivity.kt`

验收标准：

- 短标题时文字容器宽度明显收缩。
- 长标题时容器仍能撑满可用宽度。
- QA 不把“单次 shrink-wrap 后的短行右侧空白”判定为 P0 缺陷。
- track 切换或 rollback 过程中，metadata container 不因 source/target scene 宽度差异而横向抖动。

## 11. Phase P1：精度与质量提升

### P1-J Measurement-First 设计与落地

目标：逐步减少基于平均字符宽度的猜测。

架构约束：

- 不要把 Android 依赖硬塞进 `TrackTextLayoutPolicy`。
- `TrackTextLayoutPolicy` 仍应尽量保持 JVM 可测。

建议路径：

1. 设计 `TextMeasurer` 接口。
2. 接口优先采用批量测量模式，不采用字符级或单行级 API。
3. 推荐形态：

```kotlin
interface TextMeasurer {
    fun measureBlocks(
        blocks: List<TextBlockMeasureRequest>,
        maxWidthPx: Int
    ): List<TextBlockMeasureResult>
}
```

4. 生产环境提供基于 `StaticLayout` 的实现。
5. 测试环境提供可控假实现。
6. 让 policy 或上层 resolver 通过接口完成 shrink 判断。
7. 保持 Android 具体测量逻辑留在 `ui/text` 包内部。

文件：

- `app/src/main/java/com/example/roonplayer/ui/text/TrackTextLayoutPolicy.kt`
- `app/src/main/java/com/example/roonplayer/ui/text/AndroidTrackTextLayoutEngine.kt`
- 如需要新增 `TextMeasurer` 接口文件

验收标准：

- 长标题 shrink 过程更多基于真实测量结果。
- JVM 单测能力不被整体破坏。

### P1-K Balanced Lines 与二段式 shrink-wrap

目标：让多行标题更均衡，且在可接受性能范围内进一步优化容器宽度。

步骤：

1. 先尝试 `StaticLayout.Builder` 的 `breakStrategy` 与 `hyphenationFrequency`。
2. 如原生 break strategy 不足，再评估二段式 shrink-wrap。
3. 二段式 shrink-wrap 必须加短路：
   - 如果首次测量 `contentWidthPx` 已接近最大宽度，则跳过二次测量。
4. 必要时把二次重测延后一帧，避免在关键动画帧堆叠过多 `StaticLayout` 构建。

文件：

- `app/src/main/java/com/example/roonplayer/ui/text/AndroidTrackTextLayoutEngine.kt`
- `app/src/main/java/com/example/roonplayer/MainActivity.kt`

验收标准：

- 2 到 3 行标题的视觉均衡度提升。
- 不引入明显掉帧。

### P1-L 文本转场 token 复核

目标：保证大字号后动画位移和节奏仍然成立。

步骤：

1. 检查 `TrackTransitionDesignTokens.TextTransition` 的位移和时长。
2. 验证大字号标题在 skip / rollback / rapid skip 场景下的可读性。
3. 如位移过短或过长，统一在 token 层修正，不在局部硬编码补丁。

文件：

- `app/src/main/java/com/example/roonplayer/state/transition/TrackTransitionDesignTokens.kt`
- `app/src/main/java/com/example/roonplayer/MainActivity.kt`

验收标准：

- 大字号下文本过渡不显得发虚、挤、或位移不够。

## 12. Phase P2：清理与提取

### P2-M 删除旧布局比例常量

目标：清理固定比例布局遗留物。

步骤：

1. 删除 portrait / landscape 的旧布局比例常量。
2. 明确只删除布局相关比例，不删除非布局 token。
3. 保留动画、字距、行数等仍有效的设计常量。

文件：

- `app/src/main/java/com/example/roonplayer/MainActivity.kt`

验收标准：

- 代码中不再存在多个布局比例真相源。

### P2-N 视情况提取 LayoutSpecResolver

目标：在布局稳定后再做类提取，而不是在 P0 同时搬家和改行为。

步骤：

1. 评估 `ScreenAdapter` 是否仍然只服务 `MainActivity`。
2. 如果复用价值明显，再提取为独立 resolver。
3. 保持提取前后行为一致，不在同一提交里混入新的布局规则。

文件：

- 可能新增 `ui/layout/LayoutSpecResolver.kt`
- 可能修改 `MainActivity.kt`

验收标准：

- 提取是纯结构调整，不改变已验证的布局行为。

## 13. 文件级任务清单

### `MainActivity.kt`

必须完成：

- 新的 `LayoutSpec` 计算入口
- portrait / landscape 布局重写
- scrim 链路修正或删除
- `resolveTrackTextAvailableBounds()` 修正
- `contentWidthPx` 在布局中的消费
- `statusText` overlay 在新布局下的可见性回归验证
- active transition 期间 metadata container 宽度冻结策略

注意：

- P0 不强制提取 `ScreenAdapter` 出文件。
- 不要把旧 text area 概念继续塞回新实现。

### `TrackTextLayoutModels.kt`

必须完成：

- `TrackTextScreenMetrics.shortEdgePx`
- 如需要，补充 `TrackTextBounds` 或辅助模型字段

### `TrackTextLayoutPolicy.kt`

必须完成：

- 新的字号策略
- 新的高度预算输入
- 继续保持 title / artist / album 让步顺序

### `AndroidTrackTextLayoutEngine.kt`

必须完成：

- `contentWidthPx` 产出
- P1 里的 `breakStrategy` / `hyphenationFrequency` 尝试
- 如推进 measurement-first，则作为生产测量实现

### `TrackTextScene.kt`

必须完成：

- 增加 `contentWidthPx`

### `TrackTextLayoutPolicyTest.kt`

必须完成：

- 更新所有基于旧公式写死的期望值
- 增加低密度大屏和中英文混排样本

## 14. 提交建议

建议按以下提交顺序推进：

1. `docs(layout): add cover-first layout refactor plan`
   对应：`P0-A` 交付物与本计划文档。
2. `feat(layout): define readability baseline, layout spec contract, and short-edge metrics`
   对应：`P0-A`、`P0-B`。
3. `refactor(layout): introduce layout spec resolver in ScreenAdapter`
   对应：`P0-C`。
4. `refactor(layout): rebuild portrait metadata layout and scrim handling`
   对应：`P0-D`、`P0-F`。
5. `refactor(layout): rebuild landscape metadata layout with text-width guardrails`
   对应：`P0-E`。
6. `fix(text): align bounds handoff with dynamic text budget`
   对应：`P0-G`。
7. `feat(text): add content width reporting for basic shrink-wrap`
   对应：`P0-I`。
8. `tune(text): raise readability baseline for low-density large screens`
   对应：`P0-H`。
9. `refactor(text): introduce measurer abstraction for precision shrink`
   对应：`P1-J`。
10. `tune(text): add balanced line breaking and transition token refinements`
    对应：`P1-K`、`P1-L`。
11. `chore(layout): remove obsolete fixed-ratio layout constants`
    对应：`P2-M`。

## 15. QA 清单

### 15.1 设备与比例

- `1080 x 1920` 竖屏
- `1920 x 1080` 横屏
- `16:10`
- `4:3`

### 15.2 文本样本

- 短歌名：`Yellow`
- 深色封面 + 短歌名
- 常规长度：`Blinding Lights`
- 超长英文标题
- 中英文混排标题
- 空 album
- 长 artist / 长 album

### 15.3 需要手工确认的场景

1. 首次进入页面
2. 方向切换
3. track 切换
4. rapid skip
5. rollback
6. 深色封面
7. 亮色封面
8. 低对比封面
9. `statusText` overlay 显示时
10. 短歌名切换到长歌名，或反向切换时

### 15.4 重点观察项

1. 封面是否明显大于旧版。
2. 短标题是否形成“内容贴合”的文字块。
3. 长标题是否仍可读。
4. 竖屏移除分隔线后是否仍有清晰层次。
5. 3 米外是否能辨认标题与艺术家。
6. 是否出现容器历史高度锁死后续布局的问题。
7. 是否出现 scrim 逻辑静默失效的问题。
8. `statusText` overlay 是否仍然在预期位置且不被遮挡。
9. 转场期间 metadata container 是否因宽度重新收缩而抖动。

## 16. 风险与应对

### 风险 1：4:3 比例下封面过大，文字无处可放

应对：

- `LayoutSpec` 必须包含 `minReadableTextHeightPx`
- 预算不足时封面让步

### 风险 2：基础 shrink-wrap 不足以改善呼吸感

应对：

- P0 先落 `contentWidthPx`
- P1 再决定是否需要二段式 shrink-wrap

### 风险 3：measurement-first 破坏 policy 的 JVM 可测性

应对：

- 通过 `TextMeasurer` 接口注入
- 不直接把 Android 依赖塞进纯策略类

### 风险 4：布局重写后旧 scrim 分支悬空

应对：

- 把 scrim 清理并入 P0
- 不留延后到 P2 的无效链路

### 风险 5：大字号后转场位移不足

应对：

- 在 P1-L 统一复核 token

### 风险 6：source / target scene 宽度不同导致转场期容器抖动

应对：

- 在 P0-I 引入转场期间宽度冻结
- 冻结宽度取 source / target / budget 的最大值
- 转场结束后再提交最终 shrink-wrap 宽度

## 17. Definition of Done

只有满足以下全部条件，这轮工作才算完成：

1. portrait 和 landscape 都不再依赖固定文字区比例。
2. 封面尺寸和文字预算来自统一 `LayoutSpec`。
3. `resolveTrackTextAvailableBounds()` 不再锁死下一帧预算。
4. 竖屏分隔线移除后，层次感依然成立。
5. 基础 shrink-wrap 已落地，短标题时容器不再满宽。
6. `1080p 16:9` 竖屏画屏远距离可读性达到约定基线。
7. scrim 链路已被修正或清理，不存在悬空旧逻辑。
8. 旧固定比例布局常量已清理或被明确降级为非权威值。
9. 单元测试与手工 QA 样本都已更新。
10. `statusText` overlay 层级回归通过。
11. 转场期间 metadata container 宽度稳定，不出现明显横向抖动。

## 18. 当前建议

如果只允许做一轮高价值改造，优先完成以下集合：

1. `P0-A`
2. `P0-B`
3. `P0-C`
4. `P0-D`
5. `P0-E`
6. `P0-F`
7. `P0-G`
8. `P0-I`
9. `P0-H`

也就是：

- 先定可读性基线
- 再定布局预算合同
- 然后重写布局
- 先打通 bounds 和 shrink-wrap
- 最后再调字号系数

做到这一步，产品体验就会从“封面被死板文字区挤压”切换到“封面优先、文字自然呼吸、画屏可读”的新基线。
