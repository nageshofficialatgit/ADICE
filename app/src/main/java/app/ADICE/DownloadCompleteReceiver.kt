package app.ADICE

import androidx.core.app.JobIntentService
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log

/**
 * BroadcastReceiver to handle download completion events.
 */
class DownloadCompleteReceiver : BroadcastReceiver() {
    companion object {
        const val TAG = "DownloadCompleteReceiver"
    }

    /**
     * Called when a download completes. Installs the update if the reference matches.
     * @param context Application context
     * @param intent Broadcast intent
     */
    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "onReceive()")
        if (intent.action == DownloadManager.ACTION_DOWNLOAD_COMPLETE) {
            val prefs = context.getSharedPreferences("myPref", Context.MODE_PRIVATE)
            val downloadReference = prefs.getLong("downloadReference", 0)
            if (downloadReference > 0) {
                val uri = getUriFromDownloadReference(context, intent, downloadReference)
                Log.d(TAG, "Calling startActionInstallUpdate() with uri: ${uri}")
                UpdateService.startActionInstallUpdate(context, uri)
                // Clear shared preferences, we don't need the download reference anymore
                prefs.edit().clear().apply()
            }
        }
    }

    /**
     * Gets the URI for the downloaded file from DownloadManager.
     * @param context Application context
     * @param intent Broadcast intent
     * @param downloadReference Download reference ID
     * @return Uri of the downloaded file
     */
    private fun getUriFromDownloadReference(context: Context, intent: Intent, downloadReference: Long): Uri {
        val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        var uriString: String? = null
        if (intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1) == downloadReference) {
            // Extract update file path and install it
            val query = DownloadManager.Query()
            query.setFilterById(downloadReference)
            val cursor = downloadManager.query(query)
            if (cursor != null && cursor.moveToFirst()) {
                val columnIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                if (DownloadManager.STATUS_SUCCESSFUL == cursor.getInt(columnIndex)) {
                    // Prefer COLUMN_LOCAL_URI for modern Android
                    val localUriIndex = cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)
                    uriString = cursor.getString(localUriIndex)
                }
            }
            cursor?.close()
        }
        return if (uriString != null) Uri.parse(uriString) else Uri.EMPTY
    }
}
