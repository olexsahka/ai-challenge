package com.example.myapplication.platform.android

import com.example.myapplication.platform.DateFormatter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AndroidDateFormatter : DateFormatter {
    private val format = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault())
    override fun formatSessionTitle(millis: Long): String = format.format(Date(millis))
}
