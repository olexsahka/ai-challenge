package com.example.myapplication.platform.js

import com.example.myapplication.platform.Clock

class JsClock : Clock {
    override fun nowMillis(): Long = js("Date.now()").toString().toLong()
}
