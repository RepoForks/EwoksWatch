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
 * limitations under the License.
 */

package com.romainpotier.ewokswatch;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Message;
import android.support.v4.content.ContextCompat;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.SurfaceHolder;
import android.view.WindowInsets;

import com.romainpotier.ewokswatch.preferences.SharedPrefManager;

import java.lang.ref.WeakReference;
import java.text.Format;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.TimeUnit;

public class MyWatchFace extends CanvasWatchFaceService {

    private static final Typeface BOLD_TYPEFACE =
            Typeface.create(Typeface.DEFAULT_BOLD, Typeface.BOLD);

    private static final long INTERACTIVE_UPDATE_RATE_MS = TimeUnit.SECONDS.toMillis(1);

    private static final int MSG_UPDATE_TIME = 0;

    private static final String LOG_TAG = "EwoksWatchLog";

    @Override
    public Engine onCreateEngine() {
        return new Engine();
    }

    private static class EngineHandler extends Handler {
        private final WeakReference<MyWatchFace.Engine> mWeakReference;

        public EngineHandler(MyWatchFace.Engine reference) {
            mWeakReference = new WeakReference<>(reference);
        }

        @Override
        public void handleMessage(Message msg) {
            MyWatchFace.Engine engine = mWeakReference.get();
            if (engine != null) {
                switch (msg.what) {
                    case MSG_UPDATE_TIME:
                        engine.handleUpdateTimeMessage();
                        break;
                }
            }
        }
    }

    private static class AnimTapTask extends TimerTask {

        private final WeakReference<MyWatchFace.Engine> mWeakReference;
        private boolean mTwice;
        private final Context mContext;

        public AnimTapTask(MyWatchFace.Engine reference, boolean twice, Context context) {
            mWeakReference = new WeakReference<>(reference);
            mTwice = twice;
            mContext = context;
        }

        @Override
        public void run() {
            final Engine engine = mWeakReference.get();
            if (engine != null) {

                final ResourceCollection resource = RESOURCES[engine.mCurrentResourceIndex];
                engine.mCurrentBitmap = Bitmap.createScaledBitmap(
                        BitmapFactory.decodeResource(mContext.getResources(), resource.mPictureTap), engine.mWidth, engine.mHeight, true);
                engine.invalidate();

                synchronized (this) {
                    try {
                        wait(150);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }

                engine.mCurrentBitmap = Bitmap.createScaledBitmap(
                        BitmapFactory.decodeResource(mContext.getResources(), resource.mPicture), engine.mWidth, engine.mHeight, true);
                engine.invalidate();

                if (!mTwice) {
                    engine.mTimer.cancel();
                    engine.mTimerRunning = false;
                }

                mTwice = false;
            }
        }
    }

    private class Engine extends CanvasWatchFaceService.Engine {

        private final Handler mUpdateTimeHandler = new EngineHandler(this);
        private boolean mRegisteredTimeZoneReceiver = false;

        private Paint mTextPaintHours;
        private Paint mTextPaintMinutes;
        private Paint mTextPaintDate;

        private Bitmap mCurrentBitmap;
        private Bitmap mCurrentAmbientBitmap;

        private boolean mAmbient;

        private int mCurrentResourceIndex;

        private int mWidth;
        private int mHeight;
//        private int mScreenSize;

        private GregorianCalendar mLastUpdateCalendar;
        private GregorianCalendar mCurrentCalendar;

        private Timer mTimer;
        private boolean mTimerRunning;

        private boolean mBurnMode;

        private int mSpacingBottom;

