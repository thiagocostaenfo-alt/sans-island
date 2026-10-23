package com.thiagocosta.sansisland

import android.app.Application

class SansIslandApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        Prefs.migrateToGlass()
        Prefs.migrateToFluid()
    }
}
