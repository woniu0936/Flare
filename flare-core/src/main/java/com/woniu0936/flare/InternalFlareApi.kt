package com.woniu0936.flare

/**
 * 标记为 Flare 库内部使用的 API。
 * 外部开发者如果强行调用被此注解标记的方法，IDE 会直接报错标红。
 */
@RequiresOptIn(
    level = RequiresOptIn.Level.ERROR,
    message = "这是 Flare 库的内部核心 API，请勿直接调用！请使用 ConsumeFlare() 或 observeFlare()。"
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY)
annotation class InternalFlareApi