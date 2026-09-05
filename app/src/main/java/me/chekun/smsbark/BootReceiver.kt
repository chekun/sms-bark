package me.chekun.smsbark

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // 开机后系统会自动重新注册广播接收器，这里无需额外操作，
        // 仅作为保活探针存在。
    }
}
