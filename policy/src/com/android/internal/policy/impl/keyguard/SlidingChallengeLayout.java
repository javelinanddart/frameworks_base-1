/*
 * Copyright (C) 2012 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.internal.policy.impl.keyguard;

import android.animation.ObjectAnimator;
import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.util.FloatProperty;
import android.util.Log;
import android.util.Property;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.animation.Interpolator;
import android.widget.Scroller;

import com.android.internal.R;

/**
 * This layout handles interaction with the sliding security challenge views
 * that overlay/resize other keyguard contents.
 */
public class SlidingChallengeLayout extends ViewGroup implements ChallengeLayout {
    private static final String TAG = "SlidingChallengeLayout";
    private static final boolean DEBUG = false;

    // Drawn to show the drag handle in closed state; crossfades to the challenge view
    // when challenge is fully visible
    private Drawable mHandleDrawable;
    private Drawable mFrameDrawable;
    private Drawable mDragIconDrawable;

    // Initialized during measurement from child layoutparams
    private View mChallengeView;
    private View mScrimView;

    // Range: 0 (fully hidden) to 1 (fully visible)
    private float mChallengeOffset = 1.f;
    private boolean mChallengeShowing = true;
    private boolean mIsBouncing = false;

    private final Scroller mScroller;
    private int mScrollState;
    private OnChallengeScrolledListener mScrollListener;
    private OnBouncerStateChangedListener mBouncerListener;

    public static final int SCROLL_STATE_IDLE = 0;
    public static final int SCROLL_STATE_DRAGGING = 1;
    public static final int SCROLL_STATE_SETTLING = 2;

    private static final int MAX_SETTLE_DURATION = 600; // ms

    // ID of the pointer in charge of a current drag
    private int mActivePointerId = INVALID_POINTER;
    private static final int INVALID_POINTER = -1;

    // True if the user is currently dragging the slider
    private boolean mDragging;
    // True if the user may not drag until a new gesture begins
    private boolean mBlockDrag;

    private VelocityTracker mVelocityTracker;
    private int mMinVelocity;
    private int mMaxVelocity;
    private float mGestureStartY; // where did you touch the screen to start this gesture?
    private int mGestureStartChallengeBottom; // where was the challenge at that time?
    private int mDragHandleSize; // handle hitrect extension into the challenge view
    private int mDragHandleHeadroom; // extend the handle's hitrect this far above the line
    private int mDragHandleEdgeSlop;
    float mHandleAlpha;
    float mFrameAlpha;
    private ObjectAnimator mHandleAnimation;
    private ObjectAnimator mFrameAnimation;

    static final Property<SlidingChallengeLayout, Float> HANDLE_ALPHA =
            new FloatProperty<SlidingChallengeLayout>("handleAlpha") {
        @Override
        public void setValue(SlidingChallengeLayout view, float value) {
            view.mHandleAlpha = value;
            view.invalidate();
        }

        @Override
        public Float get(SlidingChallengeLayout view) {
            return view.mHandleAlpha;
        }
    };

    static final Property<SlidingChallengeLayout, Float> FRAME_ALPHA =
            new FloatProperty<SlidingChallengeLayout>("frameAlpha") {
        @Override
        public void setValue(SlidingChallengeLayout view, float value) {
            if (view.mFrameDrawable != null) {
                view.mFrameAlpha = value;
                view.mFrameDrawable.setAlpha((int) (value * 0xFF));
                view.mFrameDrawable.invalidateSelf();
            }
        }

        @Override
        public Float get(SlidingChallengeLayout view) {
            return view.mFrameAlpha;
        }
    };

    private static final int DRAG_HANDLE_DEFAULT_SIZE = 32; // dp
    private static final int HANDLE_ANIMATE_DURATION = 200; // ms

    // True if at least one layout pass has happened since the view was attached.
    private boolean mHasLayout;

