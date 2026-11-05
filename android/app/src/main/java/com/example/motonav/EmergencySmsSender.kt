package com.example.motonav

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import android.telephony.SmsMessage
import android.os.Build
import android.app.Activity

/** A single API submission. Never retries, launches a composer, or guesses a SIM. */
object EmergencySmsSender {
    @Suppress("DEPRECATION")
    fun manager(context: Context, subscriptionId: Int): SmsManager {
        check(context.checkSelfPermission(Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED) { "未授予发送短信权限" }
        check(context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY_MESSAGING)) { "这台设备不支持 SIM 短信" }
        check(SubscriptionManager.isValidSubscriptionId(subscriptionId)) { "请明确选择发送短信的 SIM 卡" }
        check(context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) { "需要电话状态权限以核对所选 SIM 卡" }
        val subscriptions = context.getSystemService(SubscriptionManager::class.java).activeSubscriptionInfoList.orEmpty()
        check(subscriptions.any { it.subscriptionId == subscriptionId }) { "所选 SIM 卡当前不可用，请重新选择" }
        return SmsManager.getSmsManagerForSubscriptionId(subscriptionId)
    }

    fun parts(context: Context, subscriptionId: Int, body: String): ArrayList<String> =
        manager(context, subscriptionId).divideMessage(body).also { check(it.size in 1..16) { "短信内容过长" } }

    fun send(context: Context, token: Long, subscriptionId: Int, phone: String, parts: ArrayList<String>) {
        val manager = manager(context, subscriptionId)
        fun callback(index: Int, delivery: Boolean): PendingIntent {
            val intent = Intent(context, EmergencySmsReceiver::class.java).apply {
                action = if (delivery) "com.example.motonav.EMERGENCY_DELIVERED" else "com.example.motonav.EMERGENCY_SENT"
                data = Uri.parse("motonav-emergency://sms/$token/$index/${if (delivery) 1 else 0}")
                putExtra("token", token); putExtra("part", index); putExtra("total", parts.size)
                putExtra("delivery", delivery)
            }
            // Only delivery needs the system's raw status-report PDU. The
            // mutable intent is explicit and its identity lives in data URI.
            val mutability = if (delivery && Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE
                else if (delivery) 0 else PendingIntent.FLAG_IMMUTABLE
            return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or mutability)
        }
        val sent = ArrayList(parts.indices.map { callback(it, false) })
        val delivered = ArrayList(parts.indices.map { callback(it, true) })
        manager.sendMultipartTextMessage(phone, null, parts, sent, delivered)
    }
}

class EmergencySmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != "com.example.motonav.EMERGENCY_SENT" && intent.action != "com.example.motonav.EMERGENCY_DELIVERED") return
        val segments = intent.data?.pathSegments ?: return
        if (intent.data?.scheme != "motonav-emergency" || intent.data?.host != "sms" || segments.size != 3) return
        val token = segments[0].toLongOrNull() ?: return
        val part = segments[1].toIntOrNull() ?: return
        val delivery = segments[2] == "1"
        val total = intent.getIntExtra("total", 0)
        val result = if (!delivery) resultCode else {
            // RESULT_OK alone means a report arrived, not successful delivery.
            val pdu = intent.getByteArrayExtra("pdu")
            val format = intent.getStringExtra("format")
            val report = if (pdu == null) null else runCatching {
                SmsMessage.createFromPdu(pdu, format ?: "3gpp")
            }.getOrNull()
            when {
                resultCode != Activity.RESULT_OK -> resultCode
                format == "3gpp2" || report == null || !report.isStatusReportMessage -> Int.MAX_VALUE
                report.status == 0 -> Activity.RESULT_OK
                report.status in 64..127 -> 1000 + report.status
                else -> Int.MAX_VALUE // Temporary/unrecognized status; no success claim.
            }
        }
        EmergencyAlertController.onSmsResult(token, part, total, delivery, result)
    }
}

class EmergencyCancelReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == "com.example.motonav.EMERGENCY_CANCEL")
            EmergencyAlertController.cancel(intent.getLongExtra("token", 0))
    }
}
