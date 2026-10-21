package com.tmplayer.data

/** A Telegram-provided sponsored message, reduced to what the media list can present. */
data class SponsoredItem(
    val messageId: Long,
    val label: String,
    val title: String,
    val text: String,
    val buttonText: String,
    val sponsorInfo: String,
    val additionalInfo: String,
    val sponsorUrl: String,
    val canBeReported: Boolean,
    val miniThumbnail: ByteArray?,
    val thumbnailFileId: Int,
)

data class SponsoredBatch(
    val messages: List<SponsoredItem>,
    val messagesBetween: Int,
)

data class SponsoredReportOption(val id: ByteArray, val text: String)

sealed interface SponsoredReportOutcome {
    data class Options(val title: String, val options: List<SponsoredReportOption>) :
        SponsoredReportOutcome

    data object Reported : SponsoredReportOutcome
    data object AdsHidden : SponsoredReportOutcome
    data object PremiumRequired : SponsoredReportOutcome
    data object Unavailable : SponsoredReportOutcome
}
