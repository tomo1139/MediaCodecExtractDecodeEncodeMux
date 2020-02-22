package develop.tomo1139.mediacodecextractdecodeencodemux.util

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.provider.MediaStore

object FilePickerUtil {
    fun showGallery(activity: Activity?, requestCode: Int): Boolean {
        activity ?: return false
        var intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
        intent.type = "video/*"
        try {
            activity.startActivityForResult(intent, requestCode)
        } catch (e: ActivityNotFoundException) {
            intent = Intent()
            intent.action = Intent.ACTION_OPEN_DOCUMENT
            intent.addCategory(Intent.CATEGORY_OPENABLE)
            intent.type = "video/*"
            try {
                activity.startActivityForResult(intent, requestCode)
            } catch (e: ActivityNotFoundException) {
                Logger.e("error")
                return false
            }
        }
        return true
    }

    fun getPath(context: Context, uri: Uri): String? {
        if ("content".equals(uri.scheme, ignoreCase = true)) {
            return getDataColumn(
                context,
                uri,
                null,
                null
            )
        }
        return null
    }

    private fun getDataColumn(context: Context, uri: Uri?, selection: String?, selectionArgs: Array<String>?): String? {
        var cursor: Cursor? = null
        val column = "_data"
        val projection = arrayOf(column)
        try {
            cursor = context.contentResolver.query(uri, projection, selection, selectionArgs, null)
            if (cursor?.moveToFirst() == true) {
                return cursor.getString(cursor.getColumnIndexOrThrow(column))
            }
        } finally {
            cursor?.close()
        }
        return null
    }
}