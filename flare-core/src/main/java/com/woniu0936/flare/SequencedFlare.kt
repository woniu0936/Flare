package com.woniu0936.flare

/**
 * 带有唯一序列号的事件包装器
 */
@InternalFlareApi
data class SequencedFlare<T>(val id: Long, val payload: T)