package com.heddrich.companion

import android.app.Application
import com.heddrich.companion.data.CompanionDatabase
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        CompanionDatabase.appContext = this
        CrashGuard.install(this)
        PDFBoxResourceLoader.init(applicationContext)
    }
}
