
**Flare** 是一个为现代 Android 架构（Jetpack Compose & Views）设计的 **绝对安全** 的一次性事件（One-off Events）分发库。

彻底告别 `Channel` 丢失事件的恐惧，告别 `SharedFlow` 屏幕旋转重复弹窗的噩梦。
Flare 借鉴了流式处理的 **Cursor (游标)** 机制，无论屏幕如何旋转、有多少个不同的订阅者同时监听，事件**绝对到达，且只执行一次**。

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
