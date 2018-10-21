package com.ea.viewlifecycle

import android.animation.ValueAnimator
import android.annotation.TargetApi
import android.arch.lifecycle.Lifecycle
import android.content.Context
import android.graphics.Region
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.support.v7.util.DiffUtil
import android.support.v7.util.ListUpdateCallback
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager

/**
 * A class that is responsible for dispatching lifecycle state to the children of the [viewGroup].
 * To do this, it builds a list of child views sorted according to their visibility level.
 * Currently visible views, i.e. having at least one pixel that is not overlapped
 * by other views in layout, belong to level 0. Views at levels greater than 0 cannot have
 * lifecycle state greater than [Lifecycle.State.CREATED].
 *
 * Dispatching happens in two cases:
 * - when the lifecycle state of the [viewGroup] is changed.
 * - during a layout pass in the [viewGroup]. Since the layout state of the views may change,
 * dispatcher rebuilds their visibility levels.
 */
internal class ViewLifecycleDispatcher(private val viewGroup: ViewGroup) {

    private var lastLayoutLevels = arrayListOf<ViewLevelData>()

    private var lastDispatchedState: Lifecycle.State? = null

    private val handler = Handler(Looper.getMainLooper())

    // used to postpone lifecycle state transition in case of layout animations
    private val dispatchDelay: Long

