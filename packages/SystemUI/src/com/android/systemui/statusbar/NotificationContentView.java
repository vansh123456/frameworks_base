/*
 * Copyright (C) 2014 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.systemui.statusbar;

import android.app.Notification;
import android.app.RemoteInput;
import android.content.Context;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.service.notification.StatusBarNotification;
import android.util.AttributeSet;
import android.view.NotificationHeaderView;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.ViewTreeObserver;
import android.view.animation.Interpolator;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;

import com.android.systemui.R;
import com.android.systemui.statusbar.notification.HybridNotificationView;
import com.android.systemui.statusbar.notification.HybridNotificationViewManager;
import com.android.systemui.statusbar.phone.NotificationGroupManager;
import com.android.systemui.statusbar.policy.RemoteInputView;

/**
 * A frame layout containing the actual payload of the notification, including the contracted,
 * expanded and heads up layout. This class is responsible for clipping the content and and
 * switching between the expanded, contracted and the heads up view depending on its clipped size.
 */
public class NotificationContentView extends FrameLayout {

    private static final long ANIMATION_DURATION_LENGTH = 170;
    private static final int VISIBLE_TYPE_CONTRACTED = 0;
    private static final int VISIBLE_TYPE_EXPANDED = 1;
    private static final int VISIBLE_TYPE_HEADSUP = 2;
    private static final int VISIBLE_TYPE_SINGLELINE = 3;

    private final Rect mClipBounds = new Rect();
    private final int mRoundRectRadius;
    private final Interpolator mLinearInterpolator = new LinearInterpolator();
    private final boolean mRoundRectClippingEnabled;

    private View mContractedChild;
    private View mExpandedChild;
    private View mHeadsUpChild;
    private HybridNotificationView mSingleLineView;

    private NotificationViewWrapper mContractedWrapper;
    private NotificationViewWrapper mExpandedWrapper;
    private NotificationViewWrapper mHeadsUpWrapper;
    private HybridNotificationViewManager mHybridViewManager;
    private int mClipTopAmount;
    private int mContentHeight;
    private int mUnrestrictedContentHeight;
    private int mVisibleType = VISIBLE_TYPE_CONTRACTED;
    private boolean mDark;
    private final Paint mFadePaint = new Paint();
    private boolean mAnimate;
    private boolean mIsHeadsUp;
    private boolean mShowingLegacyBackground;
    private boolean mIsChildInGroup;
    private int mSmallHeight;
    private int mHeadsUpHeight;
    private int mNotificationMaxHeight;
    private StatusBarNotification mStatusBarNotification;
    private NotificationGroupManager mGroupManager;
    private RemoteInputController mRemoteInputController;

    private final ViewTreeObserver.OnPreDrawListener mEnableAnimationPredrawListener
            = new ViewTreeObserver.OnPreDrawListener() {
        @Override
        public boolean onPreDraw() {
            mAnimate = true;
            getViewTreeObserver().removeOnPreDrawListener(this);
            return true;
        }
    };

    private final ViewOutlineProvider mOutlineProvider = new ViewOutlineProvider() {
        @Override
        public void getOutline(View view, Outline outline) {
            outline.setRoundRect(0, 0, view.getWidth(), mUnrestrictedContentHeight,
                    mRoundRectRadius);
        }
    };
    private OnClickListener mExpandClickListener;

