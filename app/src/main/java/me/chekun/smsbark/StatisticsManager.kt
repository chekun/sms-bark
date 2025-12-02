package me.chekun.smsbark

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object StatisticsManager {
    private const val PREFS_NAME = "app_stats"
    private const val KEY_DATE = "stats_date"
    private const val KEY_SUCCESS = "stats_success_count"
    private const val KEY_FAILURE = "stats_failure_count"

    fun incrementSuccess(context: Context) {
        updateStats(context, true)
    }

    fun incrementFailure(context: Context) {
        updateStats(context, false)
    }

    fun getTodayStats(context: Context): Pair<Int, Int> {
        checkAndReset(context)
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return Pair(prefs.getInt(KEY_SUCCESS, 0), prefs.getInt(KEY_FAILURE, 0))
    }

    private fun updateStats(context: Context, isSuccess: Boolean) {
        checkAndReset(context)
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val currentCount = if (isSuccess) prefs.getInt(KEY_SUCCESS, 0) else prefs.getInt(KEY_FAILURE, 0)
        prefs.edit()
            .putInt(if (isSuccess) KEY_SUCCESS else KEY_FAILURE, currentCount + 1)
            .apply()
    }

    private fun checkAndReset(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lastDate = prefs.getString(KEY_DATE, "")
        val today = getTodayDateString()
        
        if (lastDate != today) {
            prefs.edit()
                .putString(KEY_DATE, today)
                .putInt(KEY_SUCCESS, 0)
                .putInt(KEY_FAILURE, 0)
                .apply()
        }
    }

    private fun getTodayDateString(): String {
        return SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
    }
}