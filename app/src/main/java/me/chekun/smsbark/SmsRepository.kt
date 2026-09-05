package me.chekun.smsbark

import android.app.role.RoleManager
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.provider.Telephony
import android.telephony.SmsManager

object SmsRepository {

    data class Conversation(
        val threadId: Long,
        val address: String,
        val snippet: String,
        val date: Long
    )

    data class SmsMessage(
        val id: Long,
        val address: String,
        val body: String,
        val date: Long,
        val isOutgoing: Boolean
    )

    /**
     * 是否为默认短信应用。
     * API 29+ 优先用 RoleManager 判断——Telephony.Sms.getDefaultSmsPackage()
     * 在部分设备/系统镜像上即使角色已授予也可能返回 null，不可靠。
     */
    fun isDefaultSmsApp(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = context.getSystemService(RoleManager::class.java)
            return roleManager?.isRoleHeld(RoleManager.ROLE_SMS) == true
        }
        return context.packageName == Telephony.Sms.getDefaultSmsPackage(context)
    }

    /** 将收到的短信写入系统短信库（默认短信应用的义务） */
    fun insertIncoming(context: Context, address: String, body: String, date: Long) {
        val threadId = Telephony.Threads.getOrCreateThreadId(context, address)
        val values = ContentValues().apply {
            put(Telephony.Sms.ADDRESS, address)
            put(Telephony.Sms.BODY, body)
            put(Telephony.Sms.DATE, date)
            put(Telephony.Sms.READ, 0)
            put(Telephony.Sms.SEEN, 0)
            put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_INBOX)
            put(Telephony.Sms.THREAD_ID, threadId)
        }
        context.contentResolver.insert(Telephony.Sms.Inbox.CONTENT_URI, values)
    }

    /** 会话列表：按会话分组，取每个会话的最新一条消息 */
    fun getConversations(context: Context): List<Conversation> {
        val projection = arrayOf(
            Telephony.Sms.THREAD_ID,
            Telephony.Sms.ADDRESS,
            Telephony.Sms.BODY,
            Telephony.Sms.DATE
        )
        val result = LinkedHashMap<Long, Conversation>()
        context.contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            projection,
            null,
            null,
            "${Telephony.Sms.DATE} DESC"
        )?.use { cursor ->
            val threadIdIdx = cursor.getColumnIndexOrThrow(Telephony.Sms.THREAD_ID)
            val addressIdx = cursor.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
            val bodyIdx = cursor.getColumnIndexOrThrow(Telephony.Sms.BODY)
            val dateIdx = cursor.getColumnIndexOrThrow(Telephony.Sms.DATE)
            while (cursor.moveToNext()) {
                val threadId = cursor.getLong(threadIdIdx)
                if (result.containsKey(threadId)) continue
                result[threadId] = Conversation(
                    threadId = threadId,
                    address = cursor.getString(addressIdx) ?: "",
                    snippet = cursor.getString(bodyIdx) ?: "",
                    date = cursor.getLong(dateIdx)
                )
            }
        }
        return result.values.toList()
    }

    /** 指定会话内的全部消息，按时间正序 */
    fun getMessages(context: Context, threadId: Long): List<SmsMessage> {
        val projection = arrayOf(
            Telephony.Sms._ID,
            Telephony.Sms.ADDRESS,
            Telephony.Sms.BODY,
            Telephony.Sms.DATE,
            Telephony.Sms.TYPE
        )
        val messages = mutableListOf<SmsMessage>()
        context.contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            projection,
            "${Telephony.Sms.THREAD_ID} = ?",
            arrayOf(threadId.toString()),
            "${Telephony.Sms.DATE} ASC"
        )?.use { cursor ->
            val idIdx = cursor.getColumnIndexOrThrow(Telephony.Sms._ID)
            val addressIdx = cursor.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
            val bodyIdx = cursor.getColumnIndexOrThrow(Telephony.Sms.BODY)
            val dateIdx = cursor.getColumnIndexOrThrow(Telephony.Sms.DATE)
            val typeIdx = cursor.getColumnIndexOrThrow(Telephony.Sms.TYPE)
            while (cursor.moveToNext()) {
                messages.add(
                    SmsMessage(
                        id = cursor.getLong(idIdx),
                        address = cursor.getString(addressIdx) ?: "",
                        body = cursor.getString(bodyIdx) ?: "",
                        date = cursor.getLong(dateIdx),
                        isOutgoing = cursor.getInt(typeIdx) == Telephony.Sms.MESSAGE_TYPE_SENT
                    )
                )
            }
        }
        return messages
    }

    /** 发送短信并写入已发送记录 */
    @Suppress("DEPRECATION")
    fun sendSms(context: Context, address: String, body: String) {
        val smsManager = SmsManager.getDefault()
        smsManager.sendTextMessage(address, null, body, null, null)

        val threadId = Telephony.Threads.getOrCreateThreadId(context, address)
        val values = ContentValues().apply {
            put(Telephony.Sms.ADDRESS, address)
            put(Telephony.Sms.BODY, body)
            put(Telephony.Sms.DATE, System.currentTimeMillis())
            put(Telephony.Sms.READ, 1)
            put(Telephony.Sms.SEEN, 1)
            put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_SENT)
            put(Telephony.Sms.THREAD_ID, threadId)
        }
        context.contentResolver.insert(Telephony.Sms.Sent.CONTENT_URI, values)
    }
}
