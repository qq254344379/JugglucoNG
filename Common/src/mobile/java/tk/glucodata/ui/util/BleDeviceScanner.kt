package tk.glucodata.ui.util

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import tk.glucodata.Applic
import tk.glucodata.Log

object BleDeviceScanner {
    private const val SCAN_DURATION = 10000L // 10 seconds
    private const val LOG_ID = "BleDeviceScanner"

    @SuppressLint("MissingPermission")
    fun scanForSibionics(): Flow<String> = callbackFlow {
        // Enforce permission check before interacting with Bluetooth adapter
        if (!Applic.mayscan()) {
            Log.e(LOG_ID, "No bluetooth scan permissions, aborting scan")
            close()
            return@callbackFlow
        }

        val bluetoothManager = Applic.app.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = bluetoothManager.adapter
        
        // Double check adapter exists and is enabled
        if (adapter == null || !adapter.isEnabled) {
            Log.e(LOG_ID, "Bluetooth adapter null or disabled")
            close()
            return@callbackFlow
        }

        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            Log.e(LOG_ID, "BluetoothLeScanner is null")
            close()
            return@callbackFlow
        }

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
            
        // Keep track of found devices to avoid duplicates in the stream
        val foundDevices = mutableSetOf<String>()

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult?) {
                result?.device?.let { device ->
                    val name = device.name
                    // Filter for devices starting with "P" as requested (common for Sibionics/SiBio)
                    if (name != null && name.startsWith("P") && !foundDevices.contains(name)) {
                        foundDevices.add(name)
                        trySend(name)
                    }
                }
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>?) {
                results?.forEach { result ->
                    val name = result.device.name
                     if (name != null && name.startsWith("P") && !foundDevices.contains(name)) {
                        foundDevices.add(name)
                        trySend(name)
                    }
                }
            }

            override fun onScanFailed(errorCode: Int) {
                Log.e(LOG_ID, "Scan failed with error code: $errorCode")
                close()
            }
        }

        try {
            Log.i(LOG_ID, "Starting BLE scan for Sibionics...")
            scanner.startScan(null, settings, callback)
        } catch (e: SecurityException) {
            Log.e(LOG_ID, "SecurityException during startScan: " + e.message)
            close()
            return@callbackFlow
        } catch (e: Exception) {
            Log.e(LOG_ID, "Exception during startScan: " + e.message)
            close()
            return@callbackFlow
        }

        val handler = Handler(Looper.getMainLooper())
        handler.postDelayed({
            try {
                if (Applic.mayscan() && adapter.isEnabled) {
                    scanner.stopScan(callback)
                }
            } catch (e: Exception) {
                Log.e(LOG_ID, "Error stopping scan: " + e.message)
            }
            close()
        }, SCAN_DURATION)

        awaitClose {
            try {
                if (Applic.mayscan() && adapter.isEnabled) {
                    scanner.stopScan(callback)
                }
            } catch (e: Exception) {
                Log.e(LOG_ID, "Error stopping scan in awaitClose: " + e.message)
            }
            handler.removeCallbacksAndMessages(null)
        }
    }
}
