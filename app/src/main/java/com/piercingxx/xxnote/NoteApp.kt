package com.piercingxx.xxnote

import android.app.Application
import com.piercingxx.xxnote.log.AppLog

class NoteApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLog.init(this)
        AppLog.installCrashHandler()
        AppLog.i("app", "start")
    }
}
