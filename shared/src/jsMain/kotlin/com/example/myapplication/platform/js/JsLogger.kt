package com.example.myapplication.platform.js

import com.example.myapplication.platform.Logger

class JsLogger : Logger {
    override fun d(tag: String, message: String) {
        console.log("[$tag] $message")
    }

    override fun e(tag: String, message: String, throwable: Throwable?) {
        console.error("[$tag] $message", throwable)
    }
}
