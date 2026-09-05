package me.chekun.smsbark

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class SmsReceiver : BroadcastReceiver() {
    
    // 默认关键词
    private val DEFAULT_KEYWORDS = "验证码,code,otp"

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Telephony.Sms.Intents.SMS_DELIVER_ACTION) {
            val pendingResult = goAsync()
            val smsMessages = Telephony.Sms.Intents.getMessagesFromIntent(intent)

            // 使用 IO Dispatcher 启动协程
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                    // 获取用户配置的关键词，如果为空则使用默认值
                    val keywordsConfig = prefs.getString("keywords", "") ?: ""
                    val finalKeywordsString = if (keywordsConfig.isBlank()) DEFAULT_KEYWORDS else keywordsConfig

                    // 分割关键词并去除空白
                    val keywords = finalKeywordsString.split(",")
                        .map { it.trim().lowercase() }
                        .filter { it.isNotEmpty() }

                    // 按发件人合并分段短信正文，仅写入一条完整消息
                    val mergedBySender = LinkedHashMap<String, StringBuilder>()
                    for (message in smsMessages) {
                        val sender = message.originatingAddress ?: context.getString(R.string.app_name)
                        mergedBySender.getOrPut(sender) { StringBuilder() }.append(message.messageBody)
                    }

                    for ((sender, bodyBuilder) in mergedBySender) {
                        val rawMessageBody = bodyBuilder.toString()

                        // 身为默认短信应用，必须自行把短信写入系统短信库
                        SmsRepository.insertIncoming(context, sender, rawMessageBody, System.currentTimeMillis())

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

                        BarkSender.send(
                            context = context,
                            barkServer = barkServer,
                            barkToken = barkToken,
                            title = sender,
                            body = rawMessageBody,
                            shouldUpdateStats = true
                        )
                    }
                } finally {
                    // 确保最后调用 finish()，通知系统处理完毕
                    pendingResult.finish()
                }
            }
        }
    }
}