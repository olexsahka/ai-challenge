package com.example.myapplication.platform.android

import com.example.myapplication.platform.Clock

class AndroidClock : Clock {
    override fun nowMillis(): Long = System.currentTimeMillis()
}
