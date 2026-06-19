package online.automint.app.web

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.MimeTypeMap
import android.webkit.URLUtil
import android.widget.Toast

class DownloadRouter(private val context: Context) : DownloadListener {

    override fun onDownloadStart(
        url: String?,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?,
        contentLength: Long,
    ) {
        val src = url ?: return
        val uri = runCatching { Uri.parse(src) }.getOrNull() ?: return
        if (uri.scheme != "https" && uri.scheme != "http") {
            return
        }

        val guessedName = URLUtil.guessFileName(src, contentDisposition, mimeType)
        val effectiveMime = mimeType
            ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(guessedName.substringAfterLast('.', ""))
            ?: "application/octet-stream"

        val cookies = CookieManager.getInstance().getCookie(src)

        val req = DownloadManager.Request(uri)
            .setTitle(guessedName)
            .setMimeType(effectiveMime)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, guessedName)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)

        if (!cookies.isNullOrEmpty()) req.addRequestHeader("Cookie", cookies)
        if (!userAgent.isNullOrEmpty()) {
            req.addRequestHeader("User-Agent", userAgent)
        } else {
            req.addRequestHeader("User-Agent", AppUserAgent.suffix)
        }

        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        runCatching { dm.enqueue(req) }
            .onSuccess { Toast.makeText(context, guessedName, Toast.LENGTH_SHORT).show() }
    }
}
