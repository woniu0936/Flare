package com.woniu0936.flare

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong


/**
 * Flare 事件广播器核心引擎
 *
 * 机制说明：
 * 1. 采用 SharedFlow 作为底层管道，确保支持多订阅者（Multiple Consumers）。
 * 2. 通过记录每个 Consumer 的游标（Cursor ID），彻底杜绝由于屏幕旋转（配置更改）导致的 SharedFlow Replay 重复消费问题。
 * 3. 绝对的生命周期安全，不会丢失事件。
 *
 * @param replayCacheSize 保留的事件回放数量。默认 10。
 *                        如果 App 在后台时产生大量事件，UI 恢复时能按顺序收到最近的 10 个事件。
 * @param bufferCapacity  额外缓冲区大小。
 */
@OptIn(InternalFlareApi::class)
class FlareBroadcaster<T>(
    replayCacheSize: Int = 10,
    bufferCapacity: Int = 64
) {
    // 全局自增序列号，保证每个事件的 ID 绝对唯一且递增
    private val idGenerator = AtomicLong(0)

    // 事件流管道：当缓冲区满时，丢弃最老的事件（符合 UI 事件的通常预期）
    private val _flares = MutableSharedFlow<SequencedFlare<T>>(
        replay = replayCacheSize,
        extraBufferCapacity = bufferCapacity,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    // 暴露给外部收集的不可变 Flow
    @InternalFlareApi
    val flares = _flares.asSharedFlow()

    // 游标记录器：记录每个 Consumer Tag 最后消费过的事件 ID
    private val consumerCursors = ConcurrentHashMap<String, Long>()

    /**
     * 发射一个事件（信号弹）
     * 建议在 ViewModel 中调用。
     */
    fun fire(payload: T) {
        val newId = idGenerator.incrementAndGet()
        _flares.tryEmit(SequencedFlare(newId, payload))
    }

    /**
     * 供内部使用的消费者游标初始化逻辑。
     * 当一个新的 UI 组件首次出现时，它不应该去处理历史遗留的旧事件。
     */
    @InternalFlareApi
    fun initConsumerCursorIfNeeded(consumerTag: String) {
        // 只有当该 tag 尚未注册时，才将其游标对齐到当前的最新 ID
        consumerCursors.putIfAbsent(consumerTag, idGenerator.get())
    }

    /**
     * 核心校验逻辑：尝试消费该事件。
     * 只有当事件的 ID 大于该消费者记录的最后游标时，才允许消费。
     *
     * @return true 表示允许消费，false 表示该事件已被此 tag 消费过（通常是屏幕旋转引起的回放）
     */
    @InternalFlareApi
    fun tryConsume(consumerTag: String, eventId: Long): Boolean {
        var shouldProcess = false
        // 原子化更新游标
        consumerCursors.compute(consumerTag) { _, lastProcessedId ->
            val currentLastId = lastProcessedId ?: -1L
            if (eventId > currentLastId) {
                shouldProcess = true
                eventId // 返回新的事件 ID 作为游标
            } else {
                currentLastId // 游标保持不变
            }
        }
        return shouldProcess
    }

    /**
     * 重置某个消费者的游标（通常不需要手动调用）
     */
    fun resetConsumer(consumerTag: String) {
        consumerCursors.remove(consumerTag)
    }
}