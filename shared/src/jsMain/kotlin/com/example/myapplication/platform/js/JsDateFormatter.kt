package com.example.myapplication.platform.js

import com.example.myapplication.platform.DateFormatter

class JsDateFormatter : DateFormatter {
    override fun formatSessionTitle(millis: Long): String {
        val date = js("new Date(millis)")
        return date.toLocaleString().toString()
    }
}
