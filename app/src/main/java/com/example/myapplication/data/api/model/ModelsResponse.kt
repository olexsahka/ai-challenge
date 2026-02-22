package com.example.myapplication.data.api.model

data class ModelsResponse(
    val `object`: String,
    val data: List<ModelItem>
)

data class ModelItem(
    val id: String,
    val `object`: String,
    val created: Long,
    val owned_by: String
)