package com.ttp.pause.config

/**
 * 费率与时段配置（可运行时调节）
 *
 * 从 [com.ttp.pause.Constants] 中拆出的可配置参数。
 * [QuotaEngine] 接收此对象而非直接引用 Constants。
 */
data class RateConfig(
    val consumeDay: Float = 10f,
    val consumeNight: Float = 16f,
    val recoverDay: Float = 5f,
    val recoverNight: Float = 3f,
    val dayStartHour: Int = 6,
    val dayEndHour: Int = 23
) {
    companion object {
        /** 从 Constants 构建默认配置 */
        fun fromConstants() = RateConfig(
            consumeDay = com.ttp.pause.Constants.CONSUME_DAY.toFloat(),
            consumeNight = com.ttp.pause.Constants.CONSUME_NIGHT.toFloat(),
            recoverDay = com.ttp.pause.Constants.RECOVER_DAY.toFloat(),
            recoverNight = com.ttp.pause.Constants.RECOVER_NIGHT.toFloat(),
            dayStartHour = com.ttp.pause.Constants.DAY_START_HOUR,
            dayEndHour = com.ttp.pause.Constants.DAY_END_HOUR
        )
    }
}
