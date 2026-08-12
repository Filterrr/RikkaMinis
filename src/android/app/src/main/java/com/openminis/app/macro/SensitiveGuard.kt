package com.openminis.app.macro

import com.openminis.app.accessibility.MinisAccessibilityService
import com.openminis.app.logging.AppLogger
import org.json.JSONArray

/**
 * Sensitive operation guard (B) — detects sensitive pages during
 * macro replay and stops execution before sensitive actions occur.
 *
 * Detection: text matching on the current UI tree + package name check.
 * When a sensitive page is detected, the guard returns a block reason
 * and the replay should stop.
 */
class SensitiveGuard {

    companion object {
        private const val TAG = "SensitiveGuard"

        /** Keywords that trigger guard (text matching on UI elements) */
        val SENSITIVE_KEYWORDS: Set<String> = setOf(
            "密码", "password", "Passwort", "senha",
            "支付", "pay", "payment", "Zahlung",
            "验证码", "captcha", "verification code", "OTP", "code",
            "银行", "bank", "account",
            "登录", "login", "sign in", "anmelden",
            "信用卡", "credit card", "card number",
            "转账", "transfer", "überweisen",
            "确认支付", "confirm payment",
            "指纹", "fingerprint", "face id", "biometric",
        )

        /** Package names that are always treated as sensitive */
        val SENSITIVE_PACKAGES: Set<String> = setOf(
            "com.tencent.mm",       // WeChat (payments)
            "com.tencent.wework",   // WeCom
            "com.alipay",           // Alipay
            "com.eg.android.AlipayGphone", // Alipay alt
            "com.google.android.apps.walletnfcrel", // Google Wallet
        )

        /** Activity name fragments that indicate sensitive pages */
        val SENSITIVE_ACTIVITY_FRAGMENTS: Set<String> = setOf(
            "password", "payment", "pay", "login", "captcha",
            "verify", "auth", "2fa", "otp", "pin", "credential",
            "密码", "支付", "验证", "登录",
        )
    }

    /** Result of a guard check */
    data class GuardResult(
        val blocked: Boolean,
        val reason: String? = null,
        val matchedKeyword: String? = null,
        val matchedPackage: String? = null,
        val matchedActivity: String? = null,
    )

    /** Check the current UI state for sensitive content. */
    fun check(): GuardResult {
        val svc = MinisAccessibilityService.getInstance() ?: return GuardResult(false)

        // 1. Check foreground package
        val (pkg, activity) = svc.foregroundPackage()
        if (pkg != null && pkg.lowercase() in SENSITIVE_PACKAGES) {
            return GuardResult(
                blocked = true,
                reason = "Sensitive package: $pkg",
                matchedPackage = pkg,
            )
        }

        // 2. Check activity name
        if (activity != null) {
            val act = activity.lowercase()
            for (frag in SENSITIVE_ACTIVITY_FRAGMENTS) {
                if (act.contains(frag.lowercase())) {
                    return GuardResult(
                        blocked = true,
                        reason = "Sensitive activity: $activity contains '$frag'",
                        matchedActivity = activity,
                    )
                }
            }
        }

        // 3. Check UI text for sensitive keywords
        val textMatches = mutableListOf<String>()
        for (root in svc.rootNodes()) {
            collectText(root, 10, 0, textMatches)
        }

        for (keyword in SENSITIVE_KEYWORDS) {
            val kw = keyword.lowercase()
            for (text in textMatches) {
                if (text.lowercase().contains(kw)) {
                    return GuardResult(
                        blocked = true,
                        reason = "Sensitive keyword '$keyword' found in UI text",
                        matchedKeyword = keyword,
                    )
                }
            }
        }

        return GuardResult(false)
    }

    /**
     * Check a specific action for sensitivity (e.g., input into a
     * password field). Returns true if the action should be blocked.
     */
    fun checkAction(action: MacroAction): GuardResult {
        // Block input actions that look like they're typing into a sensitive field
        if (action.type == "input") {
            val targetDesc = action.description?.lowercase() ?: ""
            val sensitiveFieldHints = setOf("password", "密码", "passwort", "senha", "pin", "code")
            for (hint in sensitiveFieldHints) {
                if (targetDesc.contains(hint)) {
                    return GuardResult(
                        blocked = true,
                        reason = "Input action on sensitive field: '$hint'",
                        matchedKeyword = hint,
                    )
                }
            }
        }
        return GuardResult(false)
    }

    private fun collectText(
        node: android.view.accessibility.AccessibilityNodeInfo?,
        maxDepth: Int,
        depth: Int,
        out: MutableList<String>,
    ) {
        if (node == null || depth > maxDepth) return
        val text = node.text?.toString()
        if (!text.isNullOrBlank()) out.add(text)
        val desc = node.contentDescription?.toString()
        if (!desc.isNullOrBlank()) out.add(desc)
        for (i in 0 until node.childCount) {
            collectText(node.getChild(i), maxDepth, depth + 1, out)
        }
    }
}