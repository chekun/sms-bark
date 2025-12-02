package me.chekun.smsbark

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException

object BarkSender {

    suspend fun send(
        context: Context,
        barkServer: String,
        barkToken: String,
        title: String,
        body: String,
        shouldUpdateStats: Boolean = true
    ): Boolean {
        return withContext(Dispatchers.IO) {
            if (barkServer.isEmpty() || barkToken.isEmpty()) {
                if (shouldUpdateStats) StatisticsManager.incrementFailure(context)
                return@withContext false
            }

            val json = JSONObject()
            json.put("body", body)
            json.put("title", title)
            json.put("device_key", barkToken)
            json.put("group", "SmsBark")
            json.put("level", "timeSensitive")

            val mediaType = "application/json; charset=utf-8".toMediaType()
            val requestBody = json.toString().toRequestBody(mediaType)

            val finalServerUrl = if (barkServer.endsWith("/")) barkServer else "$barkServer/"
            val barkURL = "${finalServerUrl}push"

            val client = OkHttpClient()
            val request = Request.Builder()
                .url(barkURL)
                .post(requestBody)
                .build()

            try {
                val response = client.newCall(request).execute()
                val isSuccessful = response.isSuccessful
                response.close()

                if (shouldUpdateStats) {
                    if (isSuccessful) {
                        StatisticsManager.incrementSuccess(context)
                    } else {
                        StatisticsManager.incrementFailure(context)
                    }
                }
                isSuccessful
            } catch (e: IOException) {
                e.printStackTrace()
                if (shouldUpdateStats) {
                    StatisticsManager.incrementFailure(context)
                }
                false
            }
        }
    }
}