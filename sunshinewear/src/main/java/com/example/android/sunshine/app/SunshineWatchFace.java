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
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.text.format.Time;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.WindowInsets;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.wearable.Asset;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.Wearable;

import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

/**
 * Digital watch face with seconds. In ambient mode, the seconds aren't displayed. On devices with
 * low-bit ambient mode, the text is drawn without anti-aliasing in ambient mode.
 */
public class SunshineWatchFace extends CanvasWatchFaceService {

    private final String TAG = SunshineWatchFace.class.getSimpleName();

    private static final Typeface NORMAL_TYPEFACE =
            Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL);

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

    private class Engine extends CanvasWatchFaceService.Engine implements DataApi.DataListener,
            GoogleApiClient.ConnectionCallbacks, GoogleApiClient.OnConnectionFailedListener {

        final Handler mUpdateTimeHandler = new EngineHandler(this);
        boolean mRegisteredTimeZoneReceiver = false;
        Paint mBackgroundPaint;
        Paint mTextPaint;
        boolean mAmbient;
        Time mTime;
        float mXOffset;
        float mYOffset;

        // Sunshine stuff
        long TIMEOUT_MS = 1000;
        // Data
        private GoogleApiClient mGoogleApiClient;
        Calendar mCalendar;
        Asset mWeatherIDAsset;
        Bitmap mWeatherIcon;
        String mHighTemperature;
        String mLowTemperature;
        // Draw resources
        Paint mTempHighPaint;
        Paint mTempLowPaint;
        Paint mDatePaint;
        float mDateYOffset;
        float mTimeYOffset;
        float mSeparatorYOffset;
        float mTemperaturesYOffset;
        float mIconYOffset;
        int mCenterX;
        int mScreenHeight;
        int mScreenWidth;
        int mLeftBound;
        int mRightBound;



        /**
         * Whether the display supports fewer bits for each color in ambient mode. When true, we
         * disable anti-aliasing in ambient mode.
         */
        boolean mLowBitAmbient;

        final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mTime.clear(intent.getStringExtra("time-zone"));
                mTime.setToNow();
                mCalendar.setTimeZone(TimeZone.getDefault());
                invalidate();
            }
        };

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);
            Log.d(TAG, "onCreate()");

            mGoogleApiClient = new GoogleApiClient.Builder(SunshineWatchFace.this)
                    .addApi(Wearable.API)
                    .addConnectionCallbacks(this)
                    .addOnConnectionFailedListener(this)
                    .build();

            // http://stackoverflow.com/questions/34444088/how-do-i-transfer-an-android-asset-without-blocking-the-ui-thread
            //retrieveDeviceNode();

            setWatchFaceStyle(new WatchFaceStyle.Builder(SunshineWatchFace.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_VARIABLE)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setShowSystemUiTime(false)
                    .build());

            Resources resources = SunshineWatchFace.this.getResources();
            mYOffset = resources.getDimension(R.dimen.digital_y_offset);

            mBackgroundPaint = new Paint();
            mBackgroundPaint.setColor(resources.getColor(R.color.background));

            mTextPaint = new Paint();
            mTextPaint = createTextPaint(resources.getColor(R.color.digital_text));

            mTime = new Time();

            mCalendar = Calendar.getInstance();

            // Sunshine stuff
            mDatePaint = createTextPaint(Color.WHITE);
            int tempHighColor = resources.getColor(R.color.temp_high_text);
            int tempLowColor = resources.getColor(R.color.temp_low_text);
            mTempHighPaint = createTextPaint(tempHighColor);
            mTempLowPaint = createTextPaint(tempLowColor);

            // Offsets for Y axis
            mDateYOffset = resources.getDimension(R.dimen.digital_date_y_offset);
            mTimeYOffset = resources.getDimension(R.dimen.digital_time_y_offset);
            mTemperaturesYOffset = resources.getDimension(R.dimen.digital_temperatures_y_offset);
            mIconYOffset = resources.getDimension(R.dimen.digital_icon_y_offset);
        }

        @Override
        public void onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            super.onDestroy();
        }

        private Paint createTextPaint(int textColor) {
            Paint paint = new Paint();
            paint.setColor(textColor);
            paint.setTypeface(NORMAL_TYPEFACE);
            paint.setAntiAlias(true);
            return paint;
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);
            Log.d(TAG, "onVisibilityChanged()");

            if (visible) {
                registerReceiver();
                mGoogleApiClient.connect();
                Log.d(TAG, "onVisivilityChanged(): Connected!");

                // Update time zone in case it changed while we weren't visible.
                mTime.clear(TimeZone.getDefault().getID());
                mTime.setToNow();
            } else {
                unregisterReceiver();
                Wearable.DataApi.removeListener(mGoogleApiClient, this);
                mGoogleApiClient.disconnect();
                Log.d(TAG, "onVisivilityChanged(): Removed Listener and disconnected!");
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
            mXOffset = resources.getDimension(isRound
                    ? R.dimen.digital_x_offset_round : R.dimen.digital_x_offset);
            float textSize = resources.getDimension(isRound
                    ? R.dimen.digital_text_size_round : R.dimen.digital_text_size);

            mTextPaint.setTextSize(textSize);

            // Sunshine stuff
            mTempHighPaint.setTextSize(resources.getDimension(R.dimen.temp_text_size));
            mTempLowPaint.setTextSize(resources.getDimension(R.dimen.temp_text_size));
            mDatePaint.setTextSize(resources.getDimension(R.dimen.date_text_size));
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
                    mTextPaint.setAntiAlias(!inAmbientMode);
                }
            }
            invalidate();
            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        @Override
        public void onDraw(Canvas canvas, Rect bounds) {
            mCenterX = bounds.centerX();
            mScreenHeight = bounds.height();
            mScreenWidth = bounds.width();
            mLeftBound = bounds.left;
            mRightBound = bounds.right;

            Log.d(TAG, "onDraw(): Dimensions (h,w,lb,rb): "
                    + mScreenHeight + ", " + mScreenWidth + ", "
                    + mLeftBound + ", " + mRightBound);

            // Draw the background.
            if (isInAmbientMode()) {
                drawAmbient(canvas);
            } else {
                canvas.drawRect(0, 0, bounds.width(), bounds.height(), mBackgroundPaint);
                drawInteractive(canvas);
            }
            mTime.setToNow();
            drawTime(canvas);
            drawDate(canvas);
        }

        private void drawAmbient(Canvas canvas) {
            canvas.drawColor(Color.BLACK);
            if (mHighTemperature != null && mLowTemperature != null && mWeatherIcon != null) {
                Paint ambientPaint = createTextPaint(Color.WHITE);

                canvas.drawText(mHighTemperature,
                        (mScreenWidth / 4) - (mTempHighPaint.measureText(mHighTemperature) / 2),
                        (mScreenHeight / 2) + mTemperaturesYOffset,
                        ambientPaint);
                canvas.drawText(mLowTemperature,
                        ((mScreenWidth / 4) * 3) - (mTempLowPaint.measureText(mLowTemperature) / 2),
                        (mScreenHeight / 2) + mTemperaturesYOffset,
                        ambientPaint);
            }
        }

        private void drawInteractive(Canvas canvas) {
            if (mHighTemperature != null && mLowTemperature != null && mWeatherIcon != null) {

                canvas.drawText(mHighTemperature,
                        (mScreenWidth / 4) - (mTempHighPaint.measureText(mHighTemperature) / 2),
                        (mScreenHeight / 2) + mTemperaturesYOffset,
                        mTempHighPaint);
                canvas.drawText(mLowTemperature,
                        ((mScreenWidth / 4) * 3) - (mTempLowPaint.measureText(mLowTemperature) / 2),
                        (mScreenHeight / 2) + mTemperaturesYOffset,
                        mTempLowPaint);
                // Can I scale the bitmap???
                canvas.drawBitmap(mWeatherIcon,
                        (mScreenWidth / 2) - (mWeatherIcon.getWidth() / 2),
                        (mScreenHeight / 2) + mIconYOffset,
                        null);
            }
        }

        private void drawDate(Canvas canvas) {
            mCalendar.setTimeInMillis(System.currentTimeMillis());
            String formatDate = new SimpleDateFormat("E, dd MMM yyyy")
                    .format(mCalendar.getTime());

            float dateOffset = mDatePaint.measureText(formatDate)/2;
            Log.d(TAG, "Drawing date with X offset: " + dateOffset);
            canvas.drawText(formatDate,
                    (mScreenWidth / 2) - dateOffset,
                    (mScreenHeight / 2) - mDateYOffset,
                    mDatePaint);

            // Sticking in a separator mid-way to make it less jarring to look at.
            mSeparatorYOffset = mScreenHeight / 2;
            canvas.drawLine(mCenterX - 40,
                    mSeparatorYOffset, mCenterX + 40,
                    mSeparatorYOffset,
                    createTextPaint(Color.WHITE));
        }

        private void drawTime(Canvas canvas) {
            String timeText = mAmbient
                    ? String.format("%d:%02d", mTime.hour, mTime.minute)
                    : String.format("%d:%02d:%02d", mTime.hour, mTime.minute, mTime.second);

            float timeOffset = mTextPaint.measureText(timeText)/2;
            Log.d(TAG, "Drawing time with X offset: " + timeOffset);
            canvas.drawText(timeText,
                    mCenterX - timeOffset,
                    (mScreenHeight / 2) - mTimeYOffset,
                    mTextPaint);
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
        public void onConnected(Bundle bundle) {
            //super.onResume();
            Log.d(TAG, "onConnected()");
            Wearable.DataApi.addListener(mGoogleApiClient, this);
        }

        @Override
        public void onConnectionSuspended(int i) {
            Log.d(TAG, "onConnectionSuspended()");
        }

        @Override
        public void onDataChanged(DataEventBuffer dataEventBuffer) {
            Log.d(TAG, "onDataChanged()");

            // http://developer.android.com/training/wearables/data-layer/data-items.html
            for (DataEvent event : dataEventBuffer) {
                if (event.getType() == DataEvent.TYPE_CHANGED) {
                    // DataItem changed
                    DataItem item = event.getDataItem();
                    if (item.getUri().getPath().compareTo("/weather-details") == 0) {
                        DataMap dataMap = DataMapItem.fromDataItem(item).getDataMap();

                        // Grab data from phone
                        mHighTemperature = dataMap.getString("high_temp");
                        mLowTemperature = dataMap.getString("low_temp");
                        mWeatherIDAsset = dataMap.getAsset("weather_id");
                        new GetAssetTask().execute(mWeatherIDAsset);
                        //long mTime = dataMap.getLong("time");

                        Log.d(TAG, "Received: high: "
                                + mHighTemperature + ", low: "
                                + mLowTemperature + ", bitmap: "
                                + mWeatherIDAsset);

                    } else if (event.getType() == DataEvent.TYPE_DELETED) {
                        // DataItem deleted
                    }
                    invalidate();
                }
            }
        }

        // http://developer.android.com/training/wearables/data-layer/assets.html
        private class GetAssetTask extends AsyncTask<Asset, Void, Bitmap> {

            @Override
            protected Bitmap doInBackground(Asset... assets) {
                if (assets != null && assets[0] != null) {

                    // Check that there's an active client
                    ConnectionResult result =
                            mGoogleApiClient.blockingConnect(TIMEOUT_MS, TimeUnit.MILLISECONDS);
                    if (!result.isSuccess()) {
                        return null;
                    }
                    // convert asset into a file descriptor and block until it's ready
                    InputStream assetInputStream = Wearable.DataApi.getFdForAsset(
                            mGoogleApiClient, assets[0]).await().getInputStream();
                    // mGoogleApiClient.disconnect();
                    if (assetInputStream == null) {
                        Log.w(TAG, "Requested an unknown Asset.");
                        return null;
                    }
                    // decode the stream into a bitmap
                    return BitmapFactory.decodeStream(assetInputStream);
                }
                throw new IllegalArgumentException("Asset must be non-null");
            }

            @Override
            protected void onPostExecute(Bitmap bitmap) {
                mWeatherIcon = bitmap;
            }
        }

        @Override
        public void onConnectionFailed(ConnectionResult connectionResult) {
            Log.d(TAG, "onConnectionFailed(): Failed to connect! " + connectionResult.getErrorMessage());
        }
    }
}
