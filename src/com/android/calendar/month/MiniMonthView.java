/*
 * Copyright (C) 2010 The Android Open Source Project
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

package com.android.calendar.month;

import com.android.calendar.CalendarController;
import com.android.calendar.CalendarController.EventType;
import com.android.calendar.CalendarController.ViewType;
import com.android.calendar.Event;
import com.android.calendar.EventGeometry;
import com.android.calendar.EventLoader;
import com.android.calendar.MonthFragment;
import com.android.calendar.R;
import com.android.calendar.Utils;

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Paint.Style;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.SystemClock;
import android.text.format.DateUtils;
import android.text.format.Time;
import android.util.Log;
import android.util.SparseArray;
import android.view.ContextMenu;
import android.view.ContextMenu.ContextMenuInfo;
import android.view.GestureDetector;
import android.view.KeyEvent;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.PopupWindow;

import java.util.ArrayList;

public class MiniMonthView extends View implements View.OnCreateContextMenuListener,
        MonthFragment.MonthViewInterface {

    private static final String TAG = "MiniMonthView";
    private static final boolean DEBUG = false;

    private static final boolean PROFILE_LOAD_TIME = false;

    // TODO Move to a more general location
    public static final int MIN_YEAR = 1970;
    public static final int MAX_YEAR = 2036;
    public static final int MIN_WEEK = MIN_YEAR * 52;
    public static final int MAX_WEEK = (MAX_YEAR + 1) * 52;

    protected static final int MIN_NUM_WEEKS = 3;
    protected static final int MAX_NUM_WEEKS = 16;
    protected static final int DEFAULT_NUM_WEEKS = 10;

    // The number of days before and after the visible range that we should
    // query for events. This lets us display DNA for a couple weeks when users
    // start scrolling.
    private static final int EVENT_QUERY_BUFFER_DAYS = 14;

    protected static float mScale = 0; // Used for supporting different screen densities
    protected static int WEEK_GAP = 0;
    protected static int MONTH_DAY_GAP = 1;
    protected static float HOUR_GAP = 0f;
    protected static float MIN_EVENT_HEIGHT = 2f;
    protected static int MONTH_DAY_TEXT_SIZE = 20;
    protected static int MONTH_NAME_TEXT_SIZE = 16;
    protected static int MONTH_NAME_PADDING = 4;
    protected static int WEEK_BANNER_HEIGHT = 17;
    protected static int WEEK_TEXT_SIZE = 15;
    protected static int WEEK_TEXT_PADDING = 3;
    protected static int EVENT_DOT_TOP_MARGIN = 5;
    protected static int EVENT_DOT_LEFT_MARGIN = 7;
    protected static int EVENT_DOT_W_H = 10;
    protected static int TEXT_TOP_MARGIN = 7;
    protected static int BUSY_BITS_WIDTH = 6;
    protected static int BUSY_BITS_MARGIN = 4;
    protected static int DAY_NUMBER_OFFSET = 10;
    protected static int VERTICAL_FLING_THRESHOLD = 50;

    protected static final int MENU_AGENDA = 2;
    protected static final int MENU_DAY = 3;
    protected static final int MENU_EVENT_CREATE = 6;

    // These are non-static to allow subclasses to override in a second view
    protected int mMonthNameSpace = MONTH_NAME_TEXT_SIZE + 2 * MONTH_NAME_PADDING;
    protected int mDesiredCellHeight = 40;

    // The number of days worth of events to load
    protected int mEventNumDays = 7 * (DEFAULT_NUM_WEEKS + 4);
    // Which day of the week the display starts on
    protected int mStartDayOfWeek;
    // How many weeks to display at a time
    protected int mNumWeeks = DEFAULT_NUM_WEEKS;
    // Which week position the selected day should snap to, this focuses the
    // user's currently selected day about 1/3 of the way down the view.
    protected int mFocusWeek = DEFAULT_NUM_WEEKS / 3;
    // The top left day displayed. Drawing and selecting use this as a reference
    // point.
    protected Time mFirstDay = new Time();
    // The current day being drawn to the canvas
    protected Time mDrawingDay = new Time();
    // The distance in pixels to offset the y position of the weeks
    protected int mWeekOffset = 0;

    // Height of a single day
    protected int mCellHeight;
    // Width of a single day
    protected int mCellWidth;
    // height of the view
    protected int mHeight;
    // width of the view
    protected int mWidth;
    // The number of pixels to leave as a buffer around the outside of the
    // view.
    protected int mBorder;
    // Whether or not a touch event will select the day it's on
    protected boolean mSelectDay;

    protected GestureDetector mGestureDetector;
    // Handles flings, GoTos, and snapping to a week
    protected GoToScroll mGoToScroll = new GoToScroll();

    // The current local time on the device
    protected Time mToday;
    // The time being focused on, typically the first day in the week
    // that is in the mFocusWeek position
    protected Time mViewCalendar;
    protected Time mSelectedDay = new Time();
    protected Time mSavedTime = new Time();   // the time when we entered this view
    protected boolean mIsEvenMonth; // whether today is in an even numbered month

    // This Time object is used to set the time for the other Month view.
    protected Time mOtherViewCalendar = new Time();

    // This Time object is used for temporary calculations and is allocated
    // once to avoid extra garbage collection
    protected Time mTempTime = new Time();

    protected Drawable mBoxSelected;
    protected Drawable mBoxPressed;
    protected Drawable mBoxLongPressed;

    protected Resources mResources;
    protected Context mContext;
    protected CalendarController mController;
    protected final EventGeometry mEventGeometry;

    // Pre-allocate and reuse
    protected Rect mRect = new Rect();

    //An array of which days have events for quick reference
    protected boolean[] eventDay = new boolean[mEventNumDays];

    protected PopupWindow mPopup;
    protected View mPopupView;
    protected static final int POPUP_HEIGHT = 100;
    protected int mPreviousPopupHeight;
    protected static final int POPUP_DISMISS_DELAY = 3000;

    // For drawing to an off-screen Canvas
    protected Bitmap mBitmap;
    protected Canvas mCanvas;
    protected boolean mRedrawScreen = true;
    protected Rect mBitmapRect = new Rect();
    protected RectF mRectF = new RectF();

    protected boolean mAnimating;
    protected boolean mOnFlingCalled = false;
    protected boolean mScrolling = false;

    protected boolean mShowDNA = true;
    protected int mGoToView = ViewType.CURRENT;

    // Bitmap caches.
    // These improve performance by minimizing calls to NinePatchDrawable.draw() for common
    // drawables for day backgrounds.
    // mDayBitmapCache is indexed by a unique integer constructed from the width/height.
    protected SparseArray<Bitmap> mDayBitmapCache = new SparseArray<Bitmap>(4);

    protected ContextMenuHandler mContextMenuHandler = new ContextMenuHandler();

    /**
     * The selection modes are HIDDEN, PRESSED, SELECTED, and LONGPRESS.
     */
    protected static final int SELECTION_HIDDEN = 0;
    protected static final int SELECTION_PRESSED = 1;
    protected static final int SELECTION_SELECTED = 2;
    protected static final int SELECTION_LONGPRESS = 3;

    protected int mSelectionMode = SELECTION_HIDDEN;

    /**
     * The first Julian day for which we have event data.
     */
    protected int mFirstEventJulianDay;

    protected final EventLoader mEventLoader;

    protected ArrayList<Event> mEvents = new ArrayList<Event>();

    protected Drawable mTodayBackground;

    // Cached colors
    // TODO rename colors here and in xml to reflect how they are used
    protected int mMonthOtherMonthColor;
    protected int mMonthWeekBannerColor;
    protected int mMonthOtherMonthBannerColor;
    protected int mMonthOtherMonthDayNumberColor;
    protected int mMonthDayNumberColor;
    protected int mMonthTodayNumberColor;
    protected int mMonthSaturdayColor;
    protected int mMonthSundayColor;
    protected int mBusybitsColor;
    protected int mMonthBgColor;

    public MiniMonthView(Context activity, CalendarController controller, EventLoader eventLoader) {
        super(activity);
        if (mScale == 0) {
            mScale = getContext().getResources().getDisplayMetrics().density;
           if (mScale != 1) {
                    WEEK_GAP *= mScale;
                    MONTH_DAY_GAP *= mScale;
                    HOUR_GAP *= mScale;
                    mDesiredCellHeight *= mScale;
                    MONTH_DAY_TEXT_SIZE *= mScale;
                    WEEK_BANNER_HEIGHT *= mScale;
                    WEEK_TEXT_SIZE *= mScale;
                    WEEK_TEXT_PADDING *= mScale;
                    EVENT_DOT_TOP_MARGIN *= mScale;
                    EVENT_DOT_LEFT_MARGIN *= mScale;
                    EVENT_DOT_W_H *= mScale;
                    TEXT_TOP_MARGIN *= mScale * (mShowDNA ? 1 : 0);
                    VERTICAL_FLING_THRESHOLD *= mScale;
                    MIN_EVENT_HEIGHT *= mScale;
                    // The boolean check makes sure text is centered whether or not DNA view is on
                    BUSY_BITS_WIDTH *= mScale * (mShowDNA ? 1 : 0);
                    BUSY_BITS_MARGIN *= mScale * (mShowDNA ? 1 : 0);
                    DAY_NUMBER_OFFSET *= mScale;
                    MONTH_NAME_TEXT_SIZE *= mScale;
                    MONTH_NAME_PADDING *= mScale;
                    mMonthNameSpace = MONTH_NAME_TEXT_SIZE + 2 * MONTH_NAME_PADDING;
                }
            }

        mEventLoader = eventLoader;
        mController = controller;
        mEventGeometry = new EventGeometry();
        mEventGeometry.setMinEventHeight(MIN_EVENT_HEIGHT);
        mEventGeometry.setHourGap(HOUR_GAP);
        init(activity);
    }

    private void init(Context activity) {
        setFocusable(true);
        setClickable(true);
        setOnCreateContextMenuListener(this);
        mContext = activity;
        mViewCalendar = new Time();
        long now = System.currentTimeMillis();
        mViewCalendar.set(now);
        mViewCalendar.monthDay = 1;
        long millis = mViewCalendar.normalize(true /* ignore DST */);
        mFirstEventJulianDay = Time.getJulianDay(millis, mViewCalendar.gmtoff);
        mStartDayOfWeek = Utils.getFirstDayOfWeek(activity);
        mViewCalendar.set(now);
        makeFirstDayOfWeek(mViewCalendar);
        // The first week is mFocusWeek weeks earlier than the current week
        mFirstDay.set(now - DateUtils.WEEK_IN_MILLIS * mFocusWeek);
        makeFirstDayOfWeek(mFirstDay);
        mToday = new Time();
        mToday.set(System.currentTimeMillis());
        mIsEvenMonth = (mToday.month & 1) == 0;

        mResources = activity.getResources();
        mBoxSelected = mResources.getDrawable(R.drawable.month_view_selected);
        mBoxPressed = mResources.getDrawable(R.drawable.month_view_pressed);
        mBoxLongPressed = mResources.getDrawable(R.drawable.month_view_longpress);

        mTodayBackground = mResources.getDrawable(R.drawable.month_view_today_background);

        // Cache color lookups
        Resources res = getResources();
        mMonthOtherMonthColor = res.getColor(R.color.month_other_month);
        mMonthWeekBannerColor = res.getColor(R.color.month_week_banner);
        mMonthOtherMonthBannerColor = res.getColor(R.color.month_other_month_banner);
        mMonthOtherMonthDayNumberColor = res.getColor(R.color.month_other_month_day_number);
        mMonthDayNumberColor = res.getColor(R.color.month_day_number);
        mMonthTodayNumberColor = res.getColor(R.color.month_today_number);
        mMonthSaturdayColor = res.getColor(R.color.month_saturday);
        mMonthSundayColor = res.getColor(R.color.month_sunday);
        mBusybitsColor = res.getColor(R.color.month_busybits);
        mMonthBgColor = res.getColor(R.color.month_bgcolor);

        mGestureDetector = new GestureDetector(getContext(),
                new GestureDetector.SimpleOnGestureListener() {
            int mScrollOffset;
            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX,
                    float velocityY) {
                // The user might do a slow "fling" after touching the screen
                // and we don't want the long-press to pop up a context menu.
                // Setting mLaunchDayView to false prevents the long-press.
                mSelectDay = false;
                mSelectionMode = SELECTION_HIDDEN;
                return doFling(e1, e2, velocityX, velocityY);
            }

            @Override
            public boolean onDown(MotionEvent e) {
                mSelectDay = true;
                mOnFlingCalled = false;
                mScrollOffset = 0;
                getHandler().removeCallbacks(mGoToScroll);
                return true;
            }

            @Override
            public void onShowPress(MotionEvent e) {
                // Highlight the selected day.
//                setSelectedCell(e);
                long millis = getSelectedMillisFor((int) e.getX(), (int) e.getY());
                mSelectedDay.set(millis);
                mSelectionMode = SELECTION_PRESSED;
                mRedrawScreen = true;
                invalidate();
            }

            @Override
            public void onLongPress(MotionEvent e) {
                // If mLaunchDayView is true, then we haven't done any scrolling
                // after touching the screen, so allow long-press to proceed
                // with popping up the context menu.
                if (mSelectDay) {
                    mSelectDay = false;
                    mSelectionMode = SELECTION_LONGPRESS;
                    mRedrawScreen = true;
                    invalidate();
                    performLongClick();
                }
            }

            @Override
            public boolean onScroll(MotionEvent e1, MotionEvent e2,
                    float distanceX, float distanceY) {
//                MiniMonthView.this.doScroll(e1, e2, distanceX, distanceY);
                // Use the distance from the current point to the initial touch instead
                // of deltaX and deltaY to avoid accumulating floating-point rounding
                // errors.  Also, we don't need floats, we can use ints.
                int dY = (int) e1.getY() - (int) e2.getY() - mScrollOffset;
                while (dY > mCellHeight) {
                    dY -= mCellHeight;
                    mScrollOffset += mCellHeight;
                    mFirstDay.monthDay += 7;
                }
                while (dY < -mCellHeight) {
                    dY += mCellHeight;
                    mScrollOffset -= mCellHeight;
                    mFirstDay.monthDay -= 7;
                }
                mFirstDay.normalize(true);
                mWeekOffset = -dY;

                mScrolling = true;
                mRedrawScreen = true;
                invalidate();
                return true;
            }

            @Override
            public boolean onSingleTapUp(MotionEvent e) {
                if (mSelectDay) {
                    mSelectionMode = SELECTION_SELECTED;
                    mRedrawScreen = true;
                    invalidate();
                    mSelectDay = false;
                    int x = (int) e.getX();
                    int y = (int) e.getY();
                    long millis = getSelectedMillisFor(x, y);

                    mTempTime.set(millis);
                    mController.sendEvent(this, EventType.GO_TO, mTempTime, null, -1,
                            mGoToView);
                }

                return true;
            }
        });
    }

    private void makeFirstDayOfWeek(Time time) {
        int dayOfWeek = time.weekDay;
        if (mStartDayOfWeek == Time.SUNDAY) {
            time.monthDay -= dayOfWeek;
        } else if (mStartDayOfWeek == Time.MONDAY) {
            if (dayOfWeek == Time.SUNDAY) {
                dayOfWeek += 7;
            }
            time.monthDay -= (dayOfWeek - Time.MONDAY);
        } else if (mStartDayOfWeek == Time.SATURDAY) {
            if (dayOfWeek != Time.SATURDAY) {
                dayOfWeek += 7;
            }
            time.monthDay -= (dayOfWeek - Time.SATURDAY);
        }
        time.normalize(true);
    }

    boolean doFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
        mSelectionMode = SELECTION_HIDDEN;
        mOnFlingCalled = true;
        int deltaX = (int) e2.getX() - (int) e1.getX();
        int distanceX = Math.abs(deltaX);
        int deltaY = (int) e2.getY() - (int) e1.getY();
        int distanceY = Math.abs(deltaY);

        if ((distanceY <= VERTICAL_FLING_THRESHOLD) || (distanceY < distanceX)) {
            // TODO do we want to ignore user input if they aren't swiping mostly vertical?
            return false;
        }

        // Continue scrolling vertically

        Handler handler = getHandler();
        if (handler != null) {
            getHandler().removeCallbacks(mGoToScroll);
        }
        mGoToScroll.init((int) -velocityY / 100);
        post(mGoToScroll);
        return true;
    }

    // Encapsulates the code to continue the scrolling after the
    // finger is lifted.  Instead of stopping the scroll immediately,
    // the scroll continues for a given number of weeks and then snaps
    // to be on a week.
    private class GoToScroll implements Runnable {
        int mWeeks;
        int mScrollCount;
        int mInitialOffset;
        Time mStartTime = new Time();
        private static final int SCROLL_REPEAT_INTERVAL = 30;
        private static final int MAX_REPEAT_COUNT = 30;
        private int SCROLL_REPEAT_COUNT = 10;

        public void init(int numWeeks) {

            int currWeek = mFirstDay.getWeekNumber() + 52 * mFirstDay.year;
            int endWeek = currWeek + numWeeks;
            if (endWeek < MIN_WEEK) {
                // Will send it to the first week of 1970
                numWeeks = MIN_WEEK - currWeek;
            } else if (endWeek + mNumWeeks - 1 > MAX_WEEK) {
                // Will send it to the last week of 2036
                numWeeks = MAX_WEEK - currWeek - mNumWeeks + 1;
            }
            mInitialOffset = mWeekOffset;
            mWeeks = numWeeks; //Math.abs(numWeeks);
            mStartTime.set(mFirstDay);

            mScrollCount = 1;
            SCROLL_REPEAT_COUNT = 10 + Math.abs(numWeeks) / 3;
            if (SCROLL_REPEAT_COUNT > MAX_REPEAT_COUNT) {
                SCROLL_REPEAT_COUNT = MAX_REPEAT_COUNT;
            }
        }

        // At each step we set the first day and the y offset for the next
        // position in a 'smooth' scroll.
        public void run() {
            // Calculate it based on the start so we don't accumulate rounding
            // errors
            mFirstDay.set(mStartTime.toMillis(true) + DateUtils.WEEK_IN_MILLIS
                    * (long) Math.floor(mWeeks * mScrollCount / SCROLL_REPEAT_COUNT));
            makeFirstDayOfWeek(mFirstDay);
            mWeekOffset = (mWeeks * mCellHeight * mScrollCount / SCROLL_REPEAT_COUNT)
                    % mCellHeight;
            // Get the direction right and make it a smooth scroll from wherever
            // we were before
            mWeekOffset = -mWeekOffset +
                (mInitialOffset - mInitialOffset * mScrollCount / SCROLL_REPEAT_COUNT);

            if (mScrollCount < SCROLL_REPEAT_COUNT) {
                postDelayed(this, SCROLL_REPEAT_INTERVAL);
                mScrollCount++;
            } else {
                // Done scrolling.
                mWeekOffset = 0;
                mScrolling = false;
                reloadEvents();
            }

            mRedrawScreen = true;
            invalidate();
        }
    }

    public void onCreateContextMenu(ContextMenu menu, View view, ContextMenuInfo menuInfo) {
        MenuItem item;

        final long startMillis = getSelectedTimeInMillis();
        final int flags = DateUtils.FORMAT_SHOW_WEEKDAY | DateUtils.FORMAT_SHOW_DATE
                | DateUtils.FORMAT_ABBREV_MONTH;

        final String title = DateUtils.formatDateTime(mContext, startMillis, flags);
        menu.setHeaderTitle(title);

        item = menu.add(0, MENU_DAY, 0, R.string.show_day_view);
        item.setOnMenuItemClickListener(mContextMenuHandler);
        item.setIcon(android.R.drawable.ic_menu_day);
        item.setAlphabeticShortcut('d');

        item = menu.add(0, MENU_AGENDA, 0, R.string.show_agenda_view);
        item.setOnMenuItemClickListener(mContextMenuHandler);
        item.setIcon(android.R.drawable.ic_menu_agenda);
        item.setAlphabeticShortcut('a');

        item = menu.add(0, MENU_EVENT_CREATE, 0, R.string.event_create);
        item.setOnMenuItemClickListener(mContextMenuHandler);
        item.setIcon(android.R.drawable.ic_menu_add);
        item.setAlphabeticShortcut('n');
    }

    private class ContextMenuHandler implements MenuItem.OnMenuItemClickListener {
        public boolean onMenuItemClick(MenuItem item) {
            switch (item.getItemId()) {
                case MENU_DAY: {
                    mController.sendEvent(this, EventType.GO_TO, mSelectedDay, null, -1,
                            ViewType.DAY);
                    break;
                }
                case MENU_AGENDA: {
                    mController.sendEvent(this, EventType.GO_TO, mSelectedDay, null, -1,
                            ViewType.AGENDA);
                    break;
                }
                case MENU_EVENT_CREATE: {
                    long startMillis = getSelectedTimeInMillis();
                    long endMillis = startMillis + DateUtils.HOUR_IN_MILLIS;
                    mController.sendEventRelatedEvent(this, EventType.CREATE_EVENT, -1,
                            startMillis, endMillis, 0, 0);
                    break;
                }
                default: {
                    return false;
                }
            }
            return true;
        }
    }

    public void reloadEvents() {
        if (!mShowDNA) {
            return;
        }

        long millis = getFirstEventStartMillis();

        // Load the days with events in the background
//FRAG_TODO        mParentActivity.startProgressSpinner();
        final long startMillis;
        if (PROFILE_LOAD_TIME) {
            startMillis = SystemClock.uptimeMillis();
        } else {
            // To avoid a compiler error that this variable might not be initialized.
            startMillis = 0;
        }

        final ArrayList<Event> events = new ArrayList<Event>();
        mEventLoader.loadEventsInBackground(mEventNumDays, events, millis, new Runnable() {
            public void run() {
                if (DEBUG) {
                    Log.d(TAG, "found " + events.size() + " events");
                }
                mEvents = events;
//FRAG_TODO                mParentActivity.stopProgressSpinner();
                int numEvents = events.size();
                // Clear out event days
                for (int i = 0; i < mEventNumDays; i++) {
                    eventDay[i] = false;
                }

                long millis = getFirstEventStartMillis();
                mFirstEventJulianDay = Time.getJulianDay(millis, mTempTime.gmtoff);
                // Compute the new set of days with events
                for (int i = 0; i < numEvents; i++) {
                    Event event = events.get(i);
                    int startDay = event.startDay - mFirstEventJulianDay;
                    int endDay = event.endDay - mFirstEventJulianDay + 1;
                    if (startDay < mEventNumDays || endDay >= 0) {
                        if (startDay < 0) {
                            startDay = 0;
                        }
                        if (startDay > mEventNumDays) {
                            continue;
                        }
                        if (endDay < 0) {
                            continue;
                        }
                        if (endDay > mEventNumDays) {
                            endDay = mEventNumDays;
                        }
                        for (int j = startDay; j < endDay; j++) {
                            eventDay[j] = true;
                        }
                    }
                }
                mRedrawScreen = true;
                invalidate();
            }
        }, null);
    }

    private long getFirstEventStartMillis() {
        Time eventsStart = mTempTime;
        eventsStart.set(mFirstDay);
        // Query is from 2 weeks before our first day to two weeks after our
        // last day
        eventsStart.monthDay -= EVENT_QUERY_BUFFER_DAYS;
        eventsStart.hour = 0;
        eventsStart.minute = 0;
        eventsStart.second = 0;
        return eventsStart.normalize(true /* ignore isDst */);
    }

    public void animationStarted() {
        mAnimating = true;
    }

    public void animationFinished() {
        mAnimating = false;
        mRedrawScreen = true;
        invalidate();
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldw, int oldh) {
        drawingCalc(width, height);
        // If the size changed, then we should rebuild the bitmaps...
        clearBitmapCache();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        // No need to hang onto the bitmaps...
        clearBitmapCache();
        if (mBitmap != null) {
            mBitmap.recycle();
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (mRedrawScreen) {
            if (mCanvas == null) {
                drawingCalc(getWidth(), getHeight());
            }

            // If we are zero-sized, the canvas will remain null so check again
            if (mCanvas != null) {
                // Clear the background
                final Canvas bitmapCanvas = mCanvas;
                bitmapCanvas.drawColor(0, PorterDuff.Mode.CLEAR);
                doDraw(bitmapCanvas);
                mRedrawScreen = false;
            }
        }

        // If we are zero-sized, the bitmap will be null so guard against this
        if (mBitmap != null) {
            canvas.drawBitmap(mBitmap, mBitmapRect, mBitmapRect, null);
        }
    }

    private void doDraw(Canvas canvas) {
        boolean isLandscape = getResources().getConfiguration().orientation
                == Configuration.ORIENTATION_LANDSCAPE;

        Paint p = new Paint();
        Rect r = mRect;
        int day = 0;
        mDrawingDay.set(mFirstDay);
        int lastWeek = mNumWeeks;
        int row = 0;
        if (mWeekOffset > mCellHeight) {
            day = -14;
            mDrawingDay.monthDay -= 14;
            mDrawingDay.normalize(true);
            row = -2;
        } else if (mWeekOffset > 0) {
            day = -7;
            mDrawingDay.monthDay -= 7;
            mDrawingDay.normalize(true);
            row = -1;
        } else if (mWeekOffset < 0) {
            lastWeek++;
            if (mWeekOffset < -mCellHeight) {
                lastWeek++;
            }
        }

        for (; row < lastWeek; row++) {
            for (int column = 0; column < 7; column++) {
                drawBox(day, row, column, canvas, p, r, isLandscape);
                day++;
                mDrawingDay.monthDay++;
                mDrawingDay.normalize(true);
            }
//            if (mShowWeekNumbers) {
//                weekNum += 1;
//                if (weekNum >= 53) {
//                    boolean inCurrentMonth = (day - mFirstJulianDay < 31);
//                    weekNum = getWeekOfYear(row + 1, 0, inCurrentMonth, calendar);
//                }
//            }
        }
        drawGrid(canvas, p);
        drawMonthNames(canvas, p);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        int action = event.getAction();

        if (mGestureDetector.onTouchEvent(event)) {
            return true;
        } else if (action == MotionEvent.ACTION_UP) {
            int halfHeight = (mCellHeight / 2);
            // slide to the nearest week if the fling wasn't fast enough to do do a proper scroll
            if (mWeekOffset > halfHeight) {
                mGoToScroll.init(-1);
            } else if (mWeekOffset < -halfHeight) {
                mGoToScroll.init(1);
            } else {
                mGoToScroll.init(0);
            }
            post(mGoToScroll);
            return true;
        }

        return super.onTouchEvent(event);
    }

    private long getSelectedMillisFor(int x, int y) {
        int row = (y - WEEK_GAP) / (WEEK_GAP + mCellHeight);
        int column = (x - mBorder) / (MONTH_DAY_GAP + mCellWidth);
//        if (column > 6) {
//            column = 6;
//        }

//        DayOfMonthCursor c = mCursor;
        Time time = mTempTime;
        time.set(mFirstDay);

        // Compute the day number from the row and column.  If the row and
        // column are in a different month from the current one, then the
        // monthDay might be negative or it might be greater than the number
        // of days in this month, but that is okay because the normalize()
        // method will adjust the month (and year) if necessary.
        time.monthDay += 7 * row + column;
        return time.normalize(true);
    }

    /**
     * Create a bitmap at the origin and draw the drawable to it using the bounds specified by rect.
     *
     * @param drawable the drawable we wish to render
     * @param width the width of the resulting bitmap
     * @param height the height of the resulting bitmap
     * @return a new bitmap
     */
    private Bitmap createBitmap(Drawable drawable, int width, int height) {
        // Create a bitmap with the same format as mBitmap (should be Bitmap.Config.ARGB_8888)
        Bitmap bitmap = Bitmap.createBitmap(width, height, mBitmap.getConfig());

        // Draw the drawable into the bitmap at the origin.
        Canvas canvas = new Canvas(bitmap);
        drawable.setBounds(0, 0, width, height);
        drawable.draw(canvas);
        return bitmap;
    }

    /**
     * Clears the bitmap cache. Generally only needed when the screen size changed.
     */
    private void clearBitmapCache() {
        recycleAndClearBitmapCache(mDayBitmapCache);
    }

    private void recycleAndClearBitmapCache(SparseArray<Bitmap> bitmapCache) {
        int size = bitmapCache.size();
        for(int i = 0; i < size; i++) {
            bitmapCache.valueAt(i).recycle();
        }
        bitmapCache.clear();

    }

    /**
     * Draw the names of the month on the left.
     */
    private void drawMonthNames(Canvas canvas, Paint p) {

    }

    /**
     * Draw the grid lines for the calendar
     * @param canvas The canvas to draw on.
     * @param p The paint used for drawing.
     */
    private void drawGrid(Canvas canvas, Paint p) {
        p.setColor(mMonthOtherMonthColor);
        p.setAntiAlias(false);

        final int width = mWidth + mMonthNameSpace;
        final int height = mHeight;

        int count = 0;
        int y = mWeekOffset;
        if (y > mCellHeight) {
            y -= mCellHeight;
        }
        while (y <= height) {
            canvas.drawLine(mMonthNameSpace, y, width, y, p);
            // Compute directly to avoid rounding errors
            count++;
            y = count * height / mNumWeeks + mWeekOffset - 1;
        }

        int x = mMonthNameSpace;
        count = 0;
        while (x <= width) {
            canvas.drawLine(x, WEEK_GAP, x, height, p);
            count++;
            // Compute directly to avoid rounding errors
            x = count * mWidth / 7 + mBorder - 1 + mMonthNameSpace;
        }
    }

    private void drawBox(int day, int row, int column, Canvas canvas, Paint p,
            Rect r, boolean isLandscape) {
        boolean drawSelection = false;
        // Check if we're drawing the selected day and if we should show
        // it as selected.
        if (mSelectionMode != SELECTION_HIDDEN) {
            drawSelection = mSelectedDay.year == mDrawingDay.year &&
                    mSelectedDay.yearDay == mDrawingDay.yearDay;
        }
        int julianDay = Time.getJulianDay(mDrawingDay.toMillis(true), mDrawingDay.gmtoff);

        // Check if we're in a light or dark colored month
        boolean colorSameAsCurrent = ((mDrawingDay.month & 1) == 0) == mIsEvenMonth;
        boolean isToday = false;
        if (mDrawingDay.year == mToday.year && mDrawingDay.yearDay == mToday.yearDay) {
            isToday = true;
        }
        // We calculate the position relative to the total size
        // to avoid rounding errors.
        int y = row * mHeight / mNumWeeks + mWeekOffset;
        int x = column * mWidth / 7 + mBorder + mMonthNameSpace;

        r.left = x;
        r.top = y;
        r.right = x + mCellWidth;
        r.bottom = y + mCellHeight;

        // Draw the cell contents (excluding monthDay number)
        if (drawSelection) {
            if (mSelectionMode == SELECTION_SELECTED) {
                mBoxSelected.setBounds(r);
                mBoxSelected.draw(canvas);
            } else if (mSelectionMode == SELECTION_PRESSED) {
                mBoxPressed.setBounds(r);
                mBoxPressed.draw(canvas);
            } else {
                mBoxLongPressed.setBounds(r);
                mBoxLongPressed.draw(canvas);
            }
        } else if (isToday) {
            // We could cache this for a little bit more performance, but it's not on the
            // performance radar...
            Drawable background = mTodayBackground;
            background.setBounds(r);
            background.draw(canvas);
        } else if (!colorSameAsCurrent) {
            // Adjust cell boundaries to compensate for the different border
            // style.
            r.top--;
            if (column != 0) {
                r.left--;
            }
            p.setStyle(Style.FILL);
            p.setColor(mMonthBgColor);
            canvas.drawRect(r, p);
        } else {
            // Adjust cell boundaries to compensate for the different border
            // style.
            r.top--;
            if (column != 0) {
                r.left--;
            }
            p.setStyle(Style.FILL);
            p.setColor(Color.WHITE);
            canvas.drawRect(r, p);
        }


        // TODO Places events for that day
        drawEvents(julianDay, canvas, r, p, colorSameAsCurrent);

        // Draw the monthDay number
        p.setStyle(Paint.Style.FILL);
        p.setAntiAlias(true);
        p.setTypeface(null);
        p.setTextSize(MONTH_DAY_TEXT_SIZE);

        if (!colorSameAsCurrent) {
            if (Utils.isSunday(column, mStartDayOfWeek)) {
                p.setColor(mMonthSundayColor);
            } else if (Utils.isSaturday(column, mStartDayOfWeek)) {
                p.setColor(mMonthSaturdayColor);
            } else {
                p.setColor(mMonthOtherMonthDayNumberColor);
            }
        } else {
            if (isToday && !drawSelection) {
                p.setColor(mMonthTodayNumberColor);
            } else if (Utils.isSunday(column, mStartDayOfWeek)) {
                p.setColor(mMonthSundayColor);
            } else if (Utils.isSaturday(column, mStartDayOfWeek)) {
                p.setColor(mMonthSaturdayColor);
            } else {
                p.setColor(mMonthDayNumberColor);
            }
        }

        // bolds the day if there's an event that day
        int julianOffset = julianDay - mFirstEventJulianDay;
        if (julianOffset >= 0 && julianOffset < mEventNumDays) {
            p.setFakeBoldText(eventDay[julianOffset]);
        } else {
            p.setFakeBoldText(false);
        }
        /*Drawing of day number is done here
         *easy to find tags draw number draw day*/
        p.setTextAlign(Paint.Align.CENTER);
        // center of text
        // TODO figure out why it's not actually centered
        int textX = x + (mCellWidth - BUSY_BITS_MARGIN - BUSY_BITS_WIDTH) / 2;
        // bottom of text
        int textY = y + (mCellHeight + MONTH_DAY_TEXT_SIZE - 1) / 2 /*+ TEXT_TOP_MARGIN*/;
        canvas.drawText(String.valueOf(mDrawingDay.monthDay), textX, textY, p);
    }

    ///Create and draw the event busybits for this day
    private void drawEvents(int date, Canvas canvas, Rect rect, Paint p, boolean drawBg) {
        if (!mShowDNA) {
            return;
        }
        if (DEBUG) {
            Log.d(TAG, "Drawing event for " + date);
        }
        // The top of the busybits section lines up with the top of the day number
        int top = rect.top /*+ TEXT_TOP_MARGIN*/ + BUSY_BITS_MARGIN;
        int left = rect.right - BUSY_BITS_MARGIN - BUSY_BITS_WIDTH;

        Style oldStyle = p.getStyle();
        int oldColor = p.getColor();

        ArrayList<Event> events = mEvents;
        int numEvents = events.size();
        EventGeometry geometry = mEventGeometry;

        RectF rf = mRectF;
        rf.left = left;
        rf.right = left + BUSY_BITS_WIDTH;
        rf.bottom = rect.bottom - BUSY_BITS_MARGIN;
        rf.top = top;
        p.setStyle(Style.FILL);

        if (drawBg) {
            p.setColor(mMonthBgColor);
        } else {
            p.setColor(Color.WHITE);
        }
        canvas.drawRect(rf, p);

        for (int i = 0; i < numEvents; i++) {
            Event event = events.get(i);
            if (!geometry.computeEventRect(date, left, top, BUSY_BITS_WIDTH, event)) {
                continue;
            }
            drawEventRect(rect, event, canvas, p);
        }

    }

    // Draw busybits for a single event
    private RectF drawEventRect(Rect rect, Event event, Canvas canvas, Paint p) {

        p.setColor(mBusybitsColor);

        int left = rect.right - BUSY_BITS_MARGIN - BUSY_BITS_WIDTH;
        int bottom = rect.bottom - BUSY_BITS_MARGIN;

        RectF rf = mRectF;
        rf.top = event.top;
        // Make sure we don't go below the bottom of the bb bar
        rf.bottom = Math.min(event.bottom, bottom);
        rf.left = left;
        rf.right = left + BUSY_BITS_WIDTH;

        canvas.drawRect(rf, p);

        return rf;
    }

    public void setSelectedTime(Time time) {
        Log.d(TAG, "Setting time to " + time);
        // Save the selected time so that we can restore it later when we switch views.
        mSavedTime.set(time);

        mViewCalendar.set(time);
        mViewCalendar.monthDay = 1;
        long millis = mViewCalendar.normalize(true /* ignore DST */);
        mViewCalendar.set(time);

        Handler handler = getHandler();
        if (handler != null) {
            getHandler().removeCallbacks(mGoToScroll);
        }
        makeFirstDayOfWeek(mViewCalendar);
        // Kick off a scroll to the selected day
        mGoToScroll.init(mViewCalendar.getWeekNumber() - mFocusWeek - mFirstDay.getWeekNumber()
                + 52 * (mViewCalendar.year - mFirstDay.year));
        post(mGoToScroll);
        mSelectedDay.set(time);


        mSelectionMode = SELECTION_SELECTED;
        mRedrawScreen = true;
        invalidate();
    }

    public long getSelectedTimeInMillis() {
        return mSelectedDay.normalize(true);
    }

    public Time getTime() {
        return mViewCalendar;
    }

    public int getSelectionMode() {
        return mSelectionMode;
    }

    public void setSelectionMode(int selectionMode) {
        mSelectionMode = selectionMode;
    }

    private void drawingCalc(int width, int height) {
        mHeight = getMeasuredHeight();
        mWidth = getMeasuredWidth() - mMonthNameSpace;
        mNumWeeks = mHeight / mDesiredCellHeight;
        if (mNumWeeks < MIN_NUM_WEEKS) {
            mNumWeeks = MIN_NUM_WEEKS;
        } else if (mNumWeeks > MAX_NUM_WEEKS) {
            mNumWeeks = MAX_NUM_WEEKS;
        }
        // This lets us query 2 weeks before and after the first visible day
        mEventNumDays = 7 * (mNumWeeks + 4);
        eventDay = new boolean[mEventNumDays];
        mCellHeight = (height - (mNumWeeks * WEEK_GAP)) / mNumWeeks;
        mEventGeometry
                .setHourHeight((mCellHeight - BUSY_BITS_MARGIN * 2 - TEXT_TOP_MARGIN) / 24.0f);
        mCellWidth = (width - (6 * MONTH_DAY_GAP)) / 7;
        mBorder = (width - 6 * (mCellWidth + MONTH_DAY_GAP) - mCellWidth) / 2;

        if (((mBitmap == null)
                    || mBitmap.isRecycled()
                    || (mBitmap.getHeight() != height)
                    || (mBitmap.getWidth() != width))
                && (width > 0) && (height > 0)) {
            if (mBitmap != null) {
                mBitmap.recycle();
            }
            mBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            mCanvas = new Canvas(mBitmap);
        }

        mBitmapRect.top = 0;
        mBitmapRect.bottom = height;
        mBitmapRect.left = 0;
        mBitmapRect.right = width;
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        long duration = event.getEventTime() - event.getDownTime();

        switch (keyCode) {
        case KeyEvent.KEYCODE_DPAD_CENTER:
            if (mSelectionMode == SELECTION_HIDDEN) {
                // Don't do anything unless the selection is visible.
                break;
            }

            if (mSelectionMode == SELECTION_PRESSED) {
                // This was the first press when there was nothing selected.
                // Change the selection from the "pressed" state to the
                // the "selected" state.  We treat short-press and
                // long-press the same here because nothing was selected.
                mSelectionMode = SELECTION_SELECTED;
                mRedrawScreen = true;
                invalidate();
                break;
            }

            // Check the duration to determine if this was a short press
            if (duration < ViewConfiguration.getLongPressTimeout()) {
                mController.sendEvent(this, EventType.GO_TO, mSelectedDay, null, -1,
                        mGoToView);
            } else {
                mSelectionMode = SELECTION_LONGPRESS;
                mRedrawScreen = true;
                invalidate();
                performLongClick();
            }
        }
        return super.onKeyUp(keyCode, event);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (mSelectionMode == SELECTION_HIDDEN) {
            if (keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
                    || keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_UP
                    || keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                // Display the selection box but don't move or select it
                // on this key press.
                mSelectionMode = SELECTION_SELECTED;
                mRedrawScreen = true;
                invalidate();
                return true;
            } else if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER) {
                // Display the selection box but don't select it
                // on this key press.
                mSelectionMode = SELECTION_PRESSED;
                mRedrawScreen = true;
                invalidate();
                return true;
            }
        }

        mSelectionMode = SELECTION_SELECTED;
        boolean redraw = false;

        switch (keyCode) {
            // TODO make month move correctly when selected week changes
        case KeyEvent.KEYCODE_ENTER:
            mController.sendEvent(this, EventType.GO_TO, mSelectedDay, null, -1, mGoToView);
            return true;
        case KeyEvent.KEYCODE_DPAD_UP:
            redraw = true;
            break;

        case KeyEvent.KEYCODE_DPAD_DOWN:
            redraw = true;
            break;

        case KeyEvent.KEYCODE_DPAD_LEFT:
            redraw = true;
            break;

        case KeyEvent.KEYCODE_DPAD_RIGHT:
            redraw = true;
            break;
        }

        if (redraw) {
            mRedrawScreen = true;
            invalidate();
        }

        return redraw;
    }
}