    private static final Interpolator sMotionInterpolator = new Interpolator() {
        public float getInterpolation(float t) {
            t -= 1.0f;
            return t * t * t * t * t + 1.0f;
        }
    };

    private static final Interpolator sHandleFadeInterpolator = new Interpolator() {
        public float getInterpolation(float t) {
            return t * t;
        }
    };

    private final Runnable mEndScrollRunnable = new Runnable () {
        public void run() {
            completeChallengeScroll();
        }
    };

    private final OnClickListener mScrimClickListener = new OnClickListener() {
        @Override
        public void onClick(View v) {
            hideBouncer();
        }
    };

    /**
     * Listener interface that reports changes in scroll state of the challenge area.
     */
    public interface OnChallengeScrolledListener {
        /**
         * The scroll state itself changed.
         *
         * <p>scrollState will be one of the following:</p>
         *
         * <ul>
         * <li><code>SCROLL_STATE_IDLE</code> - The challenge area is stationary.</li>
         * <li><code>SCROLL_STATE_DRAGGING</code> - The user is actively dragging
         * the challenge area.</li>
         * <li><code>SCROLL_STATE_SETTLING</code> - The challenge area is animating
         * into place.</li>
         * </ul>
         *
         * <p>Do not perform expensive operations (e.g. layout)
         * while the scroll state is not <code>SCROLL_STATE_IDLE</code>.</p>
         *
         * @param scrollState The new scroll state of the challenge area.
         */
        public void onScrollStateChanged(int scrollState);

        /**
         * The precise position of the challenge area has changed.
         *
         * <p>NOTE: It is NOT safe to modify layout or call any View methods that may
         * result in a requestLayout anywhere in your view hierarchy as a result of this call.
         * It may be called during drawing.</p>
         *
         * @param scrollPosition New relative position of the challenge area.
         *                       1.f = fully visible/ready to be interacted with.
         *                       0.f = fully invisible/inaccessible to the user.
         * @param challengeTop Position of the top edge of the challenge view in px in the
         *                     SlidingChallengeLayout's coordinate system.
         */
        public void onScrollPositionChanged(float scrollPosition, int challengeTop);
    }

    public SlidingChallengeLayout(Context context) {
        this(context, null);
    }

    public SlidingChallengeLayout(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public SlidingChallengeLayout(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);

        final TypedArray a = context.obtainStyledAttributes(attrs,
                R.styleable.SlidingChallengeLayout, defStyle, 0);
        setDragDrawables(a.getDrawable(R.styleable.SlidingChallengeLayout_dragHandle),
                a.getDrawable(R.styleable.SlidingChallengeLayout_dragIcon));

        a.recycle();

        mScroller = new Scroller(context, sMotionInterpolator);

        final ViewConfiguration vc = ViewConfiguration.get(context);
        mMinVelocity = vc.getScaledMinimumFlingVelocity();
        mMaxVelocity = vc.getScaledMaximumFlingVelocity();

        mDragHandleEdgeSlop = getResources().getDimensionPixelSize(
                R.dimen.kg_edge_swipe_region_size);

        setWillNotDraw(false);
    }

    public void setDragDrawables(Drawable handle, Drawable icon) {
        final float density = getResources().getDisplayMetrics().density;
        final int defaultSize = (int) (DRAG_HANDLE_DEFAULT_SIZE * density + 0.5f);
        mDragHandleSize = Math.max(handle != null ? handle.getIntrinsicHeight() : defaultSize,
                icon != null ? icon.getIntrinsicHeight() : defaultSize);

        // top half of the lock icon, plus another 25% to be sure
        mDragHandleHeadroom = (icon != null) ? (int)(icon.getIntrinsicHeight() * 0.75f) : 0;

        mHandleDrawable = handle;
        mDragIconDrawable = icon;
    }

    public void setDragIconDrawable(Drawable d) {
        mDragIconDrawable = d;
    }

