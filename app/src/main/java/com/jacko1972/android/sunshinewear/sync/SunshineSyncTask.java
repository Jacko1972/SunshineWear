/*
 * Copyright (C) 2016 The Android Open Source Project
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
package com.jacko1972.android.sunshinewear.sync;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.text.format.DateUtils;
import android.util.Log;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.PutDataMapRequest;
import com.google.android.gms.wearable.PutDataRequest;
import com.google.android.gms.wearable.Wearable;
import com.jacko1972.android.sunshinewear.R;
import com.jacko1972.android.sunshinewear.data.SunshinePreferences;
import com.jacko1972.android.sunshinewear.data.WeatherContract;
import com.jacko1972.android.sunshinewear.utilities.NetworkUtils;
import com.jacko1972.android.sunshinewear.utilities.NotificationUtils;
import com.jacko1972.android.sunshinewear.utilities.OpenWeatherJsonUtils;
import com.jacko1972.android.sunshinewear.utilities.SunshineWeatherUtils;

import java.io.ByteArrayOutputStream;
import java.net.URL;

public class SunshineSyncTask {

    private static final String TAG = "SunshineSyncTask";

    /**
     * Performs the network request for updated weather, parses the JSON from that request, and
     * inserts the new weather information into our ContentProvider. Will notify the user that new
     * weather has been loaded if the user hasn't been notified of the weather within the last day
     * AND they haven't disabled notifications in the preferences screen.
     *
     * @param context Used to access utility methods and the ContentResolver
     */
    synchronized public static void syncWeather(Context context) {

        try {
            /*
             * The getUrl method will return the URL that we need to get the forecast JSON for the
             * weather. It will decide whether to create a URL based off of the latitude and
             * longitude or off of a simple location as a String.
             */
            URL weatherRequestUrl = NetworkUtils.getUrl(context);

            /* Use the URL to retrieve the JSON */
            String jsonWeatherResponse = NetworkUtils.getResponseFromHttpUrl(weatherRequestUrl);

            /* Parse the JSON into a list of weather values */
            ContentValues[] weatherValues = OpenWeatherJsonUtils
                    .getWeatherContentValuesFromJson(context, jsonWeatherResponse);

            /*
             * In cases where our JSON contained an error code, getWeatherContentValuesFromJson
             * would have returned null. We need to check for those cases here to prevent any
             * NullPointerExceptions being thrown. We also have no reason to insert fresh data if
             * there isn't any to insert.
             */
            if (weatherValues != null && weatherValues.length != 0) {
                /* Get a handle on the ContentResolver to delete and insert data */
                ContentResolver sunshineContentResolver = context.getContentResolver();

                /* Delete old weather data because we don't need to keep multiple days' data */
                sunshineContentResolver.delete(
                        WeatherContract.WeatherEntry.CONTENT_URI,
                        null,
                        null);

                /* Insert our new weather data into Sunshine's ContentProvider */
                sunshineContentResolver.bulkInsert(
                        WeatherContract.WeatherEntry.CONTENT_URI,
                        weatherValues);

                /*
                 * Finally, after we insert data into the ContentProvider, determine whether or not
                 * we should notify the user that the weather has been refreshed.
                 */
                boolean notificationsEnabled = SunshinePreferences.areNotificationsEnabled(context);

                /*
                 * If the last notification was shown was more than 1 day ago, we want to send
                 * another notification to the user that the weather has been updated. Remember,
                 * it's important that you shouldn't spam your users with notifications.
                 */
                long timeSinceLastNotification = SunshinePreferences
                        .getEllapsedTimeSinceLastNotification(context);

                boolean oneDayPassedSinceLastNotification = false;

                if (timeSinceLastNotification >= DateUtils.DAY_IN_MILLIS) {
                    oneDayPassedSinceLastNotification = true;
                }

                /*
                 * We only want to show the notification if the user wants them shown and we
                 * haven't shown a notification in the past day.
                 */
                if (notificationsEnabled && oneDayPassedSinceLastNotification) {
                    NotificationUtils.notifyUserOfNewWeather(context);
                }

            /* If the code reaches this point, we have successfully performed our sync */

                /*
                 * Send data from first row of json to watch face
                 */

                sendWeatherDataToWatchFace(weatherValues[0], context);

            }

        } catch (Exception e) {
            /* Server probably invalid */
            e.printStackTrace();
        }
    }

    private static void sendWeatherDataToWatchFace(ContentValues json, Context context) {

        GoogleApiClient googleClient;
        googleClient = new GoogleApiClient.Builder(context)
                .addConnectionCallbacks(new GoogleApiClient.ConnectionCallbacks() {
                    @Override
                    public void onConnected(@Nullable Bundle bundle) {
                    }

                    @Override
                    public void onConnectionSuspended(int i) {
                    }
                })
                .addOnConnectionFailedListener(new GoogleApiClient.OnConnectionFailedListener() {
                    @Override
                    public void onConnectionFailed(@NonNull ConnectionResult connectionResult) {
                    }
                })
                .addApi(Wearable.API)
                .build();

        googleClient.connect();

        double maxTemp = (double) json.get(WeatherContract.WeatherEntry.COLUMN_MAX_TEMP);
        double minTemp = (double) json.get(WeatherContract.WeatherEntry.COLUMN_MIN_TEMP);
        int weatherId = (int) json.get(WeatherContract.WeatherEntry.COLUMN_WEATHER_ID);
        int weatherImageId = SunshineWeatherUtils.getSmallArtResourceIdForWeatherCondition(weatherId);

        Bitmap bitmap = BitmapFactory.decodeResource(context.getResources(), weatherImageId);
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream);
        byte[] imageArray = stream.toByteArray();

        PutDataMapRequest request = PutDataMapRequest.create(context.getString(R.string.sunshine_wear_data_path));
        DataMap map = request.getDataMap();
        map.putString(context.getString(R.string.high_temp_wear_string), String.valueOf(Math.round(maxTemp)));
        map.putString(context.getString(R.string.low_temp_wear_string), String.valueOf(Math.round(minTemp)));
        map.putByteArray(context.getString(R.string.weather_icon_array_string), imageArray);
        map.putLong(context.getString(R.string.time_stamp_string), System.currentTimeMillis());
        map.putBoolean(context.getString(R.string.set_refresh_bool_string), false);

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
}