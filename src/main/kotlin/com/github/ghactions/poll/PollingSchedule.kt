package com.github.ghactions.poll

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
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
     * 等待 Git 仓库就绪时的探测间隔。
     * 这条路径只查 IDE 内存中的仓库列表，不发任何 HTTP 请求，因此可以探得很勤。
     */
    val REPO_PROBE: Duration = 500.milliseconds

    /**
     * 连续这么多轮拿不到仓库，才认定当前项目确实没有 GitHub remote。
     * 在此之前保持「加载中」——IDE 启动初期 Git 尚未初始化完，
     * 过早断言「没有 remote」会让用户以为自己配置错了。
     * 配合 [REPO_PROBE] 约合 3 秒宽限期。
     */
    const val REPO_GRACE_ROUNDS: Int = 6

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
