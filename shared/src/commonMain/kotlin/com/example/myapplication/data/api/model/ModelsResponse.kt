package com.example.myapplication.data.api.model

import kotlinx.serialization.Serializable

@Serializable
data class ModelsResponse(
    val `object`: String,
    val data: List<ModelItem>
)

@Serializable
data class ModelItem(
    val id: String,
    val `object`: String,
    val created: Long,
    val owned_by: String
)
