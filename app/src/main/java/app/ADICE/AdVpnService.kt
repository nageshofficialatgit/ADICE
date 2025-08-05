package app.ADICE

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.VpnService
import android.os.Handler
import android.os.Message
import android.support.v4.app.NotificationCompat
import android.support.v4.content.LocalBroadcastManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import android.os.Looper
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

/**
 * Enum representing VPN commands.
 */
enum class Command {
    START, STOP
}

/**
 * Exception thrown for VPN network errors.
 */
class VpnNetworkException(msg: String) : RuntimeException(msg)

// VPN status constants
const val VPN_STATUS_STARTING = 0
const val VPN_STATUS_RUNNING = 1
const val VPN_STATUS_STOPPING = 2
const val VPN_STATUS_WAITING_FOR_NETWORK = 3
const val VPN_STATUS_RECONNECTING = 4
const val VPN_STATUS_RECONNECTING_NETWORK_ERROR = 5
const val VPN_STATUS_STOPPED = 6

/**
 * Maps VPN status to string resource ID.
 */
fun vpnStatusToTextId(status: Int): Int = when(status) {
    VPN_STATUS_STARTING -> R.string.notification_starting
    VPN_STATUS_RUNNING -> R.string.notification_running
    VPN_STATUS_STOPPING -> R.string.notification_stopping
    VPN_STATUS_WAITING_FOR_NETWORK -> R.string.notification_waiting_for_net
    VPN_STATUS_RECONNECTING -> R.string.notification_reconnecting
    VPN_STATUS_RECONNECTING_NETWORK_ERROR -> R.string.notification_reconnecting_error
    VPN_STATUS_STOPPED -> R.string.notification_stopped
    else -> throw IllegalArgumentException("Invalid vpnStatus value ($status)")
}

const val VPN_MSG_STATUS_UPDATE = 0
const val VPN_MSG_NETWORK_CHANGED = 1

const val VPN_UPDATE_STATUS_INTENT = "app.ADICE.VPN_UPDATE_STATUS"
const val VPN_UPDATE_STATUS_EXTRA = "VPN_STATUS"

const val MIN_RETRY_TIME = 5
const val MAX_RETRY_TIME = 2*60

/**
 * Checks if VPN should start on boot and starts it if enabled.
 * @param context Application context
 */
fun checkStartVpnOnBoot(context: Context) {
    Log.i("BOOT", "Checking whether to start ad buster on boot")
    val pref = context.getSharedPreferences(context.getString(R.string.preferences_file_key), Context.MODE_PRIVATE)
    if (!pref.getBoolean(context.getString(R.string.vpn_enabled_key), false)) {
        return
    }
    if (VpnService.prepare(context) != null) {
        Log.i("BOOT", "VPN preparation not confirmed by user, changing enabled to false")
        pref.edit().putBoolean(context.getString(R.string.vpn_enabled_key), false).apply()
    }
    Log.i("BOOT", "Starting ad buster from boot")
    val intent = Intent(context, AdVpnService::class.java)
    intent.putExtra("COMMAND", Command.START.ordinal)
    intent.putExtra("NOTIFICATION_INTENT",
            PendingIntent.getActivity(context, 0,
                    Intent(context, MainActivity::class.java), 0))
    context.startService(intent)
}

/**
 * Foreground VPN service implementation.
 */
class AdVpnService : VpnService() {
    companion object {
        private const val TAG = "AdVpnService"
        private const val NOTIFICATION_CHANNEL_ID = "vpn_service_channel"
        /** Current VPN status. */
        var vpnStatus: Int = VPN_STATUS_STOPPED
    }

    // Map command ordinal to Command enum
    private val commandValue = mapOf(
        Command.START.ordinal to Command.START,
        Command.STOP.ordinal to Command.STOP
    )

    // Handler for processing messages on the main thread
    private val handler: Handler = Handler(Looper.getMainLooper()) {
        handleMessage(it)
    }

    // Thread handling VPN operations
    private var vpnThread: AdVpnThread = AdVpnThread(this) {
        handler.sendMessage(handler.obtainMessage(VPN_MSG_STATUS_UPDATE, it, 0))
    }

    // Receiver for connectivity changes
    private var connectivityChangedReceiver = broadcastReceiver { context, intent ->
        handler.sendMessage(handler.obtainMessage(VPN_MSG_NETWORK_CHANGED, intent))
    }

