package app.ADICE

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * BroadcastReceiver that listens for device boot completion and starts the VPN if enabled.
 */
class BootComplete : BroadcastReceiver() {

    /**
     * Called when the device finishes booting. Checks if VPN should start on boot.
     * @param context Application context
     * @param intent Broadcast intent
     */
    override fun onReceive(context: Context, intent: Intent) {
        checkStartVpnOnBoot(context)
    }
}
