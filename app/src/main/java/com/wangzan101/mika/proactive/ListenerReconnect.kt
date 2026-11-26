package com.wangzan101.mika.proactive

/**
 * 请系统重绑之后,监听还是没连上时再做什么。纯逻辑,[ProactiveRuntime] 照着做。
 *
 * 「请系统重绑」只对系统记着「暂停」的监听有效,也就是 Mika 自己请它解绑过的(锁屏、完全退出)。
 * 系统自己没绑上的,这个请求没有任何效果:有的系统在 App 没在运行、又不允许它自启动时拒绝绑定监听,而且不再重试
 * (开机、进程被强行停止之后都是这样);之后用户打开 Mika,监听已授权、没连着,也不在「暂停」里,怎么请都连不上。
 * 这时先按组件名请系统解绑一次——它就进了「暂停」——再请重绑,系统才会真的去绑;Mika 此刻在运行,绑得上。
 *
 * 只在这几样都成立时做:在看守、没锁屏(锁着本来就不该连着)、没连上、通知使用权还在;
 * 并且系统有按组件名解绑的接口([SDK_BY_NAME] 起)。更早的系统上没有办法,照旧等系统自己绑。
 * 系统只是绑得慢时多做这一次也无妨:它正在绑的那一次照常完成;已经绑上、只是连上的回调来得晚的,
 * 会多断开再连上一次,补收时重复的由去重挡掉。
 */
object ListenerReconnect {
    /** 请重绑之后等这么久再看;正常连上用不了一秒。 */
    const val WAIT_MS = 3_000L
    /** 按组件名解绑的接口从这个系统版本开始有。 */
    const val SDK_BY_NAME = 34

    enum class Step {
        /** 什么都不做。 */
        None,
        /** 按组件名请系统解绑,再请重绑。 */
        Reregister,
    }

    fun next(watching: Boolean, locked: Boolean, connected: Boolean, authorized: Boolean, sdk: Int): Step =
        if (watching && !locked && !connected && authorized && sdk >= SDK_BY_NAME) Step.Reregister else Step.None
}
