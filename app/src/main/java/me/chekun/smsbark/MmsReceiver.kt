package me.chekun.smsbark

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 默认短信应用要求声明彩信接收器才能获得角色资格。
 * SmsBark 只关注短信验证码转发，不解析彩信内容，保持默认结果码即可。
 */
class MmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
    }
}
