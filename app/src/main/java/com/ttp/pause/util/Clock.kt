package com.ttp.pause.util

/**
 * 可注入的时间接口（替代直接调用 System.currentTimeMillis()）
 *
 * 生产环境使用 [RealClock]，测试可注入模拟时钟。
 */
interface Clock {
    fun now(): Long
}

/** 生产环境时钟 */
object RealClock : Clock {
    override fun now(): Long = System.currentTimeMillis()
}
