package dev.zanderp.opencfmoto

internal data class LiveDashboardButtonState(
    val action: Int,
    val status: Int,
    val background: Int,
    val statusColor: Int,
) {
    companion object {
        fun forActive(active: Boolean): LiveDashboardButtonState = if (active) {
            LiveDashboardButtonState(
                R.string.ridescreen_live_close, R.string.ridescreen_live_status_on,
                R.drawable.btn_liquid_sapphire, R.color.green_neon,
            )
        } else {
            LiveDashboardButtonState(
                R.string.ridescreen_live_open, R.string.ridescreen_live_status_off,
                R.drawable.btn_liquid_emerald, R.color.text_secondary,
            )
        }
    }
}
