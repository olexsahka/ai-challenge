package com.example.myapplication.data.composition

interface BtcTrackingController {
    fun startBtcTracking(userPrompt: String)
    fun unbind() {}
}
