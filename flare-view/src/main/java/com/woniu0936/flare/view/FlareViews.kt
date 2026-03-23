package com.woniu0936.flare.view

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.woniu0936.flare.FlareBroadcaster
import com.woniu0936.flare.InternalFlareApi
import kotlinx.coroutines.launch

/**
 * 在传统的 Activity / Fragment 中安全地观察 Flare 事件。
 *
 * @param broadcaster ViewModel 中定义的 FlareBroadcaster 实例。
 * @param consumerTag 消费者的唯一标识。同一个屏幕内若有多个观察者监听同一个 broadcaster，必须使用不同的 tag。
 * @param minActiveState 接收事件的最小生命周期状态，默认为 STARTED。
 * @param onEvent 接收到事件时的回调逻辑。
 */
@OptIn(InternalFlareApi::class)
fun <T> LifecycleOwner.observeFlare(
    broadcaster: FlareBroadcaster<T>,
    consumerTag: String,
    minActiveState: Lifecycle.State = Lifecycle.State.STARTED,
    onEvent: suspend (T) -> Unit
) {
    // 1. 初始化消费者游标
    broadcaster.initConsumerCursorIfNeeded(consumerTag)

    lifecycleScope.launch {
        // 2. 结合生命周期安全收集
        repeatOnLifecycle(minActiveState) {
            broadcaster.flares.collect { sequencedFlare ->
                // 3. 检查游标，防止由于屏幕旋转引起的 SharedFlow Replay 被重复执行
                if (broadcaster.tryConsume(consumerTag, sequencedFlare.id)) {
                    onEvent(sequencedFlare.payload)
                }
            }
        }
    }

    this.lifecycle.addObserver(object : DefaultLifecycleObserver {
        override fun onDestroy(owner: LifecycleOwner) {
            broadcaster.resetConsumer(consumerTag)
            owner.lifecycle.removeObserver(this)
        }
    })
}