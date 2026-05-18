package com.example.myapplication.data.rag

import java.io.FileNotFoundException
import java.io.InputStream

class RagAssetMissingException(fileName: String) : Exception("Asset not found: $fileName")

class RagAssetLoader(private val openStream: (String) -> InputStream) {
    fun loadText(fileName: String): String {
        return try {
            openStream(fileName).bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (e: FileNotFoundException) {
            throw RagAssetMissingException(fileName)
        }
    }
}
