/*      This file is part of Juggluco, an Android app to receive and display         */
/*      glucose values from Freestyle Libre 2 and 3 sensors.                         */
/*                                                                                   */
/*      Copyright (C) 2021 Jaap Korthals Altes <jaapkorthalsaltes@gmail.com>         */
/*                                                                                   */
/*      Juggluco is free software: you can redistribute it and/or modify             */
/*      it under the terms of the GNU General Public License as published            */
/*      by the Free Software Foundation, either version 3 of the License, or         */
/*      (at your option) any later version.                                          */
/*                                                                                   */
/*      Juggluco is distributed in the hope that it will be useful, but              */
/*      WITHOUT ANY WARRANTY; without even the implied warranty of                   */
/*      MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.                         */
/*      See the GNU General Public License for more details.                         */
/*                                                                                   */
/*      You should have received a copy of the GNU General Public License            */
/*      along with Juggluco. If not, see <https://www.gnu.org/licenses/>.            */
/*                                                                                   */
/*      Fri Jan 27 15:31:05 CET 2023                                                 */


package tk.glucodata;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Bundle;

import static tk.glucodata.Log.doLog;

public class SendLikexDrip   {
    public static final String ACTION = "com.eveningoutpost.dexdrip.BgEstimate";
    private static final String EXTRA_BG_ESTIMATE = "com.eveningoutpost.dexdrip.Extras.BgEstimate";
    private static final String EXTRA_BG_SLOPE_NAME = "com.eveningoutpost.dexdrip.Extras.BgSlopeName";
   private static final String EXTRA_BG_SLOPE = "com.eveningoutpost.dexdrip.Extras.BgSlope";
    private static final String EXTRA_SENSOR_BATTERY = "com.eveningoutpost.dexdrip.Extras.SensorBattery";
private static final String EXTRA_SENSOR_STARTED_AT = "com.eveningoutpost.dexdrip.Extras.SensorStartedAt";
    private static final String EXTRA_TIMESTAMP = "com.eveningoutpost.dexdrip.Extras.Time";
   private static final String EXTRA_DATA_SOURCE_INFO = "com.eveningoutpost.dexdrip.Extras.SourceInfo";
private static final String LOG_ID="SendLikexDrip";
/*
private static int getBatteryLevel() { 
        final Intent batteryIntent = Applic.app.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        try {
            int level = batteryIntent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = batteryIntent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
            if (level == -1 || scale == -1) {
            Log.e(LOG_ID,"level="+level+" scale="+scale);
                return 50;
            }
            int perc= (int) (((float) level / (float) scale) * 100.0f);
        {if(doLog) {Log.i(LOG_ID,"BatteryLevel="+perc);};};
        return perc;
        } catch (Throwable error) {
            String mess=error!=null?error.getMessage():null;
            Log.e(LOG_ID,"getBatteryLevel "+mess);

            return 50;
            }   
    } */
private static Bundle mkGlucosebundle(ExchangeGlucosePayload payload,long sensorStartmsec) {
    return mkGlucosebundle(payload, sensorStartmsec, SensorSourceResolver.resolveXdripSourceInfo(payload.sensorId, payload.sensorGen));
}

private static Bundle mkGlucosebundle(ExchangeGlucosePayload payload,long sensorStartmsec, String sourceInfo) {
    Bundle extras = new Bundle();
    extras.putDouble(EXTRA_BG_ESTIMATE,payload.primaryMgdl);
    extras.putString(EXTRA_BG_SLOPE_NAME,payload.trendName);

    extras.putDouble(EXTRA_BG_SLOPE,(double)payload.trendRate/60000.0);
    extras.putLong(EXTRA_TIMESTAMP,payload.timeMillis);
    extras.putLong(EXTRA_SENSOR_STARTED_AT,sensorStartmsec);
    extras.putString(EXTRA_DATA_SOURCE_INFO, sourceInfo);
//        extras.putInt(EXTRA_SENSOR_BATTERY,100);
    return extras;
      }

/** Builds an xDrip-style broadcast whose SourceInfo is forced to "G6", so AAPS
 *  sees the reading as coming from a Dexcom G6 sensor. */
public static Intent buildG6Intent(ExchangeGlucosePayload payload, long sensorStartmsec) {
    Intent intent = new Intent(ACTION);
    intent.putExtras(mkGlucosebundle(payload, sensorStartmsec, Aapsv2Broadcast.SOURCE_INFO_G6));
    intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
    return intent;
}

/** Sends an already-built intent to every package in {@code receivers}, using
 *  the same per-package setPackage + sendBroadcast pattern as broadcastglucose. */
public static void sendTo(Intent intent, java.util.Collection<String> receivers) {
    final Context context = Applic.app;
    if (context == null)
        return;
    for (var name : receivers) {
        if (name != null) {
            intent.setPackage(name);
            context.sendBroadcast(intent);
            {if(doLog) {Log.i(LOG_ID,name);};};
            }
       }
    }

private static String[] names=null;
public static  void setreceivers() {
    names=Natives.xdripRecepters();
    }
static void broadcastglucose(ExchangeGlucosePayload payload,long sensorStartmsec) {
    if(names==null)
        return;
    {
    if(doLog) {Log.i(LOG_ID,"broadcastglucose "+payload.primaryMgdl+" sensor="+payload.sensorId);};};
    Intent intent = new Intent(ACTION);
    intent.putExtras(mkGlucosebundle(payload, sensorStartmsec));
    intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
    sendTo(intent, java.util.Arrays.asList(names));
    }
    
}
