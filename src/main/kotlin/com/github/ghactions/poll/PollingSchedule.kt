package com.github.ghactions.poll

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * 轮询节奏决策。纯函数，无状态，便于穷举测试。
 */
object PollingSchedule {

    /** 存在未结束的 run 时的间隔。 */
    val ACTIVE: Duration = 5.seconds

    /** 全部 run 已结束时的间隔。 */
    val IDLE: Duration = 60.seconds

    /** API 配额偏低时的降级间隔。 */
    val DEGRADED: Duration = 5.minutes

    /** 剩余配额低于此值即进入降级。 */
    const val LOW_QUOTA_THRESHOLD: Int = 100

    /**
     * @return 下一轮的等待时长；null 表示暂停轮询。
     */
    fun intervalFor(visible: Boolean, hasRunning: Boolean, rateLimitRemaining: Int?): Duration? = when {
        !visible -> null
        rateLimitRemaining != null && rateLimitRemaining < LOW_QUOTA_THRESHOLD -> DEGRADED
        hasRunning -> ACTIVE
        else -> IDLE
    }
}
