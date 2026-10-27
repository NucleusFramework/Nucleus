@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package dev.nucleusframework.authenticationiosdemo

import dev.nucleusframework.authentication.AuthenticationException
import dev.nucleusframework.authentication.AuthenticationPolicy
import dev.nucleusframework.authentication.AuthenticationPrompt
import dev.nucleusframework.authentication.Authenticator
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectBase.OverrideInit
import kotlinx.cinterop.cstr
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toCValues
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSStringFromClass
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationDelegateProtocol
import platform.UIKit.UIApplicationDelegateProtocolMeta
import platform.UIKit.UIApplicationMain
import platform.UIKit.UIColor
import platform.UIKit.UILabel
import platform.UIKit.UIResponder
import platform.UIKit.UIResponderMeta
import platform.UIKit.UIScreen
import platform.UIKit.UIViewController
import platform.UIKit.UIWindow
import platform.posix.fflush
import platform.posix.stdout
import kotlin.time.TimeSource

fun main(args: Array<String>) {
    memScoped {
        val argv = (arrayOf("AuthenticationIosDemo") + args).map { it.cstr.ptr }.toCValues()
        UIApplicationMain(args.size + 1, argv.ptr, null, NSStringFromClass(AppDelegate))
    }
}

private val start = TimeSource.Monotonic.markNow()

/** Every line the E2E script waits for starts with `[E2E`. */
private fun log(line: String) {
    println("[E2E +${start.elapsedNow().inWholeMilliseconds}ms] $line")
    fflush(stdout)
}

class AppDelegate : UIResponder, UIApplicationDelegateProtocol {
    companion object : UIResponderMeta(), UIApplicationDelegateProtocolMeta

    @Suppress("unused")
    @OverrideInit
    constructor() : super()

    private var appWindow: UIWindow? = null

    override fun application(
        application: UIApplication,
        didFinishLaunchingWithOptions: Map<Any?, *>?,
    ): Boolean {
        appWindow =
            UIWindow(frame = UIScreen.mainScreen.bounds).apply {
                rootViewController =
                    UIViewController().apply {
                        view.backgroundColor = UIColor.whiteColor
                        view.addSubview(UILabel(frame = view.bounds).apply { text = "Auth E2E" })
                    }
                makeKeyAndVisible()
            }
        val scenario = NSProcessInfo.processInfo.environment["E2E_SCENARIO"] as? String ?: "availability"
        CoroutineScope(Dispatchers.Main).launch { run(scenario) }
        return true
    }
}

/**
 * One scenario per launch, so the script can drive the simulator between the prompt and its
 * outcome: `PROMPT` is logged right before a prompt is requested, `RESULT` once it returned.
 */
private suspend fun run(scenario: String) {
    log("scenario: $scenario")
    // Let the app become active: LocalAuthentication shows nothing for an inactive app.
    delay(1_000)
    for (policy in AuthenticationPolicy.entries) log("availability($policy): ${Authenticator.availability(policy)}")
    when (scenario) {
        "availability" -> Unit
        "deviceowner" -> attempt(AuthenticationPolicy.DeviceOwner)
        "biometrics" -> attempt(AuthenticationPolicy.Biometrics)
        "appcancel" -> attempt(AuthenticationPolicy.Biometrics, cancelAfterMillis = 3_000)
        "blank" -> attempt(AuthenticationPolicy.DeviceOwner, reason = " ")
        else -> log("unknown scenario")
    }
    log("DONE")
}

private suspend fun attempt(
    policy: AuthenticationPolicy,
    reason: String = "unlock the E2E vault",
    cancelAfterMillis: Long? = null,
) {
    val begin = TimeSource.Monotonic.markNow()
    log("PROMPT $policy")
    val outcome =
        coroutineScope {
            val job =
                async {
                    try {
                        Authenticator.authenticate(AuthenticationPrompt(reason, cancelLabel = "Not now"), policy)
                        "Succeeded"
                    } catch (e: AuthenticationException) {
                        "Failed(${e.error}: ${e.message})"
                    }
                }
            if (cancelAfterMillis != null) {
                delay(cancelAfterMillis)
                job.cancel()
            }
            try {
                job.await()
            } catch (e: CancellationException) {
                if (job.isCancelled) "Cancelled" else throw e
            }
        }
    log("RESULT $outcome in ${begin.elapsedNow().inWholeMilliseconds}ms")
}
