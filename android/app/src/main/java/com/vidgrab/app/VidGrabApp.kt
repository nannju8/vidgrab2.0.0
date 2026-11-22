package com.vidgrab.app

import android.app.Application
import com.vidgrab.app.data.AppPrefs
import com.vidgrab.app.download.DownloadCenter
import com.vidgrab.app.download.DownloadWorker
import com.vidgrab.app.web.TabManager

class VidGrabApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppPrefs.init(this)
        TabManager.init(this)
        DownloadCenter.init(this)
        DownloadWorker.schedule(this)
    }
}
