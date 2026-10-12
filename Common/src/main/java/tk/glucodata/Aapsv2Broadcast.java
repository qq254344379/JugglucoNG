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
import android.content.SharedPreferences;
import android.content.pm.PackageManager;

import java.util.HashSet;
import java.util.Set;

import static tk.glucodata.Log.doLog;

/**
 * Independent "AAPSv2 online build" broadcast switch.
 *
 * Unlike the native xDrip / Glucodata switches, this switch keeps its own
 * receiver lists in SharedPreferences: enabling it discovers receivers for
 * both broadcast formats at that moment and stores them under dedicated keys,
 * so it never touches the native receivers the other switches own.
 */
public class Aapsv2Broadcast {
    public static final String PREF_KEY = "aapsv2_broadcast_enabled";
    public static final String PREF_XDRIP_RECEIVERS = "aapsv2_broadcast_xdrip_receivers";
    public static final String PREF_GLUCODATA_RECEIVERS = "aapsv2_broadcast_glucodata_receivers";
    public static final String PREF_INTERVAL_KEY = "aapsv2_broadcast_interval_minutes";
    public static final String SOURCE_INFO_G6 = "G6 Native";
    private static final int DEFAULT_INTERVAL_MINUTES = 0;
    private static final int MAX_INTERVAL_MINUTES = 10;
    private static final String PREFS_FILE = "tk.glucodata_preferences";
    private static final String LOG_ID = "Aapsv2Broadcast";

    // Lazy minute gate: the timestamp of the last successfully sent reading
    // (payload time, not wall clock). Mirrors GlucoDataHandler's TransferTask
    // model - the next reading compares its own timestamp against this gate
    // and is sent once the interval has elapsed, so no timer is needed and a
    // missed minute simply rolls into the following reading.
    private static volatile long lastSendTime = 0L;

    private Aapsv2Broadcast() {}

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE);
    }

    public static boolean isEnabled(Context context) {
        return prefs(context).getBoolean(PREF_KEY, false);
    }

    /**
     * Turning the switch on discovers the receivers for both broadcast actions
     * right now and stores them under the dedicated keys. Turning it off only
     * clears the enabled flag; the discovered lists are kept for the next time.
     */
    public static void setEnabled(Context context, boolean enabled) {
        if (!enabled) {
            prefs(context).edit().putBoolean(PREF_KEY, false).apply();
            return;
        }
        final PackageManager pm = context.getPackageManager();
        prefs(context).edit()
                .putBoolean(PREF_KEY, true)
                .putStringSet(PREF_XDRIP_RECEIVERS, discoverReceivers(pm, SendLikexDrip.ACTION))
                .putStringSet(PREF_GLUCODATA_RECEIVERS, discoverReceivers(pm, JugglucoSend.ACTION))
                .apply();
    }

    private static Set<String> discoverReceivers(PackageManager pm, String action) {
        final Set<String> result = new HashSet<>();
        try {
            final Intent intent = new Intent(action);
            for (var info : pm.queryBroadcastReceivers(intent, 0)) {
                final String name = info.activityInfo != null ? info.activityInfo.packageName : null;
                if (name != null && !name.isEmpty()) {
                    result.add(name);
                }
            }
        } catch (Throwable th) {
            Log.stack(LOG_ID, "discoverReceivers " + action, th);
        }
        {if(doLog) {Log.i(LOG_ID, "discovered " + result.size() + " receivers for " + action);};};
        return result;
    }

    /**
     * Sync interval in minutes (0..10): 0 means send every reading in real
     * time, 10 means at most one broadcast per 10 minutes.
     */
    public static int getIntervalMinutes(Context context) {
        return prefs(context).getInt(PREF_INTERVAL_KEY, DEFAULT_INTERVAL_MINUTES);
    }

    public static void setIntervalMinutes(Context context, int minutes) {
        final int clamped = Math.max(0, Math.min(MAX_INTERVAL_MINUTES, minutes));
        prefs(context).edit().putInt(PREF_INTERVAL_KEY, clamped).apply();
    }

    /**
     * Sends one reading to every stored receiver of each format. The xDrip
     * style broadcast is built with SourceInfo forced to "G6"; the Glucodata
     * broadcast uses the regular glucodata.Minute intent.
     *
     * When the configured interval is greater than 0 the send is gated lazily
     * on the reading timestamps (GlucoDataHandler TransferTask model): the
     * reading is only broadcast once at least one interval has elapsed since
     * the last successfully sent reading. No timer/pending buffer is involved
     * - if a minute carries no reading the elapsed time simply accumulates
     * and the next reading crosses the gate. 0 keeps the real-time behaviour.
     */
    public static void send(ExchangeGlucosePayload payload, long sensorStartmsec, String serialNumber, int alarm) {
        final Context context = Applic.app;
        if (context == null)
            return;
        final int intervalMinutes = getIntervalMinutes(context);
        if (intervalMinutes > 0) {
            final long readingTime = payload.getTimeMillis();
            if (readingTime <= 0L || lastSendTime >= readingTime) {
                // Not newer than the last reading sent (or unusable time):
                // nothing to do, wait for the next reading.
                return;
            }
            if (readingTime - lastSendTime < intervalMinutes * 60_000L) {
                // Interval not reached yet: keep this reading as the candidate
                // for the next trigger; no timer needed, the next reading
                // re-enters this check.
                return;
            }
            // Due: send the current reading below and advance the gate on success.
        }
        if (doSend(context, payload, sensorStartmsec, serialNumber, alarm)) {
            lastSendTime = payload.getTimeMillis();
        }
    }

    private static boolean doSend(Context context, ExchangeGlucosePayload payload, long sensorStartmsec, String serialNumber, int alarm) {
        boolean sent = false;
        final SharedPreferences prefs = prefs(context);
        final Set<String> xdripReceivers = prefs.getStringSet(PREF_XDRIP_RECEIVERS, null);
        if (xdripReceivers != null && !xdripReceivers.isEmpty()) {
            SendLikexDrip.sendTo(SendLikexDrip.buildG6Intent(payload, sensorStartmsec), xdripReceivers);
            {if(doLog) {Log.i(LOG_ID, "xdrip send to " + xdripReceivers.size() + " receivers");};};
            sent = true;
        }
        final Set<String> glucodataReceivers = prefs.getStringSet(PREF_GLUCODATA_RECEIVERS, null);
        if (glucodataReceivers != null && !glucodataReceivers.isEmpty()) {
            JugglucoSend.sendTo(JugglucoSend.buildIntent(serialNumber, payload, alarm), glucodataReceivers);
            {if(doLog) {Log.i(LOG_ID, "glucodata send to " + glucodataReceivers.size() + " receivers");};};
            sent = true;
        }
        return sent;
    }
}