        final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mCurrentCalendar = new GregorianCalendar();
            }
        };

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);

            setWatchFaceStyle(new WatchFaceStyle.Builder(MyWatchFace.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_SHORT)
                    .setPeekOpacityMode(WatchFaceStyle.PEEK_OPACITY_MODE_TRANSLUCENT)
                    .setShowSystemUiTime(false)
                    .setStatusBarGravity(Gravity.RIGHT | Gravity.TOP)
                    .setAcceptsTapEvents(true)
                    .build());

            mTextPaintHours = createTextPaintHours();
            mTextPaintMinutes = createTextPaintLight();
            mTextPaintDate = createTextPaintLight();

            mLastUpdateCalendar = new GregorianCalendar();
            mCurrentCalendar = new GregorianCalendar();

            mBurnMode = SharedPrefManager.getInstance(getApplicationContext()).getBurnMode();

        }

        @Override
        public void onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            super.onDestroy();
        }

        private Paint createTextPaintHours() {
            Paint paint = new Paint();
            paint.setTypeface(BOLD_TYPEFACE);
            paint.setAntiAlias(true);
            return paint;
        }

        private Paint createTextPaintLight() {
            Paint paint = new Paint();
            paint.setTypeface(Typeface.createFromAsset(getAssets(), "roboto_light.ttf"));
            paint.setAntiAlias(true);
            return paint;
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);

            if (visible) {
                registerReceiver();
                mCurrentCalendar = new GregorianCalendar();
            } else {
                unregisterReceiver();
            }

            updateTimer();
        }

        private void registerReceiver() {
            if (mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = true;
            IntentFilter filter = new IntentFilter(Intent.ACTION_TIMEZONE_CHANGED);
            MyWatchFace.this.registerReceiver(mTimeZoneReceiver, filter);
        }

        private void unregisterReceiver() {
            if (!mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = false;
            MyWatchFace.this.unregisterReceiver(mTimeZoneReceiver);
        }

        @Override
        public void onApplyWindowInsets(WindowInsets insets) {
            super.onApplyWindowInsets(insets);

            // Load resources that have alternate values for round watches.
            Resources resources = MyWatchFace.this.getResources();
            boolean isRound = insets.isRound();
            float textSize = resources.getDimension(isRound
                    ? R.dimen.digital_text_size_round : R.dimen.digital_text_size);

            float textSizeDate = resources.getDimension(R.dimen.digital_text_size_date);

            mTextPaintHours.setTextSize(textSize);
            mTextPaintMinutes.setTextSize(textSize);
            mTextPaintDate.setTextSize(textSizeDate);

            // Chin size
            mSpacingBottom = insets.getSystemWindowInsetBottom();

            if (mSpacingBottom == 0) {
                if (insets.isRound()) {
                    mSpacingBottom = (int) resources.getDimension(R.dimen.spacing_bottom_round);
                } else {
                    mSpacingBottom = (int) resources.getDimension(R.dimen.spacing_bottom_square);
                }
            } else {
                mSpacingBottom += 5;
            }

        }

        @Override
        public void onTimeTick() {
            super.onTimeTick();
            invalidate();
        }

        @Override
        public void onAmbientModeChanged(boolean inAmbientMode) {
            super.onAmbientModeChanged(inAmbientMode);
            if (mAmbient != inAmbientMode) {
                mAmbient = inAmbientMode;
                invalidate();
            }

            updateTimer();
        }

        @Override
        public void onTapCommand(int tapType, int x, int y, long eventTime) {
            if (!mTimerRunning) {
                switch (tapType) {
                    case TAP_TYPE_TOUCH:
                        // The user has started touching the screen.
                        break;
                    case TAP_TYPE_TOUCH_CANCEL:
                        // The user has started a different gesture or otherwise cancelled the tap.
                        break;
                    case TAP_TYPE_TAP:
                        // The user has completed the tap gesture.
                        mTimerRunning = true;
                        mTimer = new Timer();
                        mTimer.schedule(new AnimTapTask(this, RESOURCES[mCurrentResourceIndex].mTwiceAnim, getApplicationContext()), 0, 300);
                        break;
                }
            }
        }

        @Override
        public void onDraw(Canvas canvas, Rect bounds) {

            mCurrentCalendar = new GregorianCalendar();

            final ResourceCollection resourceCollection = getResourceCollection();

            final SharedPrefManager sharedPrefManager = SharedPrefManager.getInstance(getApplicationContext());

            mWidth = bounds.width();
            mHeight = bounds.height();

            Rect dest = new Rect(0, 0, mWidth, mHeight);
            Paint paint = new Paint();
            paint.setFilterBitmap(true);

            Bitmap bitmap;

            // Draw the background.
            if (mAmbient) {
                paint.setColor(ContextCompat.getColor(MyWatchFace.this, android.R.color.black));

                int pictureAmbient;
                if (sharedPrefManager.getBurnMode()) {
                    pictureAmbient = resourceCollection.mPictureBurn;
                } else {
                    pictureAmbient = resourceCollection.mPictureAmbient;
                }
                if (mCurrentAmbientBitmap == null) {
                    mCurrentAmbientBitmap = Bitmap.createScaledBitmap(BitmapFactory.decodeResource(getResources(), pictureAmbient), mWidth, mHeight, true);
                }

                bitmap = mCurrentAmbientBitmap;

                final int colorWhite = ContextCompat.getColor(MyWatchFace.this, android.R.color.white);
                mTextPaintHours.setColor(colorWhite);
                mTextPaintMinutes.setColor(colorWhite);

            } else {

                paint.setColor(ContextCompat.getColor(MyWatchFace.this, resourceCollection.mBackgroundColor));

                if (mCurrentBitmap == null) {
                    mCurrentBitmap = Bitmap.createScaledBitmap(BitmapFactory.decodeResource(getResources(), resourceCollection.mPicture), mWidth, mHeight, true);
                }

                bitmap = mCurrentBitmap;

                mTextPaintHours.setColor(ContextCompat.getColor(MyWatchFace.this, resourceCollection.mHoursColor));
                mTextPaintMinutes.setColor(ContextCompat.getColor(MyWatchFace.this, resourceCollection.mMinutesColor));
                mTextPaintDate.setColor(ContextCompat.getColor(MyWatchFace.this, resourceCollection.mMinutesColor));

            }

            canvas.drawRect(dest, paint);

            canvas.drawBitmap(bitmap, -(mWidth / 6), 0, null);

            int hoursValue = mCurrentCalendar.get(Calendar.HOUR_OF_DAY);
            if (!sharedPrefManager.getTimeFormat24() && hoursValue != 12) {
                hoursValue = mCurrentCalendar.get(Calendar.HOUR);
            }

            String hours = String.format("%02d", hoursValue);
            canvas.drawText(hours, mWidth * 0.63f, mHeight * 0.5f, mTextPaintHours);

            String minutes = String.format("%02d", mCurrentCalendar.get(Calendar.MINUTE));
            canvas.drawText(minutes, mWidth * 0.63f, mHeight * 0.67f, mTextPaintMinutes);

            // Date
            final String dateFormat = sharedPrefManager.getDateFormat();
            if (!mAmbient && !TextUtils.isEmpty(dateFormat)) {
                Format formatter = new SimpleDateFormat(dateFormat);
                Date date = new Date();
                date.setTime(mCurrentCalendar.getTimeInMillis());
                drawDateCenterWidthBottom(canvas, mTextPaintDate, formatter.format(date).toUpperCase());
            }
        }

        private void drawDateCenterWidthBottom(Canvas canvas, Paint paint, String text) {
            int width = canvas.getClipBounds().width();
            int height = canvas.getClipBounds().height();
            Rect r = new Rect();
            paint.setTextAlign(Paint.Align.LEFT);
            paint.getTextBounds(text, 0, text.length(), r);
            float x = width / 2f - r.width() / 2f - r.left;
            float y = height - mSpacingBottom;
            canvas.drawText(text, x, y, paint);
        }

        private ResourceCollection getResourceCollection() {

            final SharedPrefManager sharedPrefManager = SharedPrefManager.getInstance(getApplicationContext());

            final int ewokFixed = sharedPrefManager.getEwokFixed();
            if (ewokFixed != -1) {
                if (ewokFixed != mCurrentResourceIndex) {
                    mCurrentAmbientBitmap = null;
                    mCurrentBitmap = null;
                }
                mCurrentResourceIndex = ewokFixed;
            } else {
                final long refreshTime = sharedPrefManager.getRefreshTime();

                GregorianCalendar testCalendar = new GregorianCalendar();
                testCalendar.setTimeInMillis(mLastUpdateCalendar.getTimeInMillis() + refreshTime);
                if (testCalendar.before(mCurrentCalendar) || mLastUpdateCalendar.after(mCurrentCalendar)) {
                    if (mCurrentResourceIndex == RESOURCES.length - 1) {
                        mCurrentResourceIndex = 0;
                    } else {
                        mCurrentResourceIndex++;
                    }
                    mLastUpdateCalendar = new GregorianCalendar();
                    mCurrentAmbientBitmap = null;
                    mCurrentBitmap = null;
                }
            }

            final boolean burnMode = sharedPrefManager.getBurnMode();
            if (burnMode != mBurnMode) {
                mCurrentAmbientBitmap = null;
                mBurnMode = burnMode;
            }

            return RESOURCES[mCurrentResourceIndex];
        }

        /**
         * Starts the {@link #mUpdateTimeHandler} timer if it should be running and isn't currently
         * or stops it if it shouldn't be running but currently is.
         */
        private void updateTimer() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            if (shouldTimerBeRunning()) {
                mUpdateTimeHandler.sendEmptyMessage(MSG_UPDATE_TIME);
            }
        }

        /**
         * Returns whether the {@link #mUpdateTimeHandler} timer should be running. The timer should
         * only run when we're visible and in interactive mode.
         */
        private boolean shouldTimerBeRunning() {
            return isVisible() && !isInAmbientMode();
        }

        /**
         * Handle updating the time periodically in interactive mode.
         */
        private void handleUpdateTimeMessage() {
            invalidate();
            if (shouldTimerBeRunning()) {
                long timeMs = System.currentTimeMillis();
                long delayMs = INTERACTIVE_UPDATE_RATE_MS
                        - (timeMs % INTERACTIVE_UPDATE_RATE_MS);
                mUpdateTimeHandler.sendEmptyMessageDelayed(MSG_UPDATE_TIME, delayMs);
            }
        }
    }

    /*
     * Static resources
     */

    private static final ResourceCollection[] RESOURCES = {
            new ResourceCollection(R.drawable.ewok1, R.drawable.ewok1b, false, R.drawable.ewok_ambient1, R.drawable.ewok_burn1, R.color.middlegrey, R.color.orange, R.color.white),
            new ResourceCollection(R.drawable.ewok2, R.drawable.ewok2b, true, R.drawable.ewok_ambient2, R.drawable.ewok_burn2, R.color.green, R.color.red, R.color.white),
            new ResourceCollection(R.drawable.ewok3, R.drawable.ewok3b, false, R.drawable.ewok_ambient3, R.drawable.ewok_burn3, R.color.yellow, R.color.green, R.color.darkgrey),
            new ResourceCollection(R.drawable.ewok4, R.drawable.ewok4b, false, R.drawable.ewok_ambient4, R.drawable.ewok_burn4, R.color.orange, R.color.darkgrey, R.color.white),
            new ResourceCollection(R.drawable.ewok5, R.drawable.ewok5b, true, R.drawable.ewok_ambient5, R.drawable.ewok_burn5, R.color.red, R.color.darkgrey, R.color.white),
            new ResourceCollection(R.drawable.ewok6, R.drawable.ewok6b, true, R.drawable.ewok_ambient6, R.drawable.ewok_burn6, R.color.green, R.color.beige, android.R.color.white)
    };

    private static class ResourceCollection {
        int mPicture;
        int mPictureTap;
        int mBackgroundColor;
        int mHoursColor;
        int mMinutesColor;
        int mPictureAmbient;
        int mPictureBurn;
        boolean mTwiceAnim;

        public ResourceCollection(int picture, int pictureTap, boolean twiceAnim, int pictureAmbient, int pictureBurn, int backgroundColor, int hoursColor, int minutesColor) {
            mPicture = picture;
            mPictureTap = pictureTap;
            mTwiceAnim = twiceAnim;
            mPictureAmbient = pictureAmbient;
            mPictureBurn = pictureBurn;
            mBackgroundColor = backgroundColor;
            mHoursColor = hoursColor;
            mMinutesColor = minutesColor;
        }

    }

}
