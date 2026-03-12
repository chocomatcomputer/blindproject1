package com.example.blindproject1.network

import android.location.Location
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton
import java.net.URLEncoder

@Singleton
class TMapRepository @Inject constructor() {
    
    private val client = OkHttpClient()
    private val appKey = "YEWVxfrK4j8xTNQZURJ4z1Te4JTZs26v45fgmfn7" // Provided by user

    suspend fun getPedestrianRoute(start: Location, destLat: Double, destLon: Double, startName: String, endName: String): List<Location> = withContext(Dispatchers.IO) {
        val waypoints = mutableListOf<Location>()
        try {
            val encodedStartName = URLEncoder.encode("출발지", "UTF-8")
            val encodedEndName = URLEncoder.encode("목적지", "UTF-8")
            
            val jsonBody = JSONObject().apply {
                put("startX", start.longitude)
                put("startY", start.latitude)
                put("endX", destLon)
                put("endY", destLat)
                put("startName", encodedStartName)
                put("endName", encodedEndName)
                put("reqCoordType", "WGS84GEO")
                put("resCoordType", "WGS84GEO")
                // 옵션 30: 최단거리+계단제외 (시각장애인에게 안전한 평지 위주 탐색)
                put("searchOption", "30") 
            }

            val requestBody = jsonBody.toString().toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url("https://apis.openapi.sk.com/tmap/routes/pedestrian?version=1")
                .post(requestBody)
                .addHeader("accept", "application/json")
                .addHeader("content-type", "application/json")
                .addHeader("appKey", appKey)
                .build()

            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val responseBody = response.body?.string()
                if (responseBody != null) {
                    val jsonResponse = JSONObject(responseBody)
                    val features = jsonResponse.getJSONArray("features")
                    
                    for (i in 0 until features.length()) {
                        val feature = features.getJSONObject(i)
                        val geometry = feature.getJSONObject("geometry")
                        val type = geometry.getString("type")
                        
                        if (type == "Point") {
                            val coords = geometry.getJSONArray("coordinates")
                            val loc = Location("tmap").apply {
                                longitude = coords.getDouble(0)
                                latitude = coords.getDouble(1)
                            }
                            waypoints.add(loc)
                        } else if (type == "LineString") {
                            val coordsList = geometry.getJSONArray("coordinates")
                            for (j in 0 until coordsList.length()) {
                                val coords = coordsList.getJSONArray(j)
                                val loc = Location("tmap").apply {
                                    longitude = coords.getDouble(0)
                                    latitude = coords.getDouble(1)
                                }
                                waypoints.add(loc)
                            }
                        }
                    }
                }
            } else {
                Log.e("TMap", "API Error: ${response.code} ${response.message} ${response.body?.string()}")
            }
        } catch (e: Exception) {
            Log.e("TMap", "Failed to fetch route", e)
        }
        
        // Remove duplicate/too-close waypoints to smooth out GPS jitter targets
        val filteredWaypoints = mutableListOf<Location>()
        for (wp in waypoints) {
            if (filteredWaypoints.isEmpty()) {
                filteredWaypoints.add(wp)
            } else {
                val last = filteredWaypoints.last()
                // GPS가 튀지 않도록 너무 가까운 점(3m 이내)은 쳐냄
                if (last.distanceTo(wp) > 3.0) {
                    filteredWaypoints.add(wp)
                }
            }
        }
        
        Log.d("TMap", "Generated ${filteredWaypoints.size} valid waypoints")
        return@withContext filteredWaypoints
    }
}