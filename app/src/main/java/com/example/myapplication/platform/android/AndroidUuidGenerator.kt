package com.example.myapplication.platform.android

import com.example.myapplication.platform.UuidGenerator
import java.util.UUID

class AndroidUuidGenerator : UuidGenerator {
    override fun generate(): String = UUID.randomUUID().toString()
}