    public void showHandle(boolean visible) {
        if (visible) {
            if (mHandleAnimation != null) {
                mHandleAnimation.cancel();
                mHandleAnimation = null;
            }
            mHandleAlpha = 1.f;
            invalidate();
        } else {
            animateHandle(false);
        }
    }

    void animateHandle(boolean visible) {
        if (mHandleAnimation != null) {
            mHandleAnimation.cancel();
        }
        mHandleAnimation = ObjectAnimator.ofFloat(this, HANDLE_ALPHA, visible ? 1.f : 0.f);
        mHandleAnimation.setInterpolator(sHandleFadeInterpolator);
        mHandleAnimation.setDuration(HANDLE_ANIMATE_DURATION);
        mHandleAnimation.start();
    }

    void animateFrame(boolean visible, boolean full) {
        if (mFrameDrawable == null) return;

        if (mFrameAnimation != null) {
            mFrameAnimation.cancel();
        }
        mFrameAnimation = ObjectAnimator.ofFloat(this, FRAME_ALPHA,
                visible ? (full ? 1.f : 0.5f) : 0.f);
        mFrameAnimation.setInterpolator(sHandleFadeInterpolator);
        mFrameAnimation.setDuration(HANDLE_ANIMATE_DURATION);
        mFrameAnimation.start();
    }

    private void sendInitialListenerUpdates() {
        if (mScrollListener != null) {
            int challengeTop = mChallengeView != null ? mChallengeView.getTop() : 0;
            mScrollListener.onScrollPositionChanged(mChallengeOffset, challengeTop);
            mScrollListener.onScrollStateChanged(mScrollState);
        }
    }

    public void setOnChallengeScrolledListener(OnChallengeScrolledListener listener) {
        mScrollListener = listener;
        if (mHasLayout) {
            sendInitialListenerUpdates();
        }
    }

    public void setOnBouncerStateChangedListener(OnBouncerStateChangedListener listener) {
        mBouncerListener = listener;
    }

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();

