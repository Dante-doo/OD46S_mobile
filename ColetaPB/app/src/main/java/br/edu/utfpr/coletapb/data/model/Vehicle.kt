package br.edu.utfpr.coletapb.data.model

data class Vehicle(
    val id: Int,
    val licensePlate: String,
    val model: String,
    val brand: String,
    val year: Int,
    val status: String
)