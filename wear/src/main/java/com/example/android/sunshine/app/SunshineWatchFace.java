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

package com.example.android.sunshine.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.content.ContextCompat;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.util.Log;
import android.view.Gravity;
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
import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.DataItemBuffer;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.PutDataRequest;
import com.google.android.gms.wearable.Wearable;

import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;


/**
 * Digital watch face with seconds. In ambient mode, the seconds aren't displayed. On devices with
 * low-bit ambient mode, the text is drawn without anti-aliasing in ambient mode.
 */
public class SunshineWatchFace extends CanvasWatchFaceService {
    private static final Typeface NORMAL_TYPEFACE =
            Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL);
    private static final Typeface BOLD_TYPEFACE =
            Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD);
    private static final String DEGREE  = "\u00b0";

    /**
     * Update rate in milliseconds for interactive mode. We update once a second since seconds are
     * displayed in interactive mode.
     */
    private static final long INTERACTIVE_UPDATE_RATE_MS = TimeUnit.SECONDS.toMillis(1);

    /**
     * Handler message id for updating the time periodically in interactive mode.
     */
    private static final int MSG_UPDATE_TIME = 0;

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

    private class Engine extends CanvasWatchFaceService.Engine implements
            DataApi.DataListener,
            GoogleApiClient.ConnectionCallbacks,
            GoogleApiClient.OnConnectionFailedListener {
        final Handler mUpdateTimeHandler = new EngineHandler(this);
        boolean mRegisteredTimeZoneReceiver = false;
        Paint mBackgroundPaint;
        Paint mHourPaint;
        Paint mMinutePaint;
        Paint mSecondsPaint;
        Paint mDatePaint;
        Paint mSeparatorPaint;
        Paint mMaxPaint;
        Paint mMinPaint;
        Paint mIconPaint;
        Bitmap icon;
        Bitmap BWIcon;
        boolean mAmbient;
        Calendar mCalendar;
        final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mCalendar.setTimeZone(TimeZone.getDefault());
                invalidate();
            }
        };
        float mTimeYOffset;
        float mDateYOffset;
        float mSeparatorYOffset;
        float mSeparatorLength;
        float mTempOffset;
        private GoogleApiClient mGoogleApiClient;

        private static final String weatherPath = "/weather";
        private static final String iconKey = "icon";
        private static final String minKey = "min";
        private static final String maxKey = "max";

        private String minWeather = "0";
        private String maxWeather = "0";
        private long lastUpdate = 0;

        /**
         * Whether the display supports fewer bits for each color in ambient mode. When true, we
         * disable anti-aliasing in ambient mode.
         */
        boolean mLowBitAmbient;

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);

            mGoogleApiClient = new GoogleApiClient.Builder(getApplicationContext())
                    .addApi(Wearable.API)
                    .addConnectionCallbacks(this)
                    .addOnConnectionFailedListener(this)
                    .build();
            mGoogleApiClient.connect();

            setWatchFaceStyle(new WatchFaceStyle.Builder(SunshineWatchFace.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_SHORT)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setShowSystemUiTime(false)
                    .setAcceptsTapEvents(true)
                    .setHotwordIndicatorGravity(Gravity.TOP | Gravity.END)
                    .build());
            Resources resources = SunshineWatchFace.this.getResources();
            mTimeYOffset = resources.getDimension(R.dimen.time_y_offset);
            mDateYOffset = resources.getDimension(R.dimen.date_y_offset);
            mSeparatorYOffset = resources.getDimension(R.dimen.separator_y_offset);
            mSeparatorLength = resources.getDimension(R.dimen.separator_length);
            mTempOffset = resources.getDimension(R.dimen.temp_y_offset);

            icon = null;
            BWIcon = null;

            mBackgroundPaint = new Paint();
            mBackgroundPaint.setColor(ContextCompat.getColor(getApplicationContext(), R.color.background));

            mHourPaint = new Paint();
            mHourPaint.setTextSize(resources.getDimension(R.dimen.hour_size));
            mHourPaint.setTypeface(BOLD_TYPEFACE);
            mHourPaint.setColor(ContextCompat.getColor(getApplicationContext(), R.color.time_color));
            mHourPaint.setAntiAlias(true);

            mMinutePaint = new Paint();
            mMinutePaint.setTextSize(resources.getDimension(R.dimen.minute_size));
            mMinutePaint.setTypeface(NORMAL_TYPEFACE);
            mMinutePaint.setColor(ContextCompat.getColor(getApplicationContext(), R.color.time_color));
            mMinutePaint.setAntiAlias(true);

            mSecondsPaint = new Paint();
            mSecondsPaint.setTextSize(resources.getDimension(R.dimen.second_size));
            mSecondsPaint.setTypeface(NORMAL_TYPEFACE);
            mSecondsPaint.setColor(ContextCompat.getColor(getApplicationContext(), R.color.time_color));
            mSecondsPaint.setAntiAlias(true);

            mDatePaint = new Paint();
            mDatePaint.setTextSize(resources.getDimension(R.dimen.date_size));
            mDatePaint.setTypeface(NORMAL_TYPEFACE);
            mDatePaint.setColor(ContextCompat.getColor(getApplicationContext(), R.color.date_color));
            mDatePaint.setAntiAlias(true);

            mSeparatorPaint = new Paint();
            mSeparatorPaint.setColor(ContextCompat.getColor(getApplicationContext(), R.color.separator_color));
            mSeparatorPaint.setAntiAlias(true);

            mMaxPaint = new Paint();
            mMaxPaint.setTextSize(resources.getDimension(R.dimen.max_size));
            mMaxPaint.setTypeface(BOLD_TYPEFACE);
            mMaxPaint.setColor(ContextCompat.getColor(getApplicationContext(), R.color.max_color));
            mMaxPaint.setAntiAlias(true);

            mMinPaint = new Paint();
            mMinPaint.setTextSize(resources.getDimension(R.dimen.min_size));
            mMinPaint.setTypeface(NORMAL_TYPEFACE);
            mMinPaint.setColor(ContextCompat.getColor(getApplicationContext(), R.color.min_color));
            mMinPaint.setAntiAlias(true);

            mIconPaint = new Paint();
            mIconPaint.setAntiAlias(true);

            mCalendar = Calendar.getInstance();
        }

        @Override
        public void onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            Wearable.DataApi.removeListener(mGoogleApiClient, this);
            mGoogleApiClient.disconnect();
            super.onDestroy();
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

            // Load resources that have alternate values for round watches.
            Resources resources = SunshineWatchFace.this.getResources();
            boolean isRound = insets.isRound();
            // TODO : apply round vs square differences
            if (isRound) {
                mHourPaint.setTextSize(resources.getDimension(R.dimen.hour_size_round));
                mMinutePaint.setTextSize(resources.getDimension(R.dimen.minute_size_round));
                mSecondsPaint.setTextSize(resources.getDimension(R.dimen.second_size_round));
                mDatePaint.setTextSize(resources.getDimension(R.dimen.date_size_round));
                mMaxPaint.setTextSize(resources.getDimension(R.dimen.max_size_round));
                mMinPaint.setTextSize(resources.getDimension(R.dimen.min_size_round));
                mTimeYOffset = resources.getDimension(R.dimen.time_y_offset_round);
                mDateYOffset = resources.getDimension(R.dimen.date_y_offset_round);
                mSeparatorYOffset = resources.getDimension(R.dimen.separator_y_offset_round);
                mSeparatorLength = resources.getDimension(R.dimen.separator_length_round);
                mTempOffset = resources.getDimension(R.dimen.temp_y_offset_round);
            }
            //mTimeXOffset = resources.getDimension(isRound
            //        ? R.dimen.hour_x_offset : R.dimen.hour_x_offset);
