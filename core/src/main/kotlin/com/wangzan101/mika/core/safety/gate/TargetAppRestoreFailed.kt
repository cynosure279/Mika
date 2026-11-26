package com.wangzan101.mika.core.safety.gate

/** 目标 App 无法恢复到 Mika 的屏幕,把原因交给模型。 */
class TargetAppRestoreFailed(val packageName: String, message: String) : Exception(message) {
    internal companion object { const val CODE = "target_app_restore_failed" }
}
