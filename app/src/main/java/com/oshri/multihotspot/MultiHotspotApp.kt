package com.oshri.multihotspot

import android.app.Application
import com.oshri.multihotspot.service.HotspotService

class MultiHotspotApp : Application() {
    override fun onCreate() {
        super.onCreate()
        HotspotController.init(this)
        HotspotService.createChannel(this)
    }
}