//            float textSize = resources.getDimension(isRound
//                    ? R.dimen.digital_text_size_round : R.dimen.digital_text_size);
//
//            mTextPaint.setTextSize(textSize);
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
                    mHourPaint.setAntiAlias(!inAmbientMode);
                    mMinutePaint.setAntiAlias(!inAmbientMode);
                    mSecondsPaint.setAntiAlias(!inAmbientMode);
                    mDatePaint.setAntiAlias(!inAmbientMode);
                    mSeparatorPaint.setAntiAlias(!inAmbientMode);
                    mMaxPaint.setAntiAlias(!inAmbientMode);
                    mMinPaint.setAntiAlias(!inAmbientMode);
                    mIconPaint.setAntiAlias(!inAmbientMode);
                }
                if (inAmbientMode) {
                    mDatePaint.setColor(ContextCompat.getColor(getApplicationContext(), R.color.white));
                    mSeparatorPaint.setColor(ContextCompat.getColor(getApplicationContext(), R.color.white));
                    mMaxPaint.setColor(ContextCompat.getColor(getApplicationContext(), R.color.white));
                    mMinPaint.setColor(ContextCompat.getColor(getApplicationContext(), R.color.white));
                } else {
                    mDatePaint.setColor(ContextCompat.getColor(getApplicationContext(), R.color.date_color));
                    mSeparatorPaint.setColor(ContextCompat.getColor(getApplicationContext(), R.color.separator_color));
                    mMaxPaint.setColor(ContextCompat.getColor(getApplicationContext(), R.color.max_color));
                    mMinPaint.setColor(ContextCompat.getColor(getApplicationContext(), R.color.min_color));
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
                    // TODO: Add code to handle the tap gesture.
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

            // Draw H:MM am/pm in ambient mode or H:MM:SS in interactive mode.
            long now = System.currentTimeMillis();
            mCalendar.setTimeInMillis(now);
            String hour = String.format(Locale.ENGLISH, "%d", mCalendar.get(Calendar.HOUR));
            String min = String.format(Locale.ENGLISH, "%02d", mCalendar.get(Calendar.MINUTE));
            String sec = String.format(Locale.ENGLISH, "%02d", mCalendar.get(Calendar.SECOND));
            String ampm = mCalendar.get(Calendar.AM_PM) == 0 ? "AM" : "PM";
            String[] timeStrings;
            Paint[] paints;
            if (isInAmbientMode()) {
                timeStrings = new String[]{hour, ":", min, " " + ampm};
                paints = new Paint[]{mHourPaint, mMinutePaint, mMinutePaint, mSecondsPaint, mSecondsPaint};
            } else {
                timeStrings = new String[]{hour, ":", min, ":", sec};
                paints = new Paint[]{mHourPaint, mMinutePaint, mMinutePaint, mSecondsPaint, mSecondsPaint};
            }
            int total = 0;
            for (int i = 0; i < timeStrings.length; i++) {
                total += paints[i].measureText(timeStrings[i]);
            }
            int xOffset = bounds.width() - total;
            xOffset = xOffset / 2;
            int offset = 0;
            for (int i = 0; i < timeStrings.length; i++) {
                canvas.drawText(timeStrings[i], xOffset + offset, mTimeYOffset, paints[i]);
                offset += paints[i].measureText(timeStrings[i]);
            }

            // draw day, date
            int daynum = mCalendar.get(Calendar.DAY_OF_WEEK);
            String day;
            switch (daynum) {
                case Calendar.SUNDAY:
                    day = "SUN";
                    break;
                case Calendar.MONDAY:
                    day = "MON";
                    break;
                case Calendar.TUESDAY:
                    day = "TUE";
                    break;
                case Calendar.WEDNESDAY:
                    day = "WED";
                    break;
                case Calendar.THURSDAY:
                    day = "THUR";
                    break;
                case Calendar.FRIDAY:
                    day = "FRI";
                    break;
                case Calendar.SATURDAY:
                    day = "SAT";
                    break;
                default:
                    day = "day error";
                    break;
            }
            int monthnum = mCalendar.get(Calendar.MONTH);
            String month;
            switch (monthnum) {
                case Calendar.JANUARY:
                    month = "JAN";
                    break;
                case Calendar.FEBRUARY:
                    month = "FEB";
                    break;
                case Calendar.MARCH:
                    month = "MAR";
                    break;
                case Calendar.APRIL:
                    month = "APR";
                    break;
                case Calendar.MAY:
                    month = "MAY";
                    break;
                case Calendar.JUNE:
                    month = "JUN";
                    break;
                case Calendar.JULY:
                    month = "JUL";
                    break;
                case Calendar.AUGUST:
                    month = "AUG";
                    break;
                case Calendar.SEPTEMBER:
                    month = "SEP";
                    break;
                case Calendar.OCTOBER:
                    month = "OCT";
                    break;
                case Calendar.NOVEMBER:
                    month = "NOV";
                    break;
                case Calendar.DECEMBER:
                    month = "DEC";
                    break;
                default:
                    month = "month error";
                    break;
            }

            String dayofmonth = String.format(Locale.ENGLISH, "%d", mCalendar.get(Calendar.DAY_OF_MONTH));
            String year = String.format(Locale.ENGLISH, "%d", mCalendar.get(Calendar.YEAR));
            String dateText = day + ", " + month + " " + dayofmonth + " " + year;
            xOffset = (int) (bounds.width() - mDatePaint.measureText(dateText));
            xOffset = xOffset / 2;
            canvas.drawText(dateText, xOffset, mDateYOffset, mDatePaint);
            int dateOffsetSave = xOffset;

            xOffset = (int) (bounds.width() - mSeparatorLength);
            xOffset = xOffset / 2;
            canvas.drawLine(xOffset, mSeparatorYOffset, xOffset + mSeparatorLength, mSeparatorYOffset, mSeparatorPaint);

            String maxTemp = maxWeather + DEGREE;
            String minTemp = minWeather + DEGREE;
            xOffset = (int) (mMaxPaint.measureText(maxTemp.substring(0, maxTemp.length() - 1)) / 2);
            xOffset = (bounds.width() / 2) - xOffset;
            canvas.drawText(maxTemp, xOffset, mTempOffset, mMaxPaint);
            canvas.drawText(" " + minTemp, xOffset + mMaxPaint.measureText(maxTemp), mTempOffset, mMinPaint);
            if (icon != null) {
                Bitmap toDraw = null;
                if (isInAmbientMode()) {
                    toDraw = BWIcon;
                } else {
                    toDraw = icon;
                }
                canvas.drawBitmap(toDraw, new Rect(0, 0, icon.getWidth(), icon.getHeight()),
                        new Rect(dateOffsetSave - 25, (int) mTempOffset - 50, dateOffsetSave + 50, (int) mTempOffset + 25),
                        mIconPaint);
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
                long delayMs = INTERACTIVE_UPDATE_RATE_MS
                        - (timeMs % INTERACTIVE_UPDATE_RATE_MS);
                mUpdateTimeHandler.sendEmptyMessageDelayed(MSG_UPDATE_TIME, delayMs);
            }
        }

        @Override
        public void onConnected(@Nullable Bundle bundle) {
            Wearable.DataApi.addListener(mGoogleApiClient, this);
            Wearable.DataApi.getDataItems(mGoogleApiClient, new Uri.Builder().scheme(PutDataRequest.WEAR_URI_SCHEME).path(weatherPath).build()).setResultCallback(new ResultCallback<DataItemBuffer>() {
                @Override
                public void onResult(@NonNull DataItemBuffer dataItems) {
                    for (DataItem dataItem : dataItems) {
                        DataMap dataMap = DataMapItem.fromDataItem(dataItem).getDataMap();
                        maxWeather = String.format(Locale.ENGLISH, "%d", (int) Math.round(dataMap.getDouble(maxKey)));
                        minWeather = String.format(Locale.ENGLISH, "%d", (int) Math.round(dataMap.getDouble(minKey)));
                        if (dataMap.getAsset(iconKey) != null) {
                            loadBitmapFromAssetThenUpdateWeather(dataMap, dataMap.getAsset(iconKey));
                        } else {
                            updateWeather(dataMap.getDouble(maxKey), dataMap.getDouble(minKey));
                        }
                    }
                    dataItems.release();
                }
            });
        }

        @Override
        public void onConnectionSuspended(int i) {

        }

        @Override
        public void onConnectionFailed(@NonNull ConnectionResult connectionResult) {

        }

        @Override
        public void onDataChanged(DataEventBuffer dataEventBuffer) {
            for (DataEvent event : dataEventBuffer) {
                if (event.getType() == DataEvent.TYPE_CHANGED) {
                    DataItem item = event.getDataItem();
                    if (item.getUri().getPath().equals(weatherPath)) {
                        DataMap dataMap = DataMapItem.fromDataItem(item).getDataMap();
                        Asset asset = dataMap.getAsset(iconKey);
                        if (asset != null) {
                            loadBitmapFromAssetThenUpdateWeather(dataMap, dataMap.getAsset(iconKey));
                        } else {
                            updateWeather(dataMap.getDouble(maxKey), dataMap.getDouble(minKey));
                        }
                    }
                }
            }
            dataEventBuffer.release();
        }

        private void updateWeather(double max, double min) {
            maxWeather = String.format(Locale.ENGLISH, "%d", (int) Math.round(max));
            minWeather = String.format(Locale.ENGLISH, "%d", (int) Math.round(min));
            lastUpdate = System.currentTimeMillis();
            invalidate();
        }

        public void loadBitmapFromAssetThenUpdateWeather(final DataMap dataMap, Asset asset) {
            if (asset == null) {
                throw new IllegalArgumentException("Asset must be non-null");
            }
            // convert asset into a file descriptor and block until it's ready
            Wearable.DataApi.getFdForAsset(
                    mGoogleApiClient, asset).setResultCallback(new ResultCallback<DataApi.GetFdForAssetResult>() {
                @Override
                public void onResult(@NonNull DataApi.GetFdForAssetResult getFdForAssetResult) {
                    InputStream assetInputStream = getFdForAssetResult.getInputStream();
                    if (assetInputStream == null) {
                        Log.w("WATCHFACE", "Requested an unknown Asset.");
                        //return null;
                    }
                    // decode the stream into a bitmap
                    icon = BitmapFactory.decodeStream(assetInputStream);
                    makeBWIcon();
                    updateWeather(dataMap.getDouble(maxKey, Double.parseDouble(maxWeather)), dataMap.getDouble(minKey, Double.parseDouble(minWeather)));
                }
            });
        }

        private void makeBWIcon() {
            BWIcon = Bitmap.createBitmap(
                    icon.getWidth(),
                    icon.getHeight(),
                    Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(BWIcon);
            Paint grayPaint = new Paint();
            ColorMatrix colorMatrix = new ColorMatrix();
            colorMatrix.setSaturation(0);
            ColorMatrixColorFilter filter = new ColorMatrixColorFilter(colorMatrix);
            grayPaint.setColorFilter(filter);
            canvas.drawBitmap(icon, 0, 0, grayPaint);
        }
    }
}
