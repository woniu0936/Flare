# 🎇 Flare 源码解析：彻底终结 Android 一次性事件的架构痛点

在现代 Android 架构（Jetpack Compose / MVI）中，**“如何优雅且安全地处理一次性事件（One-off Events）”** 一直是开发者争论不休的难题。

导航跳转、Toast 提示、单次弹窗……如果我们使用 `Channel`，退到后台时容易造成事件堆积导致崩溃；如果使用 `SharedFlow`，屏幕旋转时的 Replay（回放）机制又会导致重复跳转的灾难。

**Flare** 的诞生正是为了终结这一乱象。本文将结合 Flare 的极简源码，深度剖析它是如何利用 **“SharedFlow 缓冲 + 状态游标（Cursor）”** 机制，实现绝对生命周期安全的。

---

## 💡 核心设计思想：从“水管模型”到“游标模型”

传统的 `SharedFlow` 就像一根**水管**：ViewModel 负责倒水，UI 负责接水。如果 UI 退到了后台（拿着杯子走开了），水要么流失了（丢事件），要么积压在管子里，等 UI 一回来喷他一脸（重复消费）。

**Flare 引入了后端消息队列（如 Kafka）中经典的“消费者游标（Cursor）”概念：**

1.  **发号施令**：ViewModel 发出的每一个事件，都会被打上一个绝对递增的唯一 ID（序列号）。
2.  **安全缓存**：底层依然使用 `SharedFlow`，并且主动开启了 `replay` 缓存。这样 UI 在后台时，事件绝不会丢失，而是按顺序排队。
3.  **智能阀门（游标校验）**：UI 在接水前，Flare 会拦截并核对账本：*“这个 UI 组件，上次处理到第几号事件了？”* 如果传来的事件 ID 小于或等于记录的游标，说明这是屏幕旋转导致的“历史遗留脏数据”，直接丢弃！

---

## 🔍 源码深度拆解

Flare 的核心代码不到 200 行，但每一行都经过了严密的推敲。我们将其拆分为三个核心支柱：

### 支柱一：事件的唯一身份（SequencedFlare）

```kotlin
@InternalFlareApi
data class SequencedFlare<T>(val id: Long, val payload: T)
```
这是底层流转的数据包。外部开发者传入的是单纯的 `payload`（如一个 `String` 或一个 `密封类`），Flare 在内部强制为其包裹了一层 `id`。这是整个防重复机制的基石。

### 支柱二：绝对安全的广播引擎（FlareBroadcaster）

在 `FlareBroadcaster` 内部，我们看到了对 `SharedFlow` 的精妙配置：

```kotlin
class FlareBroadcaster<T>(
    replayCacheSize: Int = 10,
    bufferCapacity: Int = 64
) {
    private val idGenerator = AtomicLong(0)

    // 管道配置：带缓存、有额外容量、溢出时丢弃最老
    private val _flares = MutableSharedFlow<SequencedFlare<T>>(
        replay = replayCacheSize,
        extraBufferCapacity = bufferCapacity,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    
    // 游标账本：记录每个消费者（Tag）最后消费过的事件 ID
    private val consumerCursors = ConcurrentHashMap<String, Long>()

    // 无阻塞发射 API
    fun fire(payload: T) {
        val newId = idGenerator.incrementAndGet()
        _flares.tryEmit(SequencedFlare(newId, payload))
    }
}
```
**设计亮点：**
1.  **无阻塞（Fire and Forget）**：配置了 `extraBufferCapacity` 和 `DROP_OLDEST` 后，`tryEmit` 永远不会失败，且不需要挂起。这意味着开发者在 ViewModel 中随时随地调用 `fire()` 即可，不需要开辟协程，极大地提升了开发体验（DX）。
2.  **并发安全**：使用 `AtomicLong` 生成 ID，使用 `ConcurrentHashMap` 记录游标，保证了多线程高并发下的绝对安全。

### 支柱三：核心拦截器（tryConsume 原子校验）

这是 Flare 最具魔法的地方，也是解决“屏幕旋转重复执行”的终极杀招：

