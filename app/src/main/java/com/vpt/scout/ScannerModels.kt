package com.vpt.scout

import com.google.gson.annotations.SerializedName

/**
 * Property data used across scanner browsing, map, and scout flows.
 */
data class Property(
    val apn: String,
    val address: String?,
    val city: String?,
    val latitude: Double?,
    val longitude: Double?,
    @SerializedName("has_vpt") val hasVpt: Boolean = false,
    @SerializedName("condition_score") val conditionScore: Float?,
    @SerializedName("is_scouted") val isScouted: Boolean = false,
    @SerializedName("streetview_image_path") val streetviewImagePath: String?,
    @SerializedName("power_status") val powerStatus: String? = null,
    @SerializedName("mailing_address") val mailingAddress: String? = null,
    @SerializedName("last_sale_date") val lastSaleDate: String? = null,
    @SerializedName("is_out_of_state") val isOutOfState: Boolean = false,
    @SerializedName("deceased_count") val deceasedCount: Int? = null,
    @SerializedName("delinquent") val isDelinquent: Boolean = false,
    @SerializedName("situs_zip") val zipCode: String? = null,
    @SerializedName("tax_year") val taxYear: String? = null,
    @SerializedName("last_payment") val lastPayment: String? = null,
    @SerializedName("owner_name") val ownerName: String? = null,
    @SerializedName("research_status") val researchStatus: String? = null,
    @SerializedName("ai_vacancy_verdict") val aiVacancyVerdict: String? = null,
    @SerializedName("ai_vacancy_confidence") val aiVacancyConfidence: Float? = null,
    @SerializedName("site_vacant") val siteVacant: Boolean? = null
)

data class PropertiesResponse(
    val properties: List<Property>,
    val total: Int,
    val page: Int,
    @SerializedName("per_page") val perPage: Int,
    @SerializedName("total_pages") val totalPages: Int
)

data class NextPropertyResponse(
    val property: Property?,
    val remaining: Int
)

data class PropertyList(
    val id: Long,
    val name: String,
    val description: String?,
    @SerializedName("property_count") val propertyCount: Int = 0,
    @SerializedName("created_at") val createdAt: String? = null
)

data class ListWithProperties(
    val id: Long,
    val name: String,
    val description: String?,
    @SerializedName("created_at") val createdAt: String?,
    val properties: List<Property>
)

data class CreateListRequest(
    val name: String,
    val description: String? = null
)

data class AddPropertiesRequest(
    val apns: List<String>
)

data class ScoutResultRequest(
    val apn: String,
    @SerializedName("follow_up") val followUp: Boolean,
    val flyered: Boolean,
    val notes: String?,
    val latitude: Double?,
    val longitude: Double?
)

data class ScoutResult(
    val id: Long,
    val apn: String,
    @SerializedName("follow_up") val followUp: Boolean,
    val flyered: Boolean,
    val notes: String?,
    @SerializedName("scouted_at") val scoutedAt: String?,
    val latitude: Double?,
    val longitude: Double?
)

data class ScoutStats(
    @SerializedName("total_visits") val totalVisits: Int,
    @SerializedName("follow_ups") val followUps: Int,
    val flyered: Int,
    @SerializedName("unique_properties") val uniqueProperties: Int
)

data class ApiResponse<T>(
    val success: Boolean,
    val data: T? = null,
    val error: String? = null
)

data class RouteResponse(
    val url: String,
    @SerializedName("property_count") val propertyCount: Int,
    val optimized: Boolean
)

data class GeoJsonResponse(
    val type: String,
    val features: List<Feature>
)

data class Feature(
    val type: String,
    val properties: FeatureProperties,
    val geometry: Geometry?
)

data class FeatureProperties(
    val apn: String,
    val address: String?,
    @SerializedName("has_vpt") val hasVpt: Int?,
    @SerializedName("condition_score") val conditionScore: Float?,
    val city: String?,
    @SerializedName("streetview_image_path") val streetviewImagePath: String?
)

data class Geometry(
    val type: String,
    val coordinates: List<Double>?
)
