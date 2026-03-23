
**Flare** 是一个为现代 Android 架构（Jetpack Compose & Views）设计的 **绝对安全** 的一次性事件（One-off Events）分发库。

彻底告别 `Channel` 丢失事件的恐惧，告别 `SharedFlow` 屏幕旋转重复弹窗的噩梦。
Flare 借鉴了流式处理的 **Cursor (游标)** 机制，无论屏幕如何旋转、有多少个不同的订阅者同时监听，事件**绝对到达，且只执行一次**。

#### 为什么选择 Flare？(Why Flare?)

在现代 Android 开发中，处理一次性事件（如导航跳转、Toast 提示）时，使用传统的 `Channel` 或 `SharedFlow` 往往会导致事件丢失或重复消费的问题，尤其在屏幕旋转或应用后台时。详情请参见 [Why_Flare.md](docs/Why_Flare.md)。

Flare 通过创新的游标机制，确保事件绝对安全且只执行一次，彻底解决这些痛点，为开发者提供无忧的开发体验。

#### 深入了解架构 (Architecture)

想要了解 Flare 的核心设计思想、源码解析和最佳使用场景？请参考 [Flare_Architecture.md](docs/Flare_Architecture.md)。

#### 快速上手 (Quick Start)

**1. 定义事件与 ViewModel**
```kotlin
sealed interface MainEvent {
    data object NavigateToLogin : MainEvent
    data class ShowToast(val msg: String) : MainEvent
}

class MainViewModel : ViewModel() {
    // 创建一个广播器
    val eventFlare = FlareBroadcaster<MainEvent>()

    fun logout() {
        // 发射信号弹
        eventFlare.fire(MainEvent.ShowToast("已退出"))
        eventFlare.fire(MainEvent.NavigateToLogin)
    }
}
```

**2. 在 Compose 中优雅消费 (支持多消费者并发)**
```kotlin
@Composable
fun MainScreen(viewModel: MainViewModel) {
    
    // 消费者A：只负责导航
    ConsumeFlare(viewModel.eventFlare, consumerTag = "NavConsumer") { event ->
        if (event is MainEvent.NavigateToLogin) navController.navigate("login")
    }

    // 消费者B：只负责弹窗 (由于 block 是 suspend 的，可以直接调用 Snackbar)
    ConsumeFlare(viewModel.eventFlare, consumerTag = "ToastConsumer") { event ->
        if (event is MainEvent.ShowToast) snackbarHostState.showSnackbar(event.msg)
    }
}
```

**3. 在 Activity/Fragment 中无缝使用**
```kotlin
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // 与 Compose 保持绝对一致的 API 体验
        observeFlare(viewModel.eventFlare, consumerTag = "ActivityConsumer") { event ->
             when(event) {
                 is MainEvent.ShowToast -> Toast.makeText(this, event.msg, Toast.LENGTH_SHORT).show()
                 else -> {}
             }
        }
    }
}
```
