package com.easysstun

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.TextView
import com.easysstun.databinding.FragmentMainBinding
import java.util.Locale

/**
 * Renders the home screen's statistics card and owns its expand/collapse
 * animation.
 *
 * The binder is recreated with the fragment view; the expanded flag lives in
 * the fragment so navigating away and back keeps the card open. It never
 * fetches anything: [StatsClient] produces a [StatsSnapshot] and the fragment
 * hands it over on the main thread.
 */
internal class StatsBinder(
    private val binding: FragmentMainBinding,
    initiallyExpanded: Boolean = false,
    private val onExpandedChanged: (Boolean) -> Unit = {},
) {
    var isExpanded: Boolean = initiallyExpanded
        private set

    init {
        binding.statsHeader.setOnClickListener {
            // The grid is hidden while the tunnel is down; toggling it there
            // would leave the card in a state the user cannot see.
            if (binding.statsGrid.visibility != View.VISIBLE) return@setOnClickListener
            setExpanded(!isExpanded, animate = true)
        }
        applyExpandedState()
    }

    /** Shows or hides the whole grid (the header stays visible). */
    fun setVisible(visible: Boolean) {
        binding.statsGrid.visibility = if (visible) View.VISIBLE else View.GONE
        if (visible) applyExpandedState()
    }

    /** Renders a successful sample and makes sure the grid is visible. */
    fun render(snapshot: StatsSnapshot) {
        binding.statsGrid.visibility = View.VISIBLE
        binding.statsAvgRttMs.text = String.format(Locale.ROOT, "%.1fms", snapshot.avgRttMs)
        binding.statsDownloadSpeed.text = stripByteUnit(snapshot.downloadSpeed)
        binding.statsUploadSpeed.text = stripByteUnit(snapshot.uploadSpeed)
        binding.statsConns.text = formatCount(snapshot.conns)
        binding.statsPeakDownloadSpeed.text = stripByteUnit(snapshot.peakDownloadSpeed)
        binding.statsPeakUploadSpeed.text = stripByteUnit(snapshot.peakUploadSpeed)
        binding.statsBytesSent.text = formatBytes(snapshot.bytesSent)
        binding.statsBytesRecv.text = formatBytes(snapshot.bytesRecv)
        binding.statsPriorityConns.text = formatCount(snapshot.priorityConns)
        binding.statsBulkConns.text = formatCount(snapshot.bulkConns)
        binding.statsPriorityActiveStreams.text = formatCount(snapshot.priorityActiveStreams)
        binding.statsBulkActiveStreams.text = formatCount(snapshot.bulkActiveStreams)
        binding.statsActiveStreams.text = formatCount(snapshot.activeStreams)
        binding.statsSlotRetiredDegraded.text = formatCount(snapshot.slotRetiredDegraded)
        binding.statsConnRotated.text = formatCount(snapshot.connRotated)
        binding.statsSlotProbeSlow.text = formatCount(snapshot.slotProbeSlow)
        binding.statsUptime.text = formatDuration(snapshot.uptimeSeconds)
    }

    /**
     * Renders the "endpoint not reachable" state: the hero slot carries the
     * status message and every other value is cleared so stale numbers from
     * the previous sample are not mistaken for live data.
     */
    fun renderUnavailable() {
        binding.statsAvgRttMs.text = binding.root.context.getString(R.string.stats_unavailable)
        clear(
            binding.statsDownloadSpeed,
            binding.statsUploadSpeed,
            binding.statsConns,
            binding.statsPeakDownloadSpeed,
            binding.statsPeakUploadSpeed,
            binding.statsBytesSent,
            binding.statsBytesRecv,
            binding.statsPriorityConns,
            binding.statsBulkConns,
            binding.statsPriorityActiveStreams,
            binding.statsBulkActiveStreams,
            binding.statsActiveStreams,
            binding.statsSlotRetiredDegraded,
            binding.statsConnRotated,
            binding.statsSlotProbeSlow,
            binding.statsUptime,
        )
    }

    private fun clear(vararg views: TextView) {
        for (view in views) view.text = ""
    }

    private fun formatCount(value: Int): String = String.format(Locale.ROOT, "%d", value)

    private fun setExpanded(expanded: Boolean, animate: Boolean) {
        if (isExpanded == expanded) return
        isExpanded = expanded
        onExpandedChanged(expanded)
        if (!animate) {
            applyExpandedState()
            return
        }
        animateArrow(expanded)
        animateHeight(expanded)
    }

    private fun applyExpandedState() {
        val expandable = binding.statsExpandable
        expandable.visibility = if (isExpanded) View.VISIBLE else View.GONE
        expandable.layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
        binding.statsHeaderArrow.rotation = if (isExpanded) 180f else 0f
    }

    private fun animateArrow(expanded: Boolean) {
        binding.statsHeaderArrow.animate()
            .rotation(if (expanded) 180f else 0f)
            .setDuration(ANIMATION_DURATION_MS)
            .setInterpolator(OvershootInterpolator(1.5f))
            .start()
    }

    /**
     * Animates the expandable section's height. The layout height is reset to
     * `WRAP_CONTENT` when the animation ends so a font-size or rotation change
     * cannot clip the content at a stale pixel height.
     */
    private fun animateHeight(expand: Boolean) {
        val view = binding.statsExpandable
        if (expand) {
            view.measure(
                View.MeasureSpec.makeMeasureSpec(
                    (view.parent as View).width,
                    View.MeasureSpec.EXACTLY,
                ),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            )
            val targetHeight = view.measuredHeight
            view.visibility = View.VISIBLE
            view.layoutParams.height = 0
            ValueAnimator.ofInt(0, targetHeight).apply {
                duration = ANIMATION_DURATION_MS
                interpolator = DecelerateInterpolator()
                addUpdateListener {
                    view.layoutParams.height = it.animatedValue as Int
                    view.requestLayout()
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        view.layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
                        view.requestLayout()
                    }
                })
                start()
            }
        } else {
            val startHeight = view.height
            if (startHeight <= 0) {
                view.visibility = View.GONE
                return
            }
            ValueAnimator.ofInt(startHeight, 0).apply {
                duration = ANIMATION_DURATION_MS
                interpolator = AccelerateInterpolator()
                addUpdateListener {
                    view.layoutParams.height = it.animatedValue as Int
                    view.requestLayout()
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        view.visibility = View.GONE
                        view.layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
                        view.requestLayout()
                    }
                })
                start()
            }
        }
    }

    private companion object {
        const val ANIMATION_DURATION_MS = 350L

        /** "1.5 MB/s" -> "1.5 M/s"; values without a byte unit are unchanged. */
        val SPEED_UNIT = Regex("(?<=[KMGT])B/s")
        val SIZE_UNIT = Regex("(?<=[KMGT])B$")

        fun stripByteUnit(value: String): String {
            if (value.isEmpty()) return value
            return value.replace(SPEED_UNIT, "/s").replace(SIZE_UNIT, "")
        }
    }
}
