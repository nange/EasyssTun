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

/** 展开状态由 Fragment 持有，实例随视图重建；本类不取数，只渲染主线程交来的 [StatsSnapshot]。 */
internal class StatsBinder(
    private val binding: FragmentMainBinding,
    initiallyExpanded: Boolean = false,
    private val onExpandedChanged: (Boolean) -> Unit = {},
) {
    var isExpanded: Boolean = initiallyExpanded
        private set

    init {
        binding.statsHeader.setOnClickListener {
            // 隧道未运行时网格是隐藏的；此时切换展开会留下用户看不见的状态。
            if (binding.statsGrid.visibility != View.VISIBLE) return@setOnClickListener
            setExpanded(!isExpanded, animate = true)
        }
        applyExpandedState()
    }

    fun setVisible(visible: Boolean) {
        binding.statsGrid.visibility = if (visible) View.VISIBLE else View.GONE
        if (visible) applyExpandedState()
    }

    /** 可见性由调用方通过 [setVisible] 控制：隧道停止后迟到的样本不得把卡片重新显示出来。 */
    fun render(snapshot: StatsSnapshot) {
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

    /** 端点不可达时清空其余数值：避免上次样本的过期数字被当成实时数据。 */
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

    /** 动画结束后高度重置为 `WRAP_CONTENT`：否则字体或旋转变化会按过期像素高度裁切内容。 */
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
    }
}
