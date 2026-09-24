/*
 * Copyright © 2013–2016 Michael von Glasow.
 * 
 * This file is part of LSRN Tools.
 *
 * LSRN Tools is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * LSRN Tools is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with LSRN Tools.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.vonglasow.michael.satstat;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.vonglasow.michael.satstat.utils.PermissionHelper;
import com.vonglasow.michael.satstat.utils.WifiCapabilities;

import android.Manifest;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.preference.PreferenceManager;
import androidx.core.content.ContextCompat;
import android.util.Log;
import android.widget.Toast;

public class GpsEventReceiver extends BroadcastReceiver {
	public static final String TAG = GpsEventReceiver.class.getSimpleName();

	/**
	 * A dummy intent called when a location update is received.
	 * <p>
	 * Some devices will refresh AGPS data only when the GPS is accessed. Thus,
	 * in order to force an AGPS update, we request location updates from the
	 * GPS and immediately remove updates again. However, in order to request
	 * location updates we need to supply either a LocationListener or a
	 * PendingIntent to which the location updates will be delivered. This
	 * Intent is used to create that PendingIntent. When this Intent is
	 * received, it will be ignored.
	 */
	public static final String LOCATION_UPDATE_RECEIVED = "com.vonglasow.michael.satstat.LOCATION_UPDATE_RECEIVED";
	private static Intent mAgpsIntent = new Intent(Const.AGPS_DATA_EXPIRED);
	private static Intent mLocationIntent = new Intent(LOCATION_UPDATE_RECEIVED);
	
	@Override
	public void onReceive(Context context, Intent intent) {
		SharedPreferences sharedPref = PreferenceManager.getDefaultSharedPreferences(context);
		
		// some logic to use the pre-1.7 setting KEY_PREF_UPDATE_WIFI as a
		// fallback if KEY_PREF_UPDATE_NETWORKS is not set
		Set<String> fallbackUpdateNetworks = new HashSet<String>();
		if (sharedPref.getBoolean(Const.KEY_PREF_UPDATE_WIFI, false)) {
			fallbackUpdateNetworks.add(Const.KEY_PREF_UPDATE_NETWORKS_WIFI);
		}
		Set<String> updateNetworks = sharedPref.getStringSet(Const.KEY_PREF_UPDATE_NETWORKS, fallbackUpdateNetworks);
		
		if (intent.getAction().equals(Const.GPS_ENABLED_CHANGE) || intent.getAction().equals(Const.GPS_ENABLED_CHANGE)) {
			//FIXME: why are we checking for the same intent twice? Should on of them be GPS_FIX_CHANGE?
			// an application has connected to GPS or disconnected from it, check if notification needs updating
			boolean notifyFix = sharedPref.getBoolean(Const.KEY_PREF_NOTIFY_FIX, false);
			boolean notifySearch = sharedPref.getBoolean(Const.KEY_PREF_NOTIFY_SEARCH, false);
			if (notifyFix || notifySearch) {
				// Always start the service. getRunningServices() is deprecated and unreliable
				// on newer Android versions. The service will manage its own lifecycle.
				Intent startServiceIntent = new Intent(context, PasvLocListenerService.class);
				startServiceIntent.setAction(intent.getAction());
				startServiceIntent.putExtras(intent.getExtras());
				context.startService(startServiceIntent);
			}
		} else if (intent.getAction().equals(WifiManager.NETWORK_STATE_CHANGED_ACTION) 
				&& updateNetworks.contains(Const.KEY_PREF_UPDATE_NETWORKS_WIFI)) {
			// change in WiFi connectivity, check if we are connected and need to refresh AGPS
			//FIXME: KEY_PREF_UPDATE_WIFI as fallback only
			NetworkInfo netinfo = intent.getParcelableExtra(WifiManager.EXTRA_NETWORK_INFO);
			if (netinfo == null) return;
			if (!netinfo.isConnected()) return;
			//Toast.makeText(context, "WiFi is connected", Toast.LENGTH_SHORT).show();
			Log.i(this.getClass().getSimpleName(), "WiFi is connected");
			refreshAgps(context, true, false);
		} else if ((intent.getAction().equals(ConnectivityManager.CONNECTIVITY_ACTION )) ||
				(intent.getAction().equals(Const.AGPS_DATA_EXPIRED))) {
			// change in network connectivity or AGPS expiration timer fired
			// Note: CONNECTIVITY_ACTION is not delivered to apps targeting API 29+
			// but AGPS_DATA_EXPIRED still works (alarm-based trigger)
			boolean isAgpsExpired = false;
			if (intent.getAction().equals(Const.AGPS_DATA_EXPIRED)) {
				Log.i(this.getClass().getSimpleName(), "AGPS data expired, checking available networks");
				isAgpsExpired = true;
			}
			ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
			NetworkInfo netinfo = null;
			try {
				netinfo = cm.getActiveNetworkInfo();
			} catch (SecurityException e) {
				Log.w(this.getClass().getSimpleName(), "Cannot get active network info: " + e.getMessage());
				return;
			}
			if (netinfo == null) return;
			if (!netinfo.isConnected()) return;
			String type;
			if ((netinfo.getType() < ConnectivityManager.TYPE_MOBILE_MMS) || (netinfo.getType() > ConnectivityManager.TYPE_MOBILE_HIPRI)) {
				type = Integer.toString(netinfo.getType());
			} else {
				// specific mobile data connections will be treated as TYPE_MOBILE
				type = Const.KEY_PREF_UPDATE_NETWORKS_MOBILE;
			}
			if (!updateNetworks.contains(type)) return;
			if (!isAgpsExpired)
				Log.i(this.getClass().getSimpleName(), "Network of type " + netinfo.getTypeName() + " is connected");
			// Enforce the update interval if we were called by a network event
			// but not if we were called by a timer, because in that case the
			// check has already been done. (I am somewhat paranoid and don't
			// count on alarms not going off a few milliseconds too early.)
			refreshAgps(context, !isAgpsExpired, false);
		}
	}
	
	/**
	 * Refreshes AGPS data if necessary.
	 * 
	 * This method requests a refresh of the AGPS data. It optionally does so
	 * only after checking when the AGPS data was last refreshed and
	 * determining if it is stale by adding the refresh interval specified in
	 * the user preferences and comparing the result against the current time.
	 * If the result is less than current time, AGPS data is considered stale
	 * and a refresh is requested.
	 * 
	 * @param context A {@link Context} to be passed to {@link LocationManager}.
	 * @param enforceInterval If true, prevents updates when the interval has
	 * not yet expired. If false, updates are permitted at any time. This is to
	 * prevent race conditions if alarms fire off too early.
	 * @param wantFeedback Whether to display a toast informing the user about
	 * the success of the operation.
	 */
	private static final ExecutorService sExecutor = Executors.newSingleThreadExecutor();

	public static void refreshAgps(Context context, boolean enforceInterval, boolean wantFeedback) {
		SharedPreferences sharedPref = PreferenceManager.getDefaultSharedPreferences(context);
		long last = sharedPref.getLong(Const.KEY_PREF_UPDATE_LAST, 0);
		long freqDays = Long.parseLong(sharedPref.getString(Const.KEY_PREF_UPDATE_FREQ, "0"));
		long now = System.currentTimeMillis();
		if (enforceInterval && (last + freqDays * Const.MILLIS_PER_DAY > now)) return;
		//Log.d(GpsEventReceiver.class.getSimpleName(), String.format("refreshAgps, enforceInterval: %b, wantFeedback: %b", enforceInterval, wantFeedback));
		
		if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED)
			executeAgpsUpdate(context, mAgpsIntent, sharedPref, freqDays * Const.MILLIS_PER_DAY, wantFeedback);
		else {
			Log.i(TAG, "Requesting permissions to update AGPS data");
			PermissionHelper.requestPermissions((SatStatApplication) (context.getApplicationContext()),
					new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
					Const.PERM_REQUEST_LOCATION_NOTIFICATION,
					context.getString(R.string.notify_perm_title),
					context.getString(R.string.notify_perm_body),
					R.drawable.ic_security);
		}
	}
	
	private static void executeAgpsUpdate(final Context context, final Intent agpsIntent,
			final SharedPreferences sharedPref, final long freqMillis, final boolean wantFeedback) {
		sExecutor.execute(() -> {
			final Handler mainHandler = new Handler(Looper.getMainLooper());
			int result = doAgpsUpdate(context, agpsIntent, sharedPref, freqMillis);
			
			if (wantFeedback) {
				final String message = getAgpsMessage(context, result);
				if (!message.isEmpty()) {
					mainHandler.post(() -> Toast.makeText(context, message, Toast.LENGTH_SHORT).show());
				}
			}
		});
	}
	
	private static int doAgpsUpdate(Context context, Intent agpsIntent,
			SharedPreferences sharedPref, long freqMillis) {
		int nc = WifiCapabilities.getNetworkConnectivity();
		if (nc == WifiCapabilities.NETWORK_CAPTIVE_PORTAL) {
			Log.i(GpsEventReceiver.class.getSimpleName(), "Captive portal detected, cannot update AGPS data");
			return nc;
		} else if (nc == WifiCapabilities.NETWORK_ERROR) {
			Log.i(GpsEventReceiver.class.getSimpleName(), "No network available, cannot update AGPS data");
			return nc;
		}
		
		AlarmManager alm = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
		PendingIntent pi = PendingIntent.getBroadcast(context, 0, agpsIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
		alm.cancel(pi);

		LocationManager locman = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
		List<String> allProviders = locman.getAllProviders();
		PendingIntent tempIntent = PendingIntent.getBroadcast(context, 0, mLocationIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
		Log.i(GpsEventReceiver.class.getSimpleName(), "Requesting AGPS data update");
		try {
			if (allProviders.contains(LocationManager.GPS_PROVIDER))
				locman.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0, 0, tempIntent);
			locman.sendExtraCommand("gps", "force_xtra_injection", null);
			locman.sendExtraCommand("gps", "force_time_injection", null);
			locman.removeUpdates(tempIntent);
			
			SharedPreferences.Editor spEditor = sharedPref.edit();
			spEditor.putLong(Const.KEY_PREF_UPDATE_LAST, System.currentTimeMillis());
			spEditor.commit();
		} catch (SecurityException e) {
			Log.w(GpsEventReceiver.class.getSimpleName(), "Permissions not granted, cannot update AGPS data");
		}
		
		if (freqMillis > 0) {
			long next = System.currentTimeMillis() + freqMillis;
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
				alm.setExactAndAllowWhileIdle(AlarmManager.RTC, next, pi);
			} else {
				alm.set(AlarmManager.RTC, next, pi);
			}
			Log.i(GpsEventReceiver.class.getSimpleName(), String.format("Next update due %1$tY-%1$tm-%1$td %1$tH:%1$tM:%1$tS (after %2$d ms)", next, freqMillis));
		}

		return nc;
	}
	
	private static String getAgpsMessage(Context context, int result) {
		switch (result) {
		case WifiCapabilities.NETWORK_AVAILABLE:
			return context.getString(R.string.status_agps);
		case WifiCapabilities.NETWORK_CAPTIVE_PORTAL:
			return context.getString(R.string.status_agps_captive);
		case WifiCapabilities.NETWORK_ERROR:
			return context.getString(R.string.status_agps_error);
		default:
			return "";
		}
	}


}
