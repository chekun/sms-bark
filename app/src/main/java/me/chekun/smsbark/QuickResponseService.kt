package me.chekun.smsbark

import android.app.Service
import android.content.Intent
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 处理系统在来电时发起的"快捷回复短信"请求（ACTION_RESPOND_VIA_MESSAGE）。
 * Android 要求默认短信应用必须用一个 Service（而非 Activity）响应该 Intent，
 * 否则无法通过 RoleManager 的 SMS 角色资格校验。
 */
class QuickResponseService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val address = intent?.data?.schemeSpecificPart
        val text = intent?.getStringExtra(Intent.EXTRA_TEXT)

        if (!address.isNullOrBlank() && !text.isNullOrBlank()) {
            CoroutineScope(Dispatchers.IO).launch {
                SmsRepository.sendSms(applicationContext, address, text)
                stopSelf(startId)
            }
        } else {
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }
}
