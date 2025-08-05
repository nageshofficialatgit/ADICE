package app.ADICE

import android.app.*
import android.content.Intent
import android.content.IntentFilter
import android.net.VpnService
import android.os.Bundle
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import android.util.Log
import android.widget.Toast
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.DialogFragment
import android.app.PendingIntent
import android.app.AlertDialog
import net.hockeyapp.android.CrashManager
import net.hockeyapp.android.CrashManagerListener
import net.hockeyapp.android.utils.Util

/**
 * Maps VPN status to toggle button level.
 */
fun vpnStatusToToggleLevel(status: Int): Int = when(status) {
    VPN_STATUS_STOPPED -> 0
    VPN_STATUS_RUNNING -> 2
    else -> 1
}

/**
 * Determines if VPN should stop based on status.
 */
fun vpnStatusShouldStop(status: Int): Boolean = when(status) {
    VPN_STATUS_STOPPED -> false
    else -> true
}

/**
 * Main activity for controlling the VPN service.
 */
class MainActivity : AppCompatActivity() {
    companion object {
        private const val TAG = "MainActivity"
    }

    // Receivers for VPN and update status
    private val vpnServiceBroadcastReceiver = broadcastReceiver { context, intent ->
        val strId = intent.getIntExtra(VPN_UPDATE_STATUS_EXTRA, R.string.notification_stopped)
        updateStatus(strId)
    }
    private val updateServiceBroadcastReceiver = broadcastReceiver { context, intent ->
        UpdateDialogFragment(intent).show(supportFragmentManager, "update")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.form)
        // Replace deprecated synthetic with findViewById or ViewBinding
        val vpnToggle = findViewById<ImageView>(R.id.vpn_toggle)
        val textStatus = findViewById<TextView>(R.id.text_status)
        vpnToggle.setOnClickListener {
            if (vpnStatusShouldStop(AdVpnService.vpnStatus)) {
                Log.i(TAG, "Attempting to disconnect")
                val intent = Intent(this, AdVpnService::class.java)
                intent.putExtra("COMMAND", Command.STOP.ordinal)
                startService(intent)
            } else {
                Log.i(TAG, "Attempting to connect")
                val intent = VpnService.prepare(this)
                if (intent != null) {
                    startActivityForResult(intent, 0)
                } else {
                    onActivityResult(0, RESULT_OK, null)
                }
            }
        }
    }

    /**
     * Updates the status text and toggle button.
     * @param status VPN status
     */
    private fun updateStatus(status: Int) {
        val textStatus = findViewById<TextView>(R.id.text_status)
        val vpnToggle = findViewById<ImageView>(R.id.vpn_toggle)
        textStatus.text = getString(vpnStatusToTextId(status))
        val level = vpnStatusToToggleLevel(status)
        vpnToggle.setImageLevel(level)
    }

    override fun onActivityResult(request: Int, result: Int, data: Intent?) {
        super.onActivityResult(request, result, data)
        if (result == RESULT_OK) {
            val intent = Intent(this, AdVpnService::class.java)
            intent.putExtra("COMMAND", Command.START.ordinal)
            intent.putExtra("NOTIFICATION_INTENT",
                PendingIntent.getActivity(this, 0,
                        Intent(this, MainActivity::class.java), 0))
            startService(intent)
        }
    }

    override fun onPause() {
        super.onPause()
        LocalBroadcastManager.getInstance(this).unregisterReceiver(vpnServiceBroadcastReceiver)
        LocalBroadcastManager.getInstance(this).unregisterReceiver(updateServiceBroadcastReceiver)
    }

    override fun onResume() {
        super.onResume()
        // TODO: Replace HockeyApp with Firebase Crashlytics or App Center
        updateStatus(AdVpnService.vpnStatus)
        LocalBroadcastManager.getInstance(this)
                .registerReceiver(vpnServiceBroadcastReceiver, IntentFilter(VPN_UPDATE_STATUS_INTENT))
        LocalBroadcastManager.getInstance(this)
                .registerReceiver(updateServiceBroadcastReceiver, IntentFilter(UPDATE_AVAILABLE_INTENT))
    }

    /**
     * Dialog fragment for showing update information.
     */
    inner class UpdateDialogFragment(intent: Intent) : DialogFragment() {
        private val versionInfo = intent.getParcelableExtra<VersionInfo>(UpdateService.EXTRA_NEW_VERSION_INFO)!!
        override fun onCreateDialog(savedInstanceState: Bundle?): AlertDialog {
            val builder = AlertDialog.Builder(requireActivity())
            builder.setTitle(R.string.update_message)
            builder.setMessage(versionInfo.releaseData)
            builder.setPositiveButton(R.string.update_dialog_positive_button) { dialog, id ->
                Log.i(TAG, "Installing available update.")
                UpdateService.startActionDownloadUpdate(this@MainActivity, versionInfo.downloadUrl)
            }
            builder.setNegativeButton(R.string.update_dialog_negative_button) { dialog, id ->
                Log.i(TAG, "Skipping available update.")
            }
            return builder.create()
        }
    }
}