package com.fengeon.resprite_desktop

import android.app.Application

/**
 * Application entry point. No billing, no analytics, no network init.
 * Kept minimal so the editor can run entirely offline.
 */
class RespriteApp : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    companion object {
        lateinit var instance: RespriteApp
            private set
    }
}
