package com.woniu0936.flare

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle

/**
 * 在 Jetpack Compose 中安全地观察 Flare 事件。
 *
 * @param broadcaster ViewModel 中定义的 FlareBroadcaster 实例。
 * @param consumerTag 消费者的唯一标识。同一个屏幕内若有多个观察者监听同一个 broadcaster，必须使用不同的 tag。
 * @param minActiveState 接收事件的最小生命周期状态，默认为 STARTED（UI 可见时才处理）。
 * @param onEvent 接收到事件时的回调逻辑（suspend 函数，支持直接调用 Snackbar 等挂起函数）。
 */
@Composable
fun <T> ConsumeFlare(
    broadcaster: FlareBroadcaster<T>,
    consumerTag: String,
    minActiveState: Lifecycle.State = Lifecycle.State.STARTED,
    onEvent: suspend (T) -> Unit
) {
    val lifecycleOwner = LocalLifecycleOwner.current

    // 【关键防御】确保 onEvent 闭包始终是最新的，避免因闭包捕获旧状态导致的逻辑 Bug
    val currentOnEvent by rememberUpdatedState(onEvent)

    LaunchedEffect(broadcaster, consumerTag, lifecycleOwner) {
        // 1. 首次订阅时，初始化游标，丢弃历史陈旧事件
        broadcaster.initConsumerCursorIfNeeded(consumerTag)

        // 2. 结合生命周期安全地收集
        lifecycleOwner.repeatOnLifecycle(minActiveState) {
            broadcaster.flares.collect { sequencedFlare ->
                // 3. 询问游标校验器：这个信号弹我看过没？
                if (broadcaster.tryConsume(consumerTag, sequencedFlare.id)) {
                    // 没看过，执行消费逻辑
                    currentOnEvent(sequencedFlare.payload)
                }
            }
        }
    }
}