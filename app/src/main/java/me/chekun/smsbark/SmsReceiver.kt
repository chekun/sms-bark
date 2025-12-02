package me.chekun.smsbark

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class SmsReceiver : BroadcastReceiver() {
    private val coroutineScope = CoroutineScope(Dispatchers.IO)
    
    // 默认关键词
    private val DEFAULT_KEYWORDS = "验证码,code,otp"

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Telephony.Sms.Intents.SMS_RECEIVED_ACTION) {
            val smsMessages = Telephony.Sms.Intents.getMessagesFromIntent(intent)

            coroutineScope.launch {
                val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                // 获取用户配置的关键词，如果为空则使用默认值
                val keywordsConfig = prefs.getString("keywords", "") ?: ""
                val finalKeywordsString = if (keywordsConfig.isBlank()) DEFAULT_KEYWORDS else keywordsConfig
                
                // 分割关键词并去除空白
                val keywords = finalKeywordsString.split(",")
                    .map { it.trim().lowercase() }
                    .filter { it.isNotEmpty() }

                for (message in smsMessages) {
                    val rawMessageBody = message.messageBody
                    val lowerCaseBody = rawMessageBody.lowercase()
                    
                    // 检查是否包含任一关键词
                    val isMatch = keywords.any { keyword -> 
                        lowerCaseBody.contains(keyword) 
                    }
                    
                    if (!isMatch) {
                        continue
                    }
                    
                    val barkServer = prefs.getString("bark_server", "") ?: ""
                    val barkToken = prefs.getString("bark_token", "") ?: ""
                    
                    val sender = message.originatingAddress ?: "SmsBark"
                    
                    BarkSender.send(
                        context = context,
                        barkServer = barkServer,
                        barkToken = barkToken,
                        title = sender,
                        body = rawMessageBody,
                        shouldUpdateStats = true
                    )
                }
            }
        }
    }
}