```kotlin
@InternalFlareApi
fun tryConsume(consumerTag: String, eventId: Long): Boolean {
    var shouldProcess = false
    // compute 方法保证了“读取 -> 比较 -> 写入”是一个原子操作
    consumerCursors.compute(consumerTag) { _, lastProcessedId ->
        val currentLastId = lastProcessedId ?: -1L
        if (eventId > currentLastId) {
            // 这个事件是最新的！允许消费，并将游标前移到当前 eventId
            shouldProcess = true
            eventId 
        } else {
            // 这个事件已经处理过了（屏幕旋转导致的 SharedFlow Replay）
            // 拒绝消费，游标保持不变
            currentLastId 
        }
    }
    return shouldProcess
}
```
**场景推演：**
当手机发生横竖屏切换时，Activity 销毁重建，UI 重新订阅流。此时底层原生的 `SharedFlow` 会立刻将缓存的最新事件**重放（Replay）**过来。
但 Flare 的拦截器发现，传来的 `eventId` 并不大于字典里存的 `lastProcessedId`。拦截器返回 `false`，事件被静默抛弃。**Bug 就在这无形之中被消灭了。**

### 支柱四：顶级的 Compose 适配素养

对于外层封装，Flare 提供了极简的 `@Composable` 语法糖，但内部暗藏玄机：

```kotlin
@Composable
fun <T> ConsumeFlare(..., onEvent: suspend (T) -> Unit) {
    // 【关键防御】：保证挂起闭包永远捕获最新的状态，防止幽灵 Bug
    val currentOnEvent by rememberUpdatedState(onEvent)

    LaunchedEffect(...) {
        lifecycleOwner.repeatOnLifecycle(minActiveState) {
            broadcaster.flares.collect { sequencedFlare ->
                if (broadcaster.tryConsume(consumerTag, sequencedFlare.id)) {
                    currentOnEvent(sequencedFlare.payload) // 安全执行
                }
            }
        }
    }
}
```
结合了 `repeatOnLifecycle`，当 App 退到后台时，收集自动暂停。当回到前台时，配合底层的游标机制，按顺序、不重复地消费错过的事件。而且利用 `rememberUpdatedState`，确保了即使 Compose 发生重组，执行的依然是最新的业务闭包。

---

## 🛠️ Flare 的最佳使用场景（什么时候该用？）

Flare 并不是用来完全替代 `StateFlow` 的。遵循 MVI 和 Google 官方架构指南，UI 的展示应该由状态（State）驱动，而 Flare 专注于处理**“瞬时动作”**。

### ✅ 完美适用场景 (Use Cases)

1.  **页面导航（Navigation）**
    *   *场景*：登录成功后跳转到主页；支付失败后跳转到错误页。
    *   *优势*：屏幕怎么旋转，绝不会触发两次 `navController.navigate()` 导致崩溃。
2.  **一次性 UI 提示（Toast / Snackbar）**
    *   *场景*：网络请求错误提示；表单提交成功提示。
    *   *优势*：因为 Flare 是队列执行（挂起函数依次处理），瞬间连发 3 个错误提示，Snackbar 会**排队依次显示**，而不会互相覆盖丢失。
3.  **触发单次动画或音效（Triggers）**
    *   *场景*：点赞成功后，触发一个撒花动画或播放一个音效。
4.  **埋点与打点日志（Analytics）**
    *   *场景*：业务逻辑完成时，向外抛出事件，由外层的 AnalyticsConsumer 统一收集上传。

### ❌ 不适用的场景 (Anti-Patterns)

1.  **展示长期的 UI 数据**
    *   *错误用法*：用 Flare 发送一个 `User(name="John")`，让 UI 收到后去渲染名字。
    *   *正确做法*：请使用 `StateFlow<UiState>`。因为如果 UI 还没初始化完成事件就发完了，或者由于复杂的重组逻辑，状态没有持久化，UI 就会显示空白。
2.  **控制加载进度条（Loading Spinner）**
    *   *错误用法*：发送 `ShowLoading` 和 `HideLoading` 两个事件。
    *   *正确做法*：在 UiState 中定义 `val isLoading: Boolean`。

## 结语

通过将 **事件流（Stream）** 与 **持久化状态（Cursor）** 结合，Flare 优雅地填补了 Jetpack 架构中一次性事件处理的空白。

它不侵入你的业务逻辑，仅需几行代码，就能让你的 App 在最恶劣的生命周期摧残下，依然稳如泰山。欢迎在你的下一个 Android / Compose 项目中引入 Flare！