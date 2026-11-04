package com.wangzan101.mika.core.export

/** 警告码到本地化文字的映射;Markdown/HTML 等后端共用,避免各自维护一份。 */
object ExportWarningText {
    fun line(warning: ExportWarning, labels: ExportLabels): String = when (warning.code) {
        "run_record_missing" -> labels.runRecordMissing
        "asset_missing", "asset_unreadable" -> labels.missingAsset.format(warning.detail)
        "asset_too_large", "asset_total_too_large" -> labels.tooLarge.format(warning.detail)
        "timestamp_unknown" -> "${labels.unknown}: ${warning.detail}"
        else -> warning.detail
    }
}