    // Notification builder for foreground service
    private lateinit var notificationBuilder: NotificationCompat.Builder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        notificationBuilder = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_vpn_notification)
            .setPriority(NotificationCompat.PRIORITY_MIN)
    }

    /**
     * Creates a notification channel for the VPN service (Android O+).
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "VPN Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        Log.i(TAG, "onStartCommand")
        when (commandValue[intent.getIntExtra("COMMAND", Command.START.ordinal)]) {
            Command.START -> startVpn(intent.getParcelableExtra("NOTIFICATION_INTENT"))
            Command.STOP -> stopVpn()
        }
        return Service.START_STICKY
    }

    /**
     * Updates the VPN status and notification.
     * @param status New VPN status
     */
    private fun updateVpnStatus(status: Int) {
        vpnStatus = status
        val notificationTextId = vpnStatusToTextId(status)
        notificationBuilder.setContentText(getString(notificationTextId))
        startForeground(10, notificationBuilder.build())
        val intent = Intent(VPN_UPDATE_STATUS_INTENT)
        intent.putExtra(VPN_UPDATE_STATUS_EXTRA, status)
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    /**
     * Starts the VPN and registers connectivity receiver.
     * @param notificationIntent PendingIntent for notification tap
     */
    private fun startVpn(notificationIntent: PendingIntent?) {
        val editPref = getSharedPreferences(getString(R.string.preferences_file_key), MODE_PRIVATE).edit()
        editPref.putBoolean(getString(R.string.vpn_enabled_key), true)
        editPref.apply()
        notificationBuilder.setContentTitle(getString(R.string.notification_title))
        notificationBuilder.setContentIntent(notificationIntent)
        updateVpnStatus(VPN_STATUS_STARTING)
        registerReceiver(connectivityChangedReceiver, IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION))
        restartVpnThread()
    }

    /**
     * Restarts the VPN thread.
     */
    private fun restartVpnThread() {
        vpnThread.stopThread()
        vpnThread.startThread()
    }

    /**
     * Stops the VPN thread.
     */
    private fun stopVpnThread() {
        vpnThread.stopThread()
    }

    /**
     * Waits for network before restarting VPN.
     */
    private fun waitForNetVpn() {
        stopVpnThread()
        updateVpnStatus(VPN_STATUS_WAITING_FOR_NETWORK)
    }

    /**
     * Attempts to reconnect the VPN.
     */
    private fun reconnect() {
        updateVpnStatus(VPN_STATUS_RECONNECTING)
        restartVpnThread()
    }

    /**
     * Stops the VPN and unregisters connectivity receiver.
     */
    private fun stopVpn() {
        val editPref = getSharedPreferences(getString(R.string.preferences_file_key), MODE_PRIVATE).edit()
        editPref.putBoolean(getString(R.string.vpn_enabled_key), false)
        editPref.apply()
        Log.i(TAG, "Stopping Service")
        stopVpnThread()
        try {
            unregisterReceiver(connectivityChangedReceiver)
        } catch (e: IllegalArgumentException) {
            Log.i(TAG, "Ignoring exception on unregistering receiver")
        }
        updateVpnStatus(VPN_STATUS_STOPPED)
        stopSelf()
    }

    override fun onDestroy() {
        Log.i(TAG, "Destroyed, shutting down")
        stopVpn()
    }

    /**
     * Handles messages from the handler.
     * @param message Message to handle
     * @return true if handled
     */
    fun handleMessage(message: Message?): Boolean {
        if (message == null) {
            return true
        }
        when (message.what) {
            VPN_MSG_STATUS_UPDATE -> updateVpnStatus(message.arg1)
            VPN_MSG_NETWORK_CHANGED -> connectivityChanged(message.obj as Intent)
            else -> throw IllegalArgumentException("Invalid message with what = ${message.what}")
        }
        return true
    }

    /**
     * Handles connectivity changes.
     * @param intent Connectivity change intent
     */
    fun connectivityChanged(intent: Intent) {
        if (intent.getIntExtra(ConnectivityManager.EXTRA_NETWORK_TYPE, 0) == ConnectivityManager.TYPE_VPN) {
            Log.i(TAG, "Ignoring connectivity changed for our own network")
            return
        }
        if (intent.action != ConnectivityManager.CONNECTIVITY_ACTION) {
            Log.e(TAG, "Got bad intent on connectivity changed " + intent.action)
        }
        if (intent.getBooleanExtra(ConnectivityManager.EXTRA_NO_CONNECTIVITY, false)) {
            Log.i(TAG, "Connectivity changed to no connectivity, wait for a network")
            waitForNetVpn()
        } else {
            Log.i(TAG, "Network changed, try to reconnect")
            reconnect()
        }
    }
}
