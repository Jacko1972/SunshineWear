package com.jacko1972.android.sunshinewear;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.AsyncTask;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.util.Log;
import android.widget.ImageView;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.wearable.Asset;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.Wearable;
import com.google.android.gms.wearable.WearableListenerService;

import java.io.InputStream;
import java.util.concurrent.TimeUnit;

public class AppListenerService extends WearableListenerService implements DataApi.DataListener, GoogleApiClient.ConnectionCallbacks, GoogleApiClient.OnConnectionFailedListener {

    private GoogleApiClient googleClient;
    private static final String TAG = "AppListenerService";

    @Override
    public void onCreate() {
        super.onCreate();
        googleClient = new GoogleApiClient.Builder(this)
                .addApi(Wearable.API)
                .addConnectionCallbacks(this)
                .addOnConnectionFailedListener(this)
                .build();

        googleClient.connect();
    }

    @Override
    public void onDataChanged(DataEventBuffer dataEventBuffer) {
        for (DataEvent event : dataEventBuffer) {
            if (event.getType() == DataEvent.TYPE_CHANGED) {
                DataMap map = DataMapItem.fromDataItem(event.getDataItem()).getDataMap();
                String dataMapPath = event.getDataItem().getUri().getPath();
                if (dataMapPath.equals(getString(R.string.sunshine_wear_data_path))) {
                    String lowTemp = map.getString(getString(R.string.low_temp_wear_string));
                    String highTemp = map.getString(getString(R.string.high_temp_wear_string));
                    Asset iconAsset = map.getAsset(getString(R.string.weather_icon_asset_string));
                    loadBitmapFromAsset(AppListenerService.this, iconAsset);
                    SunshineWatchFace.lowTemp = lowTemp + "°";
                    SunshineWatchFace.highTemp = highTemp + "°";
                }
            }
        }
    }
    public static void loadBitmapFromAsset(final Context context, final Asset asset) {
        if (asset == null) {
            throw new IllegalArgumentException("Asset must be non-null");
        }

        new AsyncTask<Asset, Void, Bitmap>() {
            @Override
            protected Bitmap doInBackground(Asset... assets) {
                GoogleApiClient googleApiClient = new GoogleApiClient.Builder(context)
                        .addApi(Wearable.API)
                        .build();
                ConnectionResult result = googleApiClient.blockingConnect(10000, TimeUnit.MILLISECONDS);
                if (!result.isSuccess()) {
                    return null;
                }

                // convert asset into a file descriptor and block until it's ready
                InputStream assetInputStream = Wearable.DataApi.getFdForAsset(
                        googleApiClient, assets[0]).await().getInputStream();
                googleApiClient.disconnect();

                if (assetInputStream == null) {
                    Log.w(TAG, "Requested an unknown Asset.");
                    return null;
                }

                // decode the stream into a bitmap
                return BitmapFactory.decodeStream(assetInputStream);
            }

            @Override
            protected void onPostExecute(Bitmap bitmap) {
                if (bitmap != null) {
                    SunshineWatchFace.weatherIcon = bitmap;
                }
            }
        }.execute(asset);
    }

//
//    private Bitmap loadBitmapFromAsset(Asset iconAsset) {
//        if (iconAsset == null) {
//            throw new IllegalArgumentException("Asset must be non-null");
//        }
//
//        ConnectionResult result = googleClient.blockingConnect(15, TimeUnit.SECONDS);
//        if (!result.isSuccess()) {
//            return null;
//        }
//        // convert asset into a file descriptor and block until it's ready
//        InputStream assetInputStream = Wearable.DataApi.getFdForAsset(googleClient, iconAsset).await().getInputStream();
//        googleClient.disconnect();
//
//        if (assetInputStream == null) {
//            Log.w(TAG, "Unknown Asset.");
//            return null;
//        }
//        // decode the stream into a bitmap
//        return BitmapFactory.decodeStream(assetInputStream);
//    }

    @Override
    public void onConnected(@Nullable Bundle bundle) {
        Wearable.DataApi.addListener(googleClient, this);
    }

    @Override
    public void onConnectionSuspended(int i) {
    }

    @Override
    public void onConnectionFailed(@NonNull ConnectionResult connectionResult) {
    }

}
