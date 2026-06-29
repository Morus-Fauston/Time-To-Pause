package com.ttp.pause.config

import com.ttp.pause.Constants
import com.ttp.pause.data.QuotaStore

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
    val dayStartHour: Float = 6f,
    val dayEndHour: Float = 24f
) {
    companion object {
        /** 从 Constants 构建默认配置 */
        fun fromConstants() = RateConfig(
            consumeDay = Constants.CONSUME_DAY.toFloat(),
            consumeNight = Constants.CONSUME_NIGHT.toFloat(),
            recoverDay = Constants.RECOVER_DAY.toFloat(),
            recoverNight = Constants.RECOVER_NIGHT.toFloat(),
            dayStartHour = Constants.DAY_START_HOUR,
            dayEndHour = Constants.DAY_END_HOUR
        )

        /** 从 SharedPreferences 读取运行时配置 */
        fun fromStore(store: QuotaStore): RateConfig = RateConfig(
            consumeDay = store.consumeDay.toFloat(),
            consumeNight = store.consumeNight.toFloat(),
            recoverDay = store.recoverDay.toFloat(),
            recoverNight = store.recoverNight.toFloat(),
            dayStartHour = store.dayStartHour,
            dayEndHour = store.dayEndHour
        )
    }
}