    public NotificationContentView(Context context, AttributeSet attrs) {
        super(context, attrs);
        mHybridViewManager = new HybridNotificationViewManager(getContext(), this);
        mFadePaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.ADD));
        mRoundRectRadius = getResources().getDimensionPixelSize(
                R.dimen.notification_material_rounded_rect_radius);
        mRoundRectClippingEnabled = getResources().getBoolean(
                R.bool.config_notifications_round_rect_clipping);
        reset(true);
        setOutlineProvider(mOutlineProvider);
    }

    public void setHeights(int smallHeight, int headsUpMaxHeight, int maxHeight) {
        mSmallHeight = smallHeight;
        mHeadsUpHeight = headsUpMaxHeight;
        mNotificationMaxHeight = maxHeight;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int heightMode = MeasureSpec.getMode(heightMeasureSpec);
        boolean hasFixedHeight = heightMode == MeasureSpec.EXACTLY;
        boolean isHeightLimited = heightMode == MeasureSpec.AT_MOST;
        int maxSize = Integer.MAX_VALUE;
        if (hasFixedHeight || isHeightLimited) {
            maxSize = MeasureSpec.getSize(heightMeasureSpec);
        }
        int maxChildHeight = 0;
        if (mContractedChild != null) {
            int size = Math.min(maxSize, mSmallHeight);
            mContractedChild.measure(widthMeasureSpec,
                    MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY));
            maxChildHeight = Math.max(maxChildHeight, mContractedChild.getMeasuredHeight());
        }
        if (mExpandedChild != null) {
            int size = Math.min(maxSize, mNotificationMaxHeight);
            ViewGroup.LayoutParams layoutParams = mExpandedChild.getLayoutParams();
            if (layoutParams.height >= 0) {
                // An actual height is set
                size = Math.min(maxSize, layoutParams.height);
            }
            int spec = size == Integer.MAX_VALUE
                    ? MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
                    : MeasureSpec.makeMeasureSpec(size, MeasureSpec.AT_MOST);
            mExpandedChild.measure(widthMeasureSpec, spec);
            maxChildHeight = Math.max(maxChildHeight, mExpandedChild.getMeasuredHeight());
        }
        if (mHeadsUpChild != null) {
            int size = Math.min(maxSize, mHeadsUpHeight);
            ViewGroup.LayoutParams layoutParams = mHeadsUpChild.getLayoutParams();
            if (layoutParams.height >= 0) {
                // An actual height is set
                size = Math.min(size, layoutParams.height);
            }
            mHeadsUpChild.measure(widthMeasureSpec,
                    MeasureSpec.makeMeasureSpec(size, MeasureSpec.AT_MOST));
            maxChildHeight = Math.max(maxChildHeight, mHeadsUpChild.getMeasuredHeight());
        }
        if (mSingleLineView != null) {
            mSingleLineView.measure(widthMeasureSpec,
                    MeasureSpec.makeMeasureSpec(maxSize, MeasureSpec.AT_MOST));
            maxChildHeight = Math.max(maxChildHeight, mSingleLineView.getMeasuredHeight());
        }
        int ownHeight = Math.min(maxChildHeight, maxSize);
        int width = MeasureSpec.getSize(widthMeasureSpec);
        setMeasuredDimension(width, ownHeight);
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        updateClipping();
        invalidateOutline();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        updateVisibility();
    }

    public void reset(boolean resetActualHeight) {
        if (mContractedChild != null) {
            mContractedChild.animate().cancel();
        }
        if (mExpandedChild != null) {
            mExpandedChild.animate().cancel();
        }
        if (mHeadsUpChild != null) {
            mHeadsUpChild.animate().cancel();
        }
        removeAllViews();
        mContractedChild = null;
        mExpandedChild = null;
        mHeadsUpChild = null;
        mVisibleType = VISIBLE_TYPE_CONTRACTED;
        if (resetActualHeight) {
            mContentHeight = mSmallHeight;
        }
    }

    public View getContractedChild() {
        return mContractedChild;
    }

    public View getExpandedChild() {
        return mExpandedChild;
    }

    public View getHeadsUpChild() {
        return mHeadsUpChild;
    }

    public void setContractedChild(View child) {
        if (mContractedChild != null) {
            mContractedChild.animate().cancel();
            removeView(mContractedChild);
        }
        addView(child);
        mContractedChild = child;
        mContractedWrapper = NotificationViewWrapper.wrap(getContext(), child);
        selectLayout(false /* animate */, true /* force */);
        mContractedWrapper.setDark(mDark, false /* animate */, 0 /* delay */);
        updateRoundRectClipping();
    }

    public void setExpandedChild(View child) {
        if (mExpandedChild != null) {
            mExpandedChild.animate().cancel();
            removeView(mExpandedChild);
        }
        addView(child);
        mExpandedChild = child;
        mExpandedWrapper = NotificationViewWrapper.wrap(getContext(), child);
        selectLayout(false /* animate */, true /* force */);
        updateRoundRectClipping();
    }

    public void setHeadsUpChild(View child) {
        if (mHeadsUpChild != null) {
            mHeadsUpChild.animate().cancel();
            removeView(mHeadsUpChild);
        }
        addView(child);
        mHeadsUpChild = child;
        mHeadsUpWrapper = NotificationViewWrapper.wrap(getContext(), child);
        selectLayout(false /* animate */, true /* force */);
        updateRoundRectClipping();
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        updateVisibility();
    }

    private void updateVisibility() {
        setVisible(isShown());
    }

    private void setVisible(final boolean isVisible) {
        if (isVisible) {

            // We only animate if we are drawn at least once, otherwise the view might animate when
            // it's shown the first time
            getViewTreeObserver().addOnPreDrawListener(mEnableAnimationPredrawListener);
        } else {
            getViewTreeObserver().removeOnPreDrawListener(mEnableAnimationPredrawListener);
            mAnimate = false;
        }
    }

    public void setContentHeight(int contentHeight) {
        mContentHeight = Math.max(Math.min(contentHeight, getHeight()), getMinHeight());;
        mUnrestrictedContentHeight = Math.max(contentHeight, getMinHeight());
        selectLayout(mAnimate /* animate */, false /* force */);
        updateClipping();
        invalidateOutline();
    }

    public int getContentHeight() {
        return mContentHeight;
    }

    public int getMaxHeight() {
        if (mExpandedChild != null) {
            return mExpandedChild.getHeight();
        } else if (mIsHeadsUp && mHeadsUpChild != null) {
            return mHeadsUpChild.getHeight();
        }
        return mSmallHeight;
    }

    public int getMinHeight() {
        if (mIsChildInGroup && !isGroupExpanded()) {
            return mSingleLineView.getHeight();
        } else {
            return mSmallHeight;
        }
    }

    private boolean isGroupExpanded() {
        return mGroupManager.isGroupExpanded(mStatusBarNotification);
    }

    public void setClipTopAmount(int clipTopAmount) {
        mClipTopAmount = clipTopAmount;
        updateClipping();
    }

    private void updateRoundRectClipping() {
        boolean enabled = needsRoundRectClipping();
        setClipToOutline(enabled);
    }

    private boolean needsRoundRectClipping() {
        if (!mRoundRectClippingEnabled) {
            return false;
        }
        boolean needsForContracted = mContractedChild != null
                && mContractedChild.getVisibility() == View.VISIBLE
                && mContractedWrapper.needsRoundRectClipping();
        boolean needsForExpanded = mExpandedChild != null
                && mExpandedChild.getVisibility() == View.VISIBLE
                && mExpandedWrapper.needsRoundRectClipping();
        boolean needsForHeadsUp = mExpandedChild != null
                && mExpandedChild.getVisibility() == View.VISIBLE
                && mExpandedWrapper.needsRoundRectClipping();
        return needsForContracted || needsForExpanded || needsForHeadsUp;
    }

    private void updateClipping() {
        mClipBounds.set(0, mClipTopAmount, getWidth(), mContentHeight);
        setClipBounds(mClipBounds);
    }

    private void selectLayout(boolean animate, boolean force) {
        if (mContractedChild == null) {
            return;
        }
        int visibleType = calculateVisibleType();
        if (visibleType != mVisibleType || force) {
            if (animate && ((visibleType == VISIBLE_TYPE_EXPANDED && mExpandedChild != null)
                    || (visibleType == VISIBLE_TYPE_HEADSUP && mHeadsUpChild != null)
                    || (visibleType == VISIBLE_TYPE_SINGLELINE && mSingleLineView != null)
                    || visibleType == VISIBLE_TYPE_CONTRACTED)) {
                runSwitchAnimation(visibleType);
            } else {
                updateViewVisibilities(visibleType);
            }
            mVisibleType = visibleType;
        }
    }

    private void updateViewVisibilities(int visibleType) {
        boolean contractedVisible = visibleType == VISIBLE_TYPE_CONTRACTED;
        mContractedChild.setVisibility(contractedVisible ? View.VISIBLE : View.INVISIBLE);
        mContractedChild.setAlpha(contractedVisible ? 1f : 0f);
        mContractedChild.setLayerType(LAYER_TYPE_NONE, null);
        if (mExpandedChild != null) {
            boolean expandedVisible = visibleType == VISIBLE_TYPE_EXPANDED;
            mExpandedChild.setVisibility(expandedVisible ? View.VISIBLE : View.INVISIBLE);
            mExpandedChild.setAlpha(expandedVisible ? 1f : 0f);
            mExpandedChild.setLayerType(LAYER_TYPE_NONE, null);
        }
        if (mHeadsUpChild != null) {
            boolean headsUpVisible = visibleType == VISIBLE_TYPE_HEADSUP;
            mHeadsUpChild.setVisibility(headsUpVisible ? View.VISIBLE : View.INVISIBLE);
            mHeadsUpChild.setAlpha(headsUpVisible ? 1f : 0f);
            mHeadsUpChild.setLayerType(LAYER_TYPE_NONE, null);
        }
        if (mSingleLineView != null) {
            boolean singleLineVisible = visibleType == VISIBLE_TYPE_SINGLELINE;
            mSingleLineView.setVisibility(singleLineVisible ? View.VISIBLE : View.INVISIBLE);
            mSingleLineView.setAlpha(singleLineVisible ? 1f : 0f);
            mSingleLineView.setLayerType(LAYER_TYPE_NONE, null);
        }
        setLayerType(LAYER_TYPE_NONE, null);
        updateRoundRectClipping();
    }

    private void runSwitchAnimation(int visibleType) {
        View shownView = getViewForVisibleType(visibleType);
        View hiddenView = getViewForVisibleType(mVisibleType);
        shownView.setVisibility(View.VISIBLE);
        hiddenView.setVisibility(View.VISIBLE);
        shownView.setLayerType(LAYER_TYPE_HARDWARE, mFadePaint);
        hiddenView.setLayerType(LAYER_TYPE_HARDWARE, mFadePaint);
        setLayerType(LAYER_TYPE_HARDWARE, null);
        hiddenView.animate()
                .alpha(0f)
                .setDuration(ANIMATION_DURATION_LENGTH)
                .setInterpolator(mLinearInterpolator)
                .withEndAction(null); // In case we have multiple changes in one frame.
        shownView.animate()
                .alpha(1f)
                .setDuration(ANIMATION_DURATION_LENGTH)
                .setInterpolator(mLinearInterpolator)
                .withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        updateViewVisibilities(mVisibleType);
                    }
                });
        updateRoundRectClipping();
    }

    /**
     * @param visibleType one of the static enum types in this view
     * @return the corresponding view according to the given visible type
     */
    private View getViewForVisibleType(int visibleType) {
        switch (visibleType) {
            case VISIBLE_TYPE_EXPANDED:
                return mExpandedChild;
            case VISIBLE_TYPE_HEADSUP:
                return mHeadsUpChild;
            case VISIBLE_TYPE_SINGLELINE:
                return mSingleLineView;
            default:
                return mContractedChild;
        }
    }

    /**
     * @return one of the static enum types in this view, calculated form the current state
     */
    private int calculateVisibleType() {
        boolean noExpandedChild = mExpandedChild == null;

        if (!noExpandedChild && mContentHeight == mExpandedChild.getHeight()) {
            return VISIBLE_TYPE_EXPANDED;
        }
        if (mIsChildInGroup && !isGroupExpanded()) {
            return VISIBLE_TYPE_SINGLELINE;
        }

        if (mIsHeadsUp && mHeadsUpChild != null) {
            if (mContentHeight <= mHeadsUpChild.getHeight() || noExpandedChild) {
                return VISIBLE_TYPE_HEADSUP;
            } else {
                return VISIBLE_TYPE_EXPANDED;
            }
        } else {
            if (mContentHeight <= mSmallHeight || noExpandedChild) {
                return VISIBLE_TYPE_CONTRACTED;
            } else {
                return VISIBLE_TYPE_EXPANDED;
            }
        }
    }

    public boolean isContentExpandable() {
        return mExpandedChild != null;
    }

    public void setDark(boolean dark, boolean fade, long delay) {
        if (mDark == dark || mContractedChild == null) return;
        mDark = dark;
        mContractedWrapper.setDark(dark && !mShowingLegacyBackground, fade, delay);
        if (mSingleLineView != null) {
            mSingleLineView.setDark(dark, fade, delay);
        }
    }

    public void setHeadsUp(boolean headsUp) {
        mIsHeadsUp = headsUp;
        selectLayout(false /* animate */, true /* force */);
    }

    @Override
    public boolean hasOverlappingRendering() {

        // This is not really true, but good enough when fading from the contracted to the expanded
        // layout, and saves us some layers.
        return false;
    }

    public void setShowingLegacyBackground(boolean showing) {
        mShowingLegacyBackground = showing;
    }

    public void setIsChildInGroup(boolean isChildInGroup) {
        mIsChildInGroup = isChildInGroup;
        updateSingleLineView();
    }

    public void onNotificationUpdated(NotificationData.Entry entry) {
        mStatusBarNotification = entry.notification;
        updateSingleLineView();
        applyRemoteInput(entry);
        selectLayout(false /* animate */, true /* force */);
        if (mContractedChild != null) {
            mContractedWrapper.notifyContentUpdated();
            mContractedWrapper.setDark(mDark, false /* animate */, 0 /* delay */);
        }
        if (mExpandedChild != null) {
            mExpandedWrapper.notifyContentUpdated();
        }
        if (mHeadsUpChild != null) {
            mHeadsUpWrapper.notifyContentUpdated();
        }
        updateRoundRectClipping();
    }

    private void updateSingleLineView() {
        if (mIsChildInGroup) {
            mSingleLineView = mHybridViewManager.bindFromNotification(
                    mSingleLineView, mStatusBarNotification.getNotification());
        }
    }

    private void applyRemoteInput(final NotificationData.Entry entry) {
        if (mRemoteInputController == null) {
            return;
        }

        boolean hasRemoteInput = false;

        Notification.Action[] actions = entry.notification.getNotification().actions;
        if (actions != null) {
            for (Notification.Action a : actions) {
                if (a.getRemoteInputs() != null) {
                    for (RemoteInput ri : a.getRemoteInputs()) {
                        if (ri.getAllowFreeFormInput()) {
                            hasRemoteInput = true;
                            break;
                        }
                    }
                }
            }
        }

        View bigContentView = mExpandedChild;
        if (bigContentView != null) {
            applyRemoteInput(bigContentView, entry, hasRemoteInput);
        }
        View headsUpContentView = mHeadsUpChild;
        if (headsUpContentView != null) {
            applyRemoteInput(headsUpContentView, entry, hasRemoteInput);
        }
    }

    private void applyRemoteInput(View view, NotificationData.Entry entry, boolean hasRemoteInput) {
        View actionContainerCandidate = view.findViewById(
                com.android.internal.R.id.actions_container);
        if (actionContainerCandidate instanceof FrameLayout) {
            RemoteInputView existing = (RemoteInputView)
                    view.findViewWithTag(RemoteInputView.VIEW_TAG);

            if (existing != null) {
                existing.onNotificationUpdate();
            }

            if (existing == null && hasRemoteInput) {
                ViewGroup actionContainer = (FrameLayout) actionContainerCandidate;
                RemoteInputView riv = RemoteInputView.inflate(
                        mContext, actionContainer, entry, mRemoteInputController);

                riv.setVisibility(View.INVISIBLE);
                actionContainer.addView(riv, new LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT)
                );
                int color = entry.notification.getNotification().color;
                if (color == Notification.COLOR_DEFAULT) {
                    color = mContext.getColor(R.color.default_remote_input_background);
                }
                riv.setBackgroundColor(color);
            }
        }
    }

    public void setGroupManager(NotificationGroupManager groupManager) {
        mGroupManager = groupManager;
    }

    public void setRemoteInputController(RemoteInputController r) {
        mRemoteInputController = r;
    }

    public void setExpandClickListener(OnClickListener expandClickListener) {
        mExpandClickListener = expandClickListener;
    }

    public void updateExpandButtons(boolean expandable) {
        if (mExpandedChild != null) {
            mExpandedWrapper.updateExpandability(expandable, mExpandClickListener);
        }
        if (mContractedChild != null) {
            mContractedWrapper.updateExpandability(expandable, mExpandClickListener);
        }
        if (mHeadsUpChild != null) {
            mHeadsUpWrapper.updateExpandability(expandable,  mExpandClickListener);
        }
    }

    public NotificationHeaderView getNotificationHeader() {
        NotificationHeaderView header = null;
        if (mContractedChild != null) {
            header = mContractedWrapper.getNotificationHeader();
        }
        if (header == null && mExpandedChild != null) {
            header = mExpandedWrapper.getNotificationHeader();
        }
        if (header == null && mHeadsUpChild != null) {
            header = mHeadsUpWrapper.getNotificationHeader();
        }
        return header;
    }
}
