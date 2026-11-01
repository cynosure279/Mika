package com.wangzan101.mika.core.uistate

/**
 * 这次任务在哪块屏上操作:自己的虚拟屏(默认),还是用户的主屏。
 * 手机能力据此写提示词那一段的第一句——模型要知道自己在动的是不是用户看得见的屏。
 */
enum class ScreenHost { Virtual, Main }
