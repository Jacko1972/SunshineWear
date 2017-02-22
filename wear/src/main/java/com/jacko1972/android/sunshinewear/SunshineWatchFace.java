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

package com.jacko1972.android.sunshinewear;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.text.TextPaint;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.WindowInsets;
import android.widget.Toast;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.wearable.Asset;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.PutDataMapRequest;
import com.google.android.gms.wearable.PutDataRequest;
import com.google.android.gms.wearable.Wearable;

import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

/**
 * Digital watch face with seconds. In ambient mode, the seconds aren't displayed. On devices with
 * low-bit ambient mode, the text is drawn without anti-aliasing in ambient mode.
 */
public class SunshineWatchFace extends CanvasWatchFaceService {

    private static final Typeface NORMAL_TYPEFACE = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL);
    private static final Typeface ITALIC_TYPEFACE = Typeface.create(Typeface.DEFAULT, Typeface.ITALIC);

    /**
     * Update rate in milliseconds for interactive mode. We update once a second since seconds are
     * displayed in interactive mode.
     */
    private static final long INTERACTIVE_UPDATE_RATE_MS = TimeUnit.SECONDS.toMillis(1);

    /**
     * Handler message id for updating the time periodically in interactive mode.
     */
    private static final int MSG_UPDATE_TIME = 0;

    public static String lowTemp = "0°";
    public static String highTemp = "0°";
    public static Bitmap weatherIcon = null;

    @Override
    public Engine onCreateEngine() {
        return new Engine();
    }

    private static class EngineHandler extends Handler {
        private final WeakReference<SunshineWatchFace.Engine> mWeakReference;

        public EngineHandler(SunshineWatchFace.Engine reference) {
            mWeakReference = new WeakReference<>(reference);
        }

        @Override
        public void handleMessage(Message msg) {
            SunshineWatchFace.Engine engine = mWeakReference.get();
            if (engine != null) {
                switch (msg.what) {
                    case MSG_UPDATE_TIME:
                        engine.handleUpdateTimeMessage();
                        break;
                }
            }
        }
    }

    private class Engine extends CanvasWatchFaceService.Engine implements GoogleApiClient.OnConnectionFailedListener, GoogleApiClient.ConnectionCallbacks{
        final Handler mUpdateTimeHandler = new EngineHandler(this);
        boolean mRegisteredTimeZoneReceiver = false;
        Paint mBackgroundPaint;
        Paint mDivider;
        TextPaint mTimePaint;
        TextPaint mDatePaint;
        TextPaint mLowPaint;
        TextPaint mHighPaint;
        TextPaint mErrorPaint;
        boolean mAmbient;
        Calendar mCalendar;
        private SimpleDateFormat timeFormat;
        private SimpleDateFormat ambientTimeFormat;
        private SimpleDateFormat dateFormat;
        private long currentTime;
        private static final String TAG = "Engine";
        final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mCalendar.setTimeZone(TimeZone.getDefault());
                invalidate();
            }
        };

        /**
         * Whether the display supports fewer bits for each color in ambient mode. When true, we
         * disable anti-aliasing in ambient mode.
         */
        boolean mLowBitAmbient;
        Resources resources;
        private GoogleApiClient googleClient;


        @SuppressWarnings("deprecation")
        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);

            setWatchFaceStyle(new WatchFaceStyle.Builder(SunshineWatchFace.this)
                    .setShowSystemUiTime(false)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .build());
            resources = SunshineWatchFace.this.getResources();

            Typeface DIGITAL_TYPEFACE = Typeface.createFromAsset(resources.getAssets(), "digital_7_mono.ttf");

            mBackgroundPaint = new Paint();
            mDivider = new Paint();
            mTimePaint = new TextPaint();
            mDatePaint = new TextPaint();
            mLowPaint = new TextPaint();
            mHighPaint = new TextPaint();
            mErrorPaint = new TextPaint();

            timeFormat = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
            ambientTimeFormat = new SimpleDateFormat("HH:mm", Locale.getDefault());
            dateFormat = new SimpleDateFormat("EEE, MMM dd yyyy", Locale.getDefault());

            mBackgroundPaint.setColor(resources.getColor(R.color.colorPrimary));
            mDivider.setColor(resources.getColor(R.color.colorAccent));
            mTimePaint = createTextPaint(resources.getColor(R.color.digital_text), 60, DIGITAL_TYPEFACE);
            mDatePaint = createTextPaint(resources.getColor(R.color.digital_text), 24, NORMAL_TYPEFACE);
            mLowPaint = createTextPaint(resources.getColor(R.color.light_grey_text), 40, NORMAL_TYPEFACE);
            mHighPaint = createTextPaint(resources.getColor(R.color.digital_text), 40, NORMAL_TYPEFACE);
            mErrorPaint = createTextPaint(resources.getColor(R.color.colorAccent), 30, ITALIC_TYPEFACE);

            mCalendar = Calendar.getInstance();

            googleClient = new GoogleApiClient.Builder(SunshineWatchFace.this)
                    .addApi(Wearable.API)
                    .addConnectionCallbacks(this)
                    .addOnConnectionFailedListener(this)
                    .build();

            googleClient.connect();
        }

        private void sendMessageForWeatherInfoUpdate() {
            PutDataMapRequest request = PutDataMapRequest.create(getString(R.string.sunshine_weather_update_path));
            DataMap map = request.getDataMap();
            map.putLong(resources.getString(R.string.time_stamp_string), System.currentTimeMillis());
            PutDataRequest dataRequest = request.asPutDataRequest();
            dataRequest.setUrgent();
            Wearable.DataApi.putDataItem(googleClient, dataRequest)
                    .setResultCallback(new ResultCallback<DataApi.DataItemResult>() {
                        @Override
                        public void onResult(@NonNull DataApi.DataItemResult dataItemResult) {
                            if (!dataItemResult.getStatus().isSuccess()) {
                                Log.d(TAG, ": " + dataItemResult.getStatus().getStatusMessage());
                            }
                        }
                    });
        }

        @Override
        public void onConnected(@Nullable Bundle bundle) {
            sendMessageForWeatherInfoUpdate();
        }

        @Override
        public void onConnectionSuspended(int i) {
        }

        @Override
        public void onConnectionFailed(@NonNull ConnectionResult connectionResult) {
        }

        @Override
        public void onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            super.onDestroy();
        }

        private TextPaint createTextPaint(int textColor, float textSize, Typeface typeface) {
            TextPaint textPaint = new TextPaint();
            textPaint.setColor(textColor);
            textPaint.setTypeface(typeface);
            textPaint.setTextSize(textSize);
            textPaint.setAntiAlias(true);
            return textPaint;
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);

            if (visible) {
                registerReceiver();
                // Update time zone in case it changed while we weren't visible.
                mCalendar.setTimeZone(TimeZone.getDefault());
                invalidate();
            } else {
                unregisterReceiver();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        private void registerReceiver() {
            if (mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = true;
            IntentFilter filter = new IntentFilter(Intent.ACTION_TIMEZONE_CHANGED);
            SunshineWatchFace.this.registerReceiver(mTimeZoneReceiver, filter);
        }

        private void unregisterReceiver() {
            if (!mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = false;
            SunshineWatchFace.this.unregisterReceiver(mTimeZoneReceiver);
        }

        @Override
        public void onApplyWindowInsets(WindowInsets insets) {
            super.onApplyWindowInsets(insets);

//            // Load resources that have alternate values for round watches.
//            Resources resources = SunshineWatchFace.this.getResources();
//            boolean isRound = insets.isRound();
//            mXOffset = resources.getDimension(isRound ? R.dimen.digital_x_offset_round : R.dimen.digital_x_offset);
//            float textSize = resources.getDimension(isRound ? R.dimen.digital_text_size_round : R.dimen.digital_text_size);
//
//            mTimePaint.setTextSize(textSize);
        }

        @Override
        public void onPropertiesChanged(Bundle properties) {
            super.onPropertiesChanged(properties);
            mLowBitAmbient = properties.getBoolean(PROPERTY_LOW_BIT_AMBIENT, false);
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
                if (mLowBitAmbient) {
                    mTimePaint.setAntiAlias(!inAmbientMode);
                    mDatePaint.setAntiAlias(!inAmbientMode);
                }
                invalidate();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        /**
         * Captures tap event (and tap type) and toggles the background color if the user finishes
         * a tap.
         */
        @Override
        public void onTapCommand(int tapType, int x, int y, long eventTime) {
            switch (tapType) {
                case TAP_TYPE_TOUCH:
                    // The user has started touching the screen.
                    break;
                case TAP_TYPE_TOUCH_CANCEL:
                    // The user has started a different gesture or otherwise cancelled the tap.
                    break;
                case TAP_TYPE_TAP:
                    // The user has completed the tap gesture.
                    sendMessageForWeatherInfoUpdate();
                    Toast.makeText(getApplicationContext(), R.string.message, Toast.LENGTH_SHORT)
                            .show();
                    break;
            }
            invalidate();
        }

        @Override
        public void onDraw(Canvas canvas, Rect bounds) {
            // Draw the background.
            if (isInAmbientMode()) {
                canvas.drawColor(Color.BLACK);
            } else {
                canvas.drawRect(0, 0, bounds.width(), bounds.height(), mBackgroundPaint);
            }
            long now = System.currentTimeMillis();
            mCalendar.setTimeInMillis(now);
            currentTime = mCalendar.getTimeInMillis();
            // Draw H:MM in ambient mode or H:MM:SS in interactive mode.

            Rect timeBounds = new Rect();
            Rect dateBounds = new Rect();
            Rect lowBounds = new Rect();
            Rect highBounds = new Rect();
            Rect errorBounds = new Rect();
            String time;
            String date;
            if (mAmbient) {
                time = ambientTimeFormat.format(currentTime);
                date = dateFormat.format(currentTime);
                mTimePaint.getTextBounds(time, 0, time.length(), timeBounds);
                mDatePaint.getTextBounds(date, 0, date.length(), dateBounds);
                int timeX = Math.abs(bounds.centerX() - timeBounds.centerX());
                int timeY = Math.abs(bounds.centerY() - 30);

                int dateX = Math.abs(bounds.centerX() - dateBounds.centerX());
                int dateY = Math.abs(bounds.centerY() + 30);

                canvas.drawText(time, timeX, timeY, mTimePaint);
                canvas.drawText(date, dateX, dateY, mDatePaint);
            } else {
                time = timeFormat.format(currentTime);
//                time = String.format(Locale.getDefault(), "%d:%02d:%02d", mCalendar.get(Calendar.HOUR), mCalendar.get(Calendar.MINUTE), mCalendar.get(Calendar.SECOND));

                date = dateFormat.format(currentTime);
                mTimePaint.getTextBounds(time, 0, time.length(), timeBounds);
                mDatePaint.getTextBounds(date, 0, date.length(), dateBounds);
                mLowPaint.getTextBounds(lowTemp, 0, lowTemp.length(), lowBounds);
                mHighPaint.getTextBounds(highTemp, 0, highTemp.length(), highBounds);

                int timeX = Math.abs(bounds.centerX() - timeBounds.centerX());
                int timeY = Math.abs(bounds.centerY() - dateBounds.height() - (timeBounds.height() / 2));

                int dateX = Math.abs(bounds.centerX() - dateBounds.centerX());
                int dateY = Math.abs(bounds.centerY() - dateBounds.centerY());

                canvas.drawText(time, timeX, timeY, mTimePaint);
                canvas.drawText(date, dateX, dateY, mDatePaint);
                canvas.drawLine(bounds.centerX() - 50, bounds.centerX() + 30, bounds.centerX() + 50, bounds.centerY() + 30, mDivider);

            /*
             * Display Weather information, check data has been updated from listener service
             */
                if (weatherIcon != null) {
                    //weatherIcon.setHeight(highBounds.height());
                    //weatherIcon.setWidth(highBounds.height());
                    canvas.drawBitmap(weatherIcon, bounds.centerX() - weatherIcon.getWidth() - 50, bounds.centerY() + dateBounds.height() + 15, null);
                    canvas.drawText(highTemp, bounds.centerX() - lowBounds.centerX(), bounds.centerY() + dateBounds.height() + 55, mHighPaint);
                    canvas.drawText(lowTemp, bounds.centerX() + 50, bounds.centerY() + dateBounds.height() + 55, mLowPaint);
                } else {
                    mErrorPaint.getTextBounds("Requesting...", 0, 13, errorBounds);
                    canvas.drawText("Requesting...", bounds.centerX() - errorBounds.centerX(), bounds.centerY() + 70, mErrorPaint);
                }
            }


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
                long delayMs = INTERACTIVE_UPDATE_RATE_MS - (timeMs % INTERACTIVE_UPDATE_RATE_MS);
                mUpdateTimeHandler.sendEmptyMessageDelayed(MSG_UPDATE_TIME, delayMs);
            }
        }
    }
}