        mHasLayout = false;
    }

    @Override
    public void onDetachedFromWindow() {
        super.onDetachedFromWindow();

        removeCallbacks(mEndScrollRunnable);
        mHasLayout = false;
    }

    @Override
    public void requestChildFocus(View child, View focused) {
        if (mIsBouncing && child != mChallengeView) {
            // Clear out of the bouncer if the user tries to move focus outside of
            // the security challenge view.
            hideBouncer();
        }
        super.requestChildFocus(child, focused);
    }

    // We want the duration of the page snap animation to be influenced by the distance that
    // the screen has to travel, however, we don't want this duration to be effected in a
    // purely linear fashion. Instead, we use this method to moderate the effect that the distance
    // of travel has on the overall snap duration.
    float distanceInfluenceForSnapDuration(float f) {
        f -= 0.5f; // center the values about 0.
        f *= 0.3f * Math.PI / 2.0f;
        return (float) Math.sin(f);
    }

    void setScrollState(int state) {
        if (mScrollState != state) {
            mScrollState = state;

            animateHandle(state == SCROLL_STATE_IDLE && !mChallengeShowing);
            animateFrame(state == SCROLL_STATE_DRAGGING, false);
            if (mScrollListener != null) {
                mScrollListener.onScrollStateChanged(state);
            }
        }
    }

    void completeChallengeScroll() {
        setChallengeShowing(mChallengeOffset != 0);
        setScrollState(SCROLL_STATE_IDLE);
    }

    void setScrimView(View scrim) {
        if (mScrimView != null) {
            mScrimView.setOnClickListener(null);
        }
        mScrimView = scrim;
        mScrimView.setVisibility(mIsBouncing ? VISIBLE : GONE);
        mScrimView.setFocusable(true);
        mScrimView.setOnClickListener(mScrimClickListener);
    }

    /**
     * Animate the bottom edge of the challenge view to the given position.
     *
     * @param y desired final position for the bottom edge of the challenge view in px
     * @param velocity velocity in
     */
    void animateChallengeTo(int y, int velocity) {
        if (mChallengeView == null) {
            // Nothing to do.
            return;
        }
        int sy = mChallengeView.getBottom();
        int dy = y - sy;
        if (dy == 0) {
            completeChallengeScroll();
            return;
        }

        setScrollState(SCROLL_STATE_SETTLING);

        final int childHeight = mChallengeView.getHeight();
        final int halfHeight = childHeight / 2;
        final float distanceRatio = Math.min(1f, 1.0f * Math.abs(dy) / childHeight);
        final float distance = halfHeight + halfHeight *
                distanceInfluenceForSnapDuration(distanceRatio);

        int duration = 0;
        velocity = Math.abs(velocity);
        if (velocity > 0) {
            duration = 4 * Math.round(1000 * Math.abs(distance / velocity));
        } else {
            final float childDelta = (float) Math.abs(dy) / childHeight;
            duration = (int) ((childDelta + 1) * 100);
        }
        duration = Math.min(duration, MAX_SETTLE_DURATION);

        mScroller.startScroll(0, sy, 0, dy, duration);
        postInvalidateOnAnimation();
    }

    private void setChallengeShowing(boolean showChallenge) {
        if (mChallengeShowing != showChallenge) {
            mChallengeShowing = showChallenge;
            if (mChallengeView != null) {
                mChallengeView.setVisibility(showChallenge ? VISIBLE : INVISIBLE);
            }
        }
    }

    /**
     * @return true if the challenge is at all visible.
     */
    public boolean isChallengeShowing() {
        return mChallengeShowing;
    }

    @Override
    public boolean isChallengeOverlapping() {
        return mChallengeShowing;
    }

    @Override
    public boolean isBouncing() {
        return mIsBouncing;
    }

    @Override
    public void showBouncer() {
        if (mIsBouncing) return;
        showChallenge(true);
        mIsBouncing = true;
        if (mScrimView != null) {
            mScrimView.setVisibility(VISIBLE);
        }
        animateFrame(true, true);
        if (mBouncerListener != null) {
            mBouncerListener.onBouncerStateChanged(true);
        }
    }

    @Override
    public void hideBouncer() {
        if (!mIsBouncing) return;
        setChallengeShowing(false);
        mIsBouncing = false;
        if (mScrimView != null) {
            mScrimView.setVisibility(GONE);
        }
        animateFrame(false, false);
        if (mBouncerListener != null) {
            mBouncerListener.onBouncerStateChanged(false);
        }
    }

    @Override
    public void requestDisallowInterceptTouchEvent(boolean allowIntercept) {
        // We'll intercept whoever we feel like! ...as long as it isn't a challenge view.
        // If there are one or more pointers in the challenge view before we take over
        // touch events, onInterceptTouchEvent will set mBlockDrag.
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        if (mVelocityTracker == null) {
            mVelocityTracker = VelocityTracker.obtain();
        }
        mVelocityTracker.addMovement(ev);

        final int action = ev.getActionMasked();
        switch (action) {
            case MotionEvent.ACTION_DOWN:
                mGestureStartY = ev.getY();
                break;

            case MotionEvent.ACTION_CANCEL:
            case MotionEvent.ACTION_UP:
                resetTouch();
                break;

            case MotionEvent.ACTION_MOVE:
                final int count = ev.getPointerCount();
                for (int i = 0; i < count; i++) {
                    final float x = ev.getX(i);
                    final float y = ev.getY(i);

                    if (!mIsBouncing &&
                            (isInDragHandle(x, y) || crossedDragHandle(x, y, mGestureStartY) ||
                            (isInChallengeView(x, y) && mScrollState == SCROLL_STATE_SETTLING)) &&
                            mActivePointerId == INVALID_POINTER) {
                        mActivePointerId = ev.getPointerId(i);
                        mGestureStartY = ev.getY();
                        mGestureStartChallengeBottom = getChallengeBottom();
                        mDragging = true;
                    } else if (isInChallengeView(x, y)) {
                        mBlockDrag = true;
                    }
                }
                break;
        }

        if (mBlockDrag) {
            mActivePointerId = INVALID_POINTER;
            mDragging = false;
        }

        return mDragging;
    }

    private void resetTouch() {
        mVelocityTracker.recycle();
        mVelocityTracker = null;
        mActivePointerId = INVALID_POINTER;
        mDragging = mBlockDrag = false;
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (mVelocityTracker == null) {
            mVelocityTracker = VelocityTracker.obtain();
        }
        mVelocityTracker.addMovement(ev);

        final int action = ev.getActionMasked();
        switch (action) {
            case MotionEvent.ACTION_CANCEL:
                if (mDragging) {
                    showChallenge(0);
                }
                resetTouch();
                break;

            case MotionEvent.ACTION_POINTER_UP:
                if (mActivePointerId != ev.getPointerId(ev.getActionIndex())) {
                    break;
                }
            case MotionEvent.ACTION_UP:
                if (mDragging) {
                    mVelocityTracker.computeCurrentVelocity(1000, mMaxVelocity);
                    showChallenge((int) mVelocityTracker.getYVelocity(mActivePointerId));
                }
                resetTouch();
                break;

            case MotionEvent.ACTION_MOVE:
                if (!mDragging && !mBlockDrag && !mIsBouncing) {
                    final int count = ev.getPointerCount();
                    for (int i = 0; i < count; i++) {
                        final float x = ev.getX(i);
                        final float y = ev.getY(i);

                        if ((isInDragHandle(x, y) || crossedDragHandle(x, y, mGestureStartY) ||
                                (isInChallengeView(x, y) && mScrollState == SCROLL_STATE_SETTLING))
                                && mActivePointerId == INVALID_POINTER) {
                            mGestureStartY = y;
                            mActivePointerId = ev.getPointerId(i);
                            mGestureStartChallengeBottom = getChallengeBottom();
                            mDragging = true;
                            break;
                        }
                    }
                }
                // Not an else; this can be set above.
                if (mDragging) {
                    // No-op if already in this state, but set it here in case we arrived
                    // at this point from either intercept or the above.
                    setScrollState(SCROLL_STATE_DRAGGING);

                    final int index = ev.findPointerIndex(mActivePointerId);
                    if (index < 0) {
                        // Oops, bogus state. We lost some touch events somewhere.
                        // Just drop it with no velocity and let things settle.
                        resetTouch();
                        showChallenge(0);
                        return true;
                    }
                    final float y = ev.getY(index);
                    final float pos = Math.min(y - mGestureStartY,
                            getChallengeOpenedTop());

                    moveChallengeTo(mGestureStartChallengeBottom + (int) pos);
                }
                break;
        }
        return true;
    }

    /**
     * We only want to add additional vertical space to the drag handle when the panel is fully
     * closed.
     */
    private int getDragHandleHeadroom() {
        return isChallengeShowing() ? 0 : mDragHandleHeadroom;
    }

    private boolean isInChallengeView(float x, float y) {
        if (mChallengeView == null) return false;

        return x >= mChallengeView.getLeft() && y >= mChallengeView.getTop() &&
                x < mChallengeView.getRight() && y < mChallengeView.getBottom();
    }

    private boolean isInDragHandle(float x, float y) {
        if (mChallengeView == null) return false;

        return x >= mDragHandleEdgeSlop &&
                y >= mChallengeView.getTop() - getDragHandleHeadroom() &&
                x < getWidth() - mDragHandleEdgeSlop &&
                y < mChallengeView.getTop() + mDragHandleSize;
    }

    private boolean crossedDragHandle(float x, float y, float initialY) {
        final int challengeTop = mChallengeView.getTop();
        return  x >= 0 &&
                x < getWidth() &&
                initialY < (challengeTop - getDragHandleHeadroom()) &&
                y > challengeTop + mDragHandleSize;
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        if (MeasureSpec.getMode(widthSpec) != MeasureSpec.EXACTLY ||
                MeasureSpec.getMode(heightSpec) != MeasureSpec.EXACTLY) {
            throw new IllegalArgumentException(
                    "SlidingChallengeLayout must be measured with an exact size");
        }

        final int width = MeasureSpec.getSize(widthSpec);
        final int height = MeasureSpec.getSize(heightSpec);
        setMeasuredDimension(width, height);

        // Find one and only one challenge view.
        final View oldChallengeView = mChallengeView;
        mChallengeView = null;
        final int count = getChildCount();
        for (int i = 0; i < count; i++) {
            final View child = getChildAt(i);
            final LayoutParams lp = (LayoutParams) child.getLayoutParams();

            if (lp.childType == LayoutParams.CHILD_TYPE_CHALLENGE) {
                if (mChallengeView != null) {
                    throw new IllegalStateException(
                            "There may only be one child with layout_isChallenge=\"true\"");
                }
                mChallengeView = child;
                if (mChallengeView != oldChallengeView) {
                    mChallengeView.setVisibility(mChallengeShowing ? VISIBLE : INVISIBLE);
                }
                // We're going to play silly games with the frame's background drawable later.
                mFrameDrawable = mChallengeView.getBackground();
                mFrameDrawable.setAlpha(0);
            } else if (lp.childType == LayoutParams.CHILD_TYPE_SCRIM) {
                setScrimView(child);
            }

            if (child.getVisibility() == GONE) continue;

            measureChildWithMargins(child, widthSpec, 0, heightSpec, 0);
        }
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        final int paddingLeft = getPaddingLeft();
        final int paddingTop = getPaddingTop();
        final int paddingRight = getPaddingRight();
        final int paddingBottom = getPaddingBottom();
        final int width = r - l;
        final int height = b - t;

        final int count = getChildCount();
        for (int i = 0; i < count; i++) {
            final View child = getChildAt(i);

            if (child.getVisibility() == GONE) continue;

            final LayoutParams lp = (LayoutParams) child.getLayoutParams();

            if (lp.childType == LayoutParams.CHILD_TYPE_CHALLENGE) {
                // Challenge views pin to the bottom, offset by a portion of their height,
                // and center horizontally.
                final int center = (paddingLeft + width - paddingRight) / 2;
                final int childWidth = child.getMeasuredWidth();
                final int childHeight = child.getMeasuredHeight();
                final int left = center - childWidth / 2;
                final int layoutBottom = height - paddingBottom - lp.bottomMargin;
                // We use the top of the challenge view to position the handle, so
                // we never want less than the handle size showing at the bottom.
                final int bottom = layoutBottom + (int) ((childHeight - mDragHandleSize)
                        * (1 - mChallengeOffset));
                float offset = 1.f - (bottom - layoutBottom) / childHeight;
                child.setAlpha(offset);
                child.layout(left, bottom - childHeight, left + childWidth, bottom);
            } else {
                // Non-challenge views lay out from the upper left, layered.
                child.layout(paddingLeft + lp.leftMargin,
                        paddingTop + lp.topMargin,
                        paddingLeft + child.getMeasuredWidth(),
                        paddingTop + child.getMeasuredHeight());
            }
        }

        if (!mHasLayout) {
            // We want to trigger the initial listener updates outside of layout pass,
            // in case the listeners trigger requestLayout().
            post(new Runnable() {
                @Override
                public void run() {
                    sendInitialListenerUpdates();
                }
            });
            mHasLayout = true;
        }
    }

    public void computeScroll() {
        super.computeScroll();

        if (!mScroller.isFinished()) {
            if (mChallengeView == null) {
                // Can't scroll if the view is missing.
                Log.e(TAG, "Challenge view missing in computeScroll");
                mScroller.abortAnimation();
                return;
            }

            mScroller.computeScrollOffset();
            moveChallengeTo(mScroller.getCurrY());

            if (mScroller.isFinished()) {
                post(mEndScrollRunnable);
            }
        }
    }

    @Override
    public void draw(Canvas c) {
        super.draw(c);

        final Paint debugPaint;
        if (DEBUG) {
            debugPaint = new Paint();
            debugPaint.setColor(0x40FF00CC);
            // show the isInDragHandle() rect
            c.drawRect(mDragHandleEdgeSlop,
                    mChallengeView.getTop() - getDragHandleHeadroom(),
                    getWidth() - mDragHandleEdgeSlop,
                    mChallengeView.getTop() + mDragHandleSize,
                    debugPaint);
        }

        if (mChallengeView != null && mHandleAlpha > 0 && mHandleDrawable != null) {
            final int top = mChallengeView.getTop();
            final int handleHeight = mHandleDrawable.getIntrinsicHeight();
            final int challengeLeft = mChallengeView.getLeft();
            final int challengeRight = mChallengeView.getRight();
            mHandleDrawable.setBounds(challengeLeft, top, challengeRight, top + handleHeight);
            mHandleDrawable.setAlpha((int) (mHandleAlpha * 0xFF));
            mHandleDrawable.draw(c);

            if (DEBUG) {
                // now show the actual drag handle
                debugPaint.setStyle(Paint.Style.STROKE);
                debugPaint.setStrokeWidth(1);
                debugPaint.setColor(0xFF80FF00);
                c.drawRect(challengeLeft, top, challengeRight, top + handleHeight, debugPaint);
            }

            if (mDragIconDrawable != null) {
                final int iconWidth = mDragIconDrawable.getIntrinsicWidth();
                final int iconHeight = mDragIconDrawable.getIntrinsicHeight();
                final int iconLeft = (challengeLeft + challengeRight - iconWidth) / 2;
                final int iconTop = top + (handleHeight - iconHeight) / 2;
                mDragIconDrawable.setBounds(iconLeft, iconTop, iconLeft + iconWidth,
                        iconTop + iconHeight);
                mDragIconDrawable.setAlpha((int) (mHandleAlpha * 0xFF));
                mDragIconDrawable.draw(c);

                if (DEBUG) {
                    debugPaint.setColor(0xFF00FF00);
                    c.drawRect(iconLeft, iconTop, iconLeft + iconWidth,
                        iconTop + iconHeight, debugPaint);
                }
            }
        }
    }

    /**
     * Move the bottom edge of mChallengeView to a new position and notify the listener
     * if it represents a change in position. Changes made through this method will
     * be stable across layout passes. If this method is called before first layout of
     * this SlidingChallengeLayout it will have no effect.
     *
     * @param bottom New bottom edge in px in this SlidingChallengeLayout's coordinate system.
     * @return true if the challenge view was moved
     */
    private boolean moveChallengeTo(int bottom) {
        if (mChallengeView == null || !mHasLayout) {
            return false;
        }

        final int layoutBottom = getLayoutBottom();
        final int challengeHeight = mChallengeView.getHeight();

        bottom = Math.max(layoutBottom,
                Math.min(bottom, layoutBottom + challengeHeight - mDragHandleSize));

        float offset = 1.f - (float) (bottom - layoutBottom) / (challengeHeight - mDragHandleSize);
        mChallengeOffset = offset;
        if (offset > 0 && !mChallengeShowing) {
            setChallengeShowing(true);
        }

        mChallengeView.layout(mChallengeView.getLeft(),
                bottom - mChallengeView.getHeight(), mChallengeView.getRight(), bottom);

        mChallengeView.setAlpha(offset);
        if (mScrollListener != null) {
            mScrollListener.onScrollPositionChanged(offset, mChallengeView.getTop());
        }
        postInvalidateOnAnimation();
        return true;
    }

    /**
     * The bottom edge of this SlidingChallengeLayout's coordinate system; will coincide with
     * the bottom edge of mChallengeView when the challenge is fully opened.
     */
    private int getLayoutBottom() {
        final int bottomMargin = (mChallengeView == null)
                ? 0
                : ((LayoutParams) mChallengeView.getLayoutParams()).bottomMargin;
        final int layoutBottom = getHeight() - getPaddingBottom() - bottomMargin;
        return layoutBottom;
    }

    /**
     * The bottom edge of mChallengeView; essentially, where the sliding challenge 'is'.
     */
    private int getChallengeBottom() {
        if (mChallengeView == null) return 0;

        return mChallengeView.getBottom();
    }

    /**
     * The top edge of the challenge if it were fully opened.
     */
    private int getChallengeOpenedTop() {
        return getLayoutBottom() - ((mChallengeView == null) ? 0 : mChallengeView.getHeight());
    }

    private void moveChallengeBy(int delta) {
        moveChallengeTo(getChallengeBottom() + delta);
    }

    /**
     * Show or hide the challenge view, animating it if necessary.
     * @param show true to show, false to hide
     */
    public void showChallenge(boolean show) {
        showChallenge(show, 0);
    }

    private void showChallenge(int velocity) {
        boolean show = false;
        if (Math.abs(velocity) > mMinVelocity) {
            show = velocity < 0;
        } else {
            show = mChallengeOffset >= 0.5f;
        }
        showChallenge(show, velocity);
    }

    private void showChallenge(boolean show, int velocity) {
        if (mChallengeView == null) {
            setChallengeShowing(false);
            return;
        }

        if (mHasLayout) {
            final int bottomMargin = ((LayoutParams) mChallengeView.getLayoutParams()).bottomMargin;
            final int layoutBottom = getHeight() - getPaddingBottom() - bottomMargin;
            animateChallengeTo(show ? layoutBottom :
                layoutBottom + mChallengeView.getHeight() - mDragHandleSize, velocity);
        }
    }

    @Override
    public ViewGroup.LayoutParams generateLayoutParams(AttributeSet attrs) {
        return new LayoutParams(getContext(), attrs);
    }

    @Override
    protected ViewGroup.LayoutParams generateLayoutParams(ViewGroup.LayoutParams p) {
        return p instanceof LayoutParams ? new LayoutParams((LayoutParams) p) :
                p instanceof MarginLayoutParams ? new LayoutParams((MarginLayoutParams) p) :
                new LayoutParams(p);
    }

    @Override
    protected ViewGroup.LayoutParams generateDefaultLayoutParams() {
        return new LayoutParams();
    }

    @Override
    protected boolean checkLayoutParams(ViewGroup.LayoutParams p) {
        return p instanceof LayoutParams;
    }

    public static class LayoutParams extends MarginLayoutParams {
        public int childType = CHILD_TYPE_NONE;
        public static final int CHILD_TYPE_NONE = 0;
        public static final int CHILD_TYPE_CHALLENGE = 2;
        public static final int CHILD_TYPE_SCRIM = 4;

        public LayoutParams() {
            this(MATCH_PARENT, WRAP_CONTENT);
        }

        public LayoutParams(int width, int height) {
            super(width, height);
        }

        public LayoutParams(android.view.ViewGroup.LayoutParams source) {
            super(source);
        }

        public LayoutParams(MarginLayoutParams source) {
            super(source);
        }

        public LayoutParams(LayoutParams source) {
            super(source);

            childType = source.childType;
        }

        public LayoutParams(Context c, AttributeSet attrs) {
            super(c, attrs);

            final TypedArray a = c.obtainStyledAttributes(attrs,
                    R.styleable.SlidingChallengeLayout_Layout);
            childType = a.getInt(R.styleable.SlidingChallengeLayout_Layout_layout_childType,
                    CHILD_TYPE_NONE);
            a.recycle();
        }
    }
}
