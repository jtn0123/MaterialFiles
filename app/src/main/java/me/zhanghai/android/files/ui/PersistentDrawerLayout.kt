/*
 * Copyright (c) 2019 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.ui

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import androidx.annotation.AttrRes
import androidx.annotation.StyleRes
import androidx.core.content.res.use
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.children
import androidx.core.view.isInvisible
import androidx.customview.widget.ViewDragHelper
import me.zhanghai.android.files.util.layoutInStatusBar
import me.zhanghai.android.files.util.replaceSystemBarsInsets
import me.zhanghai.android.files.util.systemBarsInsets

class PersistentDrawerLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    @AttrRes defStyleAttr: Int = 0,
    @StyleRes defStyleRes: Int = 0
) : ViewGroup(context, attrs, defStyleAttr, defStyleRes) {
    private val leftDragger =
        ViewDragHelper.create(this, PersistentDrawerLayoutDragCallback(this, Gravity.LEFT))
    private val rightDragger =
        ViewDragHelper.create(this, PersistentDrawerLayoutDragCallback(this, Gravity.RIGHT))

    private var lastInsets: WindowInsetsCompat? = null

    init {
        if (fitsSystemWindows) {
            layoutInStatusBar = true
        }
    }

    override fun dispatchApplyWindowInsets(windowInsets: WindowInsets): WindowInsets {
        if (!fitsSystemWindows) {
            return windowInsets
        }
        val insets = WindowInsetsCompat.toWindowInsetsCompat(windowInsets, this)
        for (child in children) {
            if (isDrawerView(child)) {
                if (isLeftDrawerView(child)) {
                    ViewCompat.dispatchApplyWindowInsets(
                        child,
                        insets.replaceSystemBarsInsets(
                            insets.systemBarsInsets.left,
                            insets.systemBarsInsets.top,
                            0,
                            insets.systemBarsInsets.bottom
                        )
                    )
                } else {
                    ViewCompat.dispatchApplyWindowInsets(
                        child,
                        insets.replaceSystemBarsInsets(
                            0,
                            insets.systemBarsInsets.top,
                            insets.systemBarsInsets.right,
                            insets.systemBarsInsets.bottom
                        )
                    )
                }
            } else if (isFillView(child)) {
                ViewCompat.dispatchApplyWindowInsets(child, insets)
            }
        }
        lastInsets = insets
        updateContentViewsWindowInsets()
        return WindowInsetsCompat.CONSUMED.toWindowInsets()!!
    }

    internal fun updateContentViewsWindowInsets() {
        var contentInsets = lastInsets ?: return
        for (child in children) {
            if (isDrawerView(child)) {
                val childLayoutParams = child.layoutParams as LayoutParams
                val childRange = (
                    childLayoutParams.leftMargin + child.measuredWidth +
                        childLayoutParams.rightMargin
                    )
                val childConsumedInset = (childRange * childLayoutParams.offset).toInt()
                contentInsets = if (isLeftDrawerView(child)) {
                    contentInsets.replaceSystemBarsInsets(
                        (contentInsets.systemBarsInsets.left - childConsumedInset).coerceAtLeast(0),
                        contentInsets.systemBarsInsets.top,
                        contentInsets.systemBarsInsets.right,
                        contentInsets.systemBarsInsets.bottom
                    )
                } else {
                    contentInsets.replaceSystemBarsInsets(
                        contentInsets.systemBarsInsets.left,
                        contentInsets.systemBarsInsets.top,
                        (contentInsets.systemBarsInsets.right - childConsumedInset)
                            .coerceAtLeast(0),
                        contentInsets.systemBarsInsets.bottom
                    )
                }
            }
        }
        for (child in children) {
            if (isContentView(child)) {
                ViewCompat.dispatchApplyWindowInsets(child, contentInsets)
            }
        }
    }

    override fun computeScroll() {
        val leftSettling = leftDragger.continueSettling(true)
        val rightSettling = rightDragger.continueSettling(true)
        if (leftSettling || rightSettling) {
            postInvalidateOnAnimation()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val widthMode = MeasureSpec.getMode(widthMeasureSpec)
        var widthSize = MeasureSpec.getSize(widthMeasureSpec)
        val heightMode = MeasureSpec.getMode(heightMeasureSpec)
        var heightSize = MeasureSpec.getSize(heightMeasureSpec)
        if (widthMode != MeasureSpec.EXACTLY || heightMode != MeasureSpec.EXACTLY) {
            if (isInEditMode) {
                if (widthMode == MeasureSpec.UNSPECIFIED) {
                    widthSize = 300
                }
                if (heightMode == MeasureSpec.UNSPECIFIED) {
                    heightSize = 300
                }
            } else {
                throw IllegalArgumentException(
                    "DrawerLayout must be measured with MeasureSpec.EXACTLY"
                )
            }
        }
        setMeasuredDimension(widthSize, heightSize)
        var hasLeftDrawer = false
        var hasRightDrawer = false
        for (child in children) {
            if (child.visibility == View.GONE) {
                continue
            }
            val isDrawer = isDrawerView(child)
            if (isDrawer || isFillView(child)) {
                if (isDrawer) {
                    val isLeftDrawer = isLeftDrawerView(child)
                    check(!((isLeftDrawer && hasLeftDrawer) || (!isLeftDrawer && hasRightDrawer))) {
                        ("Child $child is a second ${if (isLeftDrawer) "left" else "right"} drawer")
                    }
                    if (isLeftDrawer) {
                        hasLeftDrawer = true
                    } else {
                        hasRightDrawer = true
                    }
                }
                val childLayoutParams = child.layoutParams as LayoutParams
                val childWidthSpec = getChildMeasureSpec(
                    widthMeasureSpec,
                    childLayoutParams.leftMargin + childLayoutParams.rightMargin,
                    childLayoutParams.width
                )
                val childHeightSpec = getChildMeasureSpec(
                    heightMeasureSpec,
                    childLayoutParams.topMargin + childLayoutParams.bottomMargin,
                    childLayoutParams.height
                )
                child.measure(childWidthSpec, childHeightSpec)
            } else {
                check(isContentView(child)) {
                    (
                        "Child $child does not have a valid layout_gravity - must be" +
                            " Gravity.LEFT, Gravity.RIGHT, Gravity.NO_GRAVITY or Gravity.FILL"
                        )
                }
            }
        }
        updateContentViewsWindowInsets()
        measureContentViews()
    }

    internal fun measureContentViews() {
        var contentWidth = measuredWidth
        val contentHeight = measuredHeight
        for (child in children) {
            if (child.visibility == View.GONE) {
                continue
            }
            if (isDrawerView(child)) {
                val childLayoutParams = child.layoutParams as LayoutParams
                val childRange = (
                    childLayoutParams.leftMargin + child.measuredWidth +
                        childLayoutParams.rightMargin
                    )
                contentWidth -= (childRange * childLayoutParams.offset).toInt()
            }
        }
        for (child in children) {
            if (child.visibility == View.GONE) {
                continue
            }
            if (isContentView(child)) {
                val childLayoutParams = child.layoutParams as LayoutParams
                val childWidthSpec = MeasureSpec.makeMeasureSpec(
                    contentWidth - childLayoutParams.leftMargin - childLayoutParams.rightMargin,
                    MeasureSpec.EXACTLY
                )
                val contentHeightSpec = MeasureSpec.makeMeasureSpec(
                    contentHeight - childLayoutParams.topMargin - childLayoutParams.bottomMargin,
                    MeasureSpec.EXACTLY
                )
                child.measure(childWidthSpec, contentHeightSpec)
            }
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        for (child in children) {
            if (child.visibility == View.GONE) {
                continue
            }
            if (isDrawerView(child)) {
                val childWidth = child.measuredWidth
                val childHeight = child.measuredHeight
                val childLayoutParams = child.layoutParams as LayoutParams
                val childLeft = computeDrawerViewLeft(child)
                val childVerticalGravity = (
                    childLayoutParams.gravity
                        and Gravity.VERTICAL_GRAVITY_MASK
                    )
                val height = bottom - top
                when (childVerticalGravity) {
                    Gravity.TOP -> child.layout(
                        childLeft,
                        childLayoutParams.topMargin,
                        childLeft + childWidth,
                        childLayoutParams.topMargin + childHeight
                    )

                    Gravity.BOTTOM -> {
                        val childBottom = height - childLayoutParams.bottomMargin
                        child.layout(
                            childLeft,
                            childBottom - childHeight,
                            childLeft + childWidth,
                            childBottom
                        )
                    }

                    Gravity.CENTER_VERTICAL -> {
                        val childTop = (
                            (height - childHeight) / 2 + childLayoutParams.topMargin -
                                childLayoutParams.bottomMargin
                            )
                        child.layout(
                            childLeft,
                            childTop,
                            childLeft + childWidth,
                            childTop + childHeight
                        )
                    }

                    else -> child.layout(
                        childLeft,
                        childLayoutParams.topMargin,
                        childLeft + childWidth,
                        childLayoutParams.topMargin + childHeight
                    )
                }
                child.isInvisible = childLayoutParams.offset <= 0
            } else if (isFillView(child)) {
                val childLayoutParams = child.layoutParams as LayoutParams
                child.layout(
                    childLayoutParams.leftMargin,
                    childLayoutParams.topMargin,
                    childLayoutParams.leftMargin + child.measuredWidth,
                    childLayoutParams.topMargin + child.measuredHeight
                )
            }
        }
        layoutContentViews()
    }

    private fun computeDrawerViewLeft(drawerView: View): Int {
        val childLayoutParams = drawerView.layoutParams as LayoutParams
        val childRange = (
            childLayoutParams.leftMargin + drawerView.measuredWidth +
                childLayoutParams.rightMargin
            )
        return if (isLeftDrawerView(drawerView)) {
            (
                -childRange + (childRange * childLayoutParams.offset).toInt() +
                    childLayoutParams.leftMargin
                )
        } else {
            (
                measuredWidth - (childRange * childLayoutParams.offset).toInt() +
                    childLayoutParams.leftMargin
                )
        }
    }

    internal fun layoutContentViews() {
        var contentLeft = 0
        for (child in children) {
            if (child.visibility == View.GONE) {
                continue
            }
            if (isDrawerView(child)) {
                if (isLeftDrawerView(child)) {
                    val childLayoutParams = child.layoutParams as LayoutParams
                    contentLeft = child.right + childLayoutParams.rightMargin
                }
            }
        }
        for (child in children) {
            if (child.visibility == View.GONE) {
                continue
            }
            if (isContentView(child)) {
                val childLayoutParams = child.layoutParams as LayoutParams
                val childLeft = contentLeft + childLayoutParams.leftMargin
                child.layout(
                    childLeft,
                    childLayoutParams.topMargin,
                    childLeft + child.measuredWidth,
                    childLayoutParams.topMargin + child.measuredHeight
                )
            }
        }
    }

    override fun generateLayoutParams(attrs: AttributeSet?): ViewGroup.LayoutParams =
        LayoutParams(context, attrs)

    override fun generateLayoutParams(
        layoutParams: ViewGroup.LayoutParams
    ): ViewGroup.LayoutParams = when (layoutParams) {
        is LayoutParams -> LayoutParams(layoutParams)
        is MarginLayoutParams -> LayoutParams(layoutParams)
        else -> LayoutParams(layoutParams)
    }

    override fun generateDefaultLayoutParams(): ViewGroup.LayoutParams =
        LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)

    override fun checkLayoutParams(layoutParams: ViewGroup.LayoutParams): Boolean =
        layoutParams is LayoutParams && super.checkLayoutParams(layoutParams)

    fun isDrawerOpen(drawerView: View): Boolean {
        require(isDrawerView(drawerView)) { "View $drawerView is not a drawer" }
        val childLayoutParams = drawerView.layoutParams as LayoutParams
        return childLayoutParams.isOpen
    }

    fun isDrawerOpen(gravity: Int): Boolean {
        val drawerView = findDrawerView(gravity)
            ?: throw IllegalArgumentException("No drawer view found with gravity $gravity")
        return isDrawerOpen(drawerView)
    }

    fun openDrawer(drawerView: View, animate: Boolean = true) {
        require(isDrawerView(drawerView)) { "View $drawerView is not a drawer" }
        val childLayoutParams = drawerView.layoutParams as LayoutParams
        if (childLayoutParams.isOpen && childLayoutParams.offset == 1f) {
            return
        }
        childLayoutParams.isOpen = true
        if (!isLaidOut) {
            childLayoutParams.offset = 1f
        } else if (animate) {
            if (isLeftDrawerView(drawerView)) {
                leftDragger.smoothSlideViewTo(drawerView, 0, drawerView.top)
            } else {
                rightDragger.smoothSlideViewTo(
                    drawerView,
                    width - drawerView.width - childLayoutParams.rightMargin,
                    drawerView.top
                )
            }
        } else {
            moveDrawerToOffset(drawerView, 1f)
            updateContentViewsWindowInsets()
            measureContentViews()
            layoutContentViews()
        }
        invalidate()
    }

    fun openDrawer(gravity: Int, animate: Boolean = true) {
        val drawerView = findDrawerView(gravity)
            ?: throw IllegalArgumentException("No drawer view found with gravity $gravity")
        openDrawer(drawerView, animate)
    }

    fun closeDrawer(drawerView: View, animate: Boolean = true) {
        require(isDrawerView(drawerView)) { "View $drawerView is not a drawer" }
        val childLayoutParams = drawerView.layoutParams as LayoutParams
        if (!childLayoutParams.isOpen && childLayoutParams.offset == 0f) {
            return
        }
        childLayoutParams.isOpen = false
        if (!isLaidOut) {
            childLayoutParams.offset = 0f
        } else if (animate) {
            if (isLeftDrawerView(drawerView)) {
                leftDragger.smoothSlideViewTo(
                    drawerView,
                    -drawerView.width - childLayoutParams.rightMargin,
                    drawerView.top
                )
            } else {
                rightDragger.smoothSlideViewTo(drawerView, width, drawerView.top)
            }
        } else {
            childLayoutParams.offset = 0f
            moveDrawerToOffset(drawerView, 0f)
            updateContentViewsWindowInsets()
            measureContentViews()
            layoutContentViews()
        }
        invalidate()
    }

    fun closeDrawer(gravity: Int, animate: Boolean = true) {
        val drawerView = findDrawerView(gravity)
            ?: throw IllegalArgumentException("No drawer view found with gravity $gravity")
        closeDrawer(drawerView, animate)
    }

    private fun moveDrawerToOffset(drawerView: View, offset: Float) {
        val childLayoutParams = drawerView.layoutParams as LayoutParams
        if (childLayoutParams.offset == offset) {
            return
        }
        childLayoutParams.offset = offset
        val oldChildLeft = drawerView.left
        val newChildLeft = computeDrawerViewLeft(drawerView)
        drawerView.offsetLeftAndRight(newChildLeft - oldChildLeft)
        drawerView.isInvisible = offset <= 0
    }

    fun toggleDrawer(drawerView: View) {
        if (isDrawerOpen(drawerView)) {
            closeDrawer(drawerView)
        } else {
            openDrawer(drawerView)
        }
    }

    fun toggleDrawer(gravity: Int) {
        val drawerView = findDrawerView(gravity)
            ?: throw IllegalArgumentException("No drawer view found with gravity $gravity")
        toggleDrawer(drawerView)
    }

    class LayoutParams : MarginLayoutParams {
        var gravity = Gravity.NO_GRAVITY
        var offset = 0f
        var isOpen = false

        constructor(context: Context, attrs: AttributeSet?) : super(context, attrs) {
            gravity = context.obtainStyledAttributes(attrs, ATTRS)
                .use { it.getInt(0, Gravity.NO_GRAVITY) }
        }

        constructor(width: Int, height: Int) : super(width, height)

        constructor(width: Int, height: Int, gravity: Int) : this(width, height) {
            this.gravity = gravity
        }

        constructor(source: LayoutParams) : super(source) {
            gravity = source.gravity
        }

        constructor(source: MarginLayoutParams) : super(source)

        constructor(source: ViewGroup.LayoutParams) : super(source)

        companion object {
            private val ATTRS = intArrayOf(android.R.attr.layout_gravity)
        }
    }
}