    private val viewLevelComparator: Comparator<View> by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            ViewLevelComparator()
        } else {
            ViewLevelComparatorSupport()
        }
    }

    init {
        val wm = viewGroup.context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val displayRefreshDelay = 1000f / wm.defaultDisplay.refreshRate
        val animationDelay = ValueAnimator.getFrameDelay()
        dispatchDelay = Math.max(displayRefreshDelay.toLong(), animationDelay) * 2
    }

    private val layoutListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
        handler.removeCallbacks(dispatchOnLayoutRun)
        handler.postDelayed(dispatchOnLayoutRun, dispatchDelay)
    }

    private val dispatchOnLayoutRun = { dispatchLifecycleOnLayout() }

    fun attach() {
        viewGroup.addOnLayoutChangeListener(layoutListener)
    }

    fun detach() {
        viewGroup.removeOnLayoutChangeListener(layoutListener)
        handler.removeCallbacks(dispatchOnLayoutRun)
    }

    fun dispatchLifecycleState(state: Lifecycle.State) {
        if (state == lastDispatchedState) {
            return
        }
        var stateToDispatch = state
        if (!viewGroup.isDisplayed) {
            stateToDispatch = Lifecycle.State.CREATED
        }
        if (!stateToDispatch.isAtLeast(Lifecycle.State.STARTED)) {
            for (i in 0 until viewGroup.childCount) {
                val view = viewGroup.getChildAt(i)
                view.rawLifecycleOwner?.lifecycle?.forceMarkState(stateToDispatch)
            }
            lastDispatchedState = stateToDispatch
        } else {
            if (lastLayoutLevels.size == 0) {
                lastLayoutLevels = buildLayoutLevels()
                if (lastLayoutLevels.size == 0) {
                    lastDispatchedState = stateToDispatch
                    return
                }
            }

            for (i in 0 until lastLayoutLevels.size) {
                lastLayoutLevels[i].updateState(stateToDispatch)
            }
            lastDispatchedState = stateToDispatch
        }
    }

    private fun dispatchLifecycleOnLayout() {
        val owner = viewGroup.rawLifecycleOwner
                ?: throw IllegalStateException("ViewLifecycleDispatcher is attached " +
                        "but View's LifecycleOwner is null.")

        val currentState = owner.lifecycle.currentState
        if (!currentState.isAtLeast(Lifecycle.State.STARTED) || !viewGroup.isDisplayed) {
            return
        }

        val newLevels = buildLayoutLevels()

        val diffCallback = object : DiffUtil.Callback() {
            override fun getOldListSize() = lastLayoutLevels.size
            override fun getNewListSize() = newLevels.size

            override fun areContentsTheSame(oldPos: Int, newPos: Int): Boolean {
                return lastLayoutLevels[oldPos].visibility == newLevels[newPos].visibility &&
                        lastLayoutLevels[oldPos].level == newLevels[newPos].level
            }

            override fun areItemsTheSame(oldPos: Int, newPos: Int): Boolean {
                return lastLayoutLevels[oldPos].view === newLevels[newPos].view
            }
        }

        val diffResult = DiffUtil.calculateDiff(diffCallback, true)
        diffResult.dispatchUpdatesTo(object : ListUpdateCallback {
            override fun onChanged(position: Int, count: Int, payload: Any?) {
                for (index in position until position + count) {
                    newLevels
                            .firstOrNull { it.view == lastLayoutLevels[index].view }
                            ?.updateState(currentState)

                }
            }

            override fun onMoved(fromPosition: Int, toPosition: Int) {
                lastLayoutLevels[fromPosition].updateState(currentState)
                newLevels[toPosition].updateState(currentState)
            }

            override fun onInserted(position: Int, count: Int) {
                for (index in position until position + count) {
                    newLevels[index].updateState(currentState)
                }
            }

            override fun onRemoved(position: Int, count: Int) {
                for (index in position until position + count) {
                    lastLayoutLevels[index].view.destroy()
                }
            }
        })

        lastLayoutLevels = newLevels
        lastDispatchedState = currentState
    }

    private fun buildLayoutLevels(): ArrayList<ViewLevelData> {
        // sort views by z order: top displayed (elevation + z translation) come first
        val zSortedViews = ArrayList<View>()
        for (i in viewGroup.childCount - 1 downTo 0) {
            zSortedViews.add(viewGroup.getChildAt(i))
        }
        zSortedViews.sortWith(viewLevelComparator)

        val levelViews = ArrayList<ViewLevelData>()
        val levels = ArrayList<Region>()

        zSortedViews.forEach {
            val viewRegion = Region(
                    it.left + it.translationX.toInt(),
                    it.top + it.translationY.toInt(),
                    it.right + it.translationX.toInt(),
                    it.bottom + it.translationY.toInt())

            // find view level
            var viewLevel = 0
            for (i in levels.size - 1 downTo 0) {
                val region = levels[i]
                val unionRegion = Region(region)
                unionRegion.op(viewRegion, Region.Op.UNION)
                if (unionRegion == region) {
                    // view is completely hidden behind the i'th region
                    viewLevel = i + 1
                }
            }

            // the level is discovered, save it
            if (viewLevel >= levels.size) {
                // view is behind the last level, insert new
                levels += viewRegion
                levelViews += ViewLevelData.of(it, levels.size - 1)
            } else {
                // union view's region with that of its level
                levels[viewLevel].op(viewRegion, Region.Op.UNION)

                var levelIndex = 0
                for (i in 0 until levelViews.size) {
                    if (levelViews[i].level == viewLevel) {
                        levelIndex = i
                        break
                    }
                }
                levelViews.add(levelIndex, ViewLevelData.of(it, viewLevel))
            }
        }

        return levelViews
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    private class ViewLevelComparator : Comparator<View> {
        override fun compare(v1: View, v2: View): Int {
            return (v2.z - v1.z).toInt()
        }
    }

    private class ViewLevelComparatorSupport : Comparator<View> {
        override fun compare(v1: View, v2: View) = 0
    }

    private class ViewLevelData(val view: View, val level: Int, val visibility: Int) {

        fun updateState(state: Lifecycle.State) {
            if (level == 0 && view.isDisplayed) {
                view.rawLifecycleOwner?.lifecycle?.forceMarkState(state)
            } else {
                view.rawLifecycleOwner?.lifecycle?.forceMarkState(Lifecycle.State.CREATED)
            }
        }

        companion object Factory {
            fun of(view: View, level: Int) = ViewLevelData(view, level, view.visibility)
        }
    }
}