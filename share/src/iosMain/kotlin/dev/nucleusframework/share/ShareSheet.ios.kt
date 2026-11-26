package dev.nucleusframework.share

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSURL
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIApplication
import platform.UIKit.UISceneActivationStateForegroundActive
import platform.UIKit.UIViewController
import platform.UIKit.UIWindow
import platform.UIKit.UIWindowScene
import platform.UIKit.popoverPresentationController

internal actual val isPlatformShareSupported: Boolean
    get() = true

internal actual suspend fun platformShare(request: ShareRequest): Unit =
    withContext(Dispatchers.Main) {
        val presenter = presentingViewController()
        val controller =
            UIActivityViewController(activityItems = request.items.map(::activityItem), applicationActivities = null)
        anchorPopover(controller, presenter)
        presenter.presentViewController(controller, animated = true, completion = null)
    }

private fun activityItem(item: ShareItem): Any =
    when (item) {
        is ShareItem.Text -> item.text
        is ShareItem.Url -> url(item.url)
        is ShareItem.File -> NSURL.fileURLWithPath(item.path)
        is ShareItem.FileUri -> url(item.uri)
    }

private fun url(value: String): NSURL =
    NSURL.URLWithString(value) ?: throw ShareException(ShareError.InvalidItem, "Not a URL: $value")

/** The top of the key window's presentation stack. */
private fun presentingViewController(): UIViewController {
    var controller =
        keyWindow()?.rootViewController ?: throw ShareException(ShareError.NoWindow, "No key window")
    while (true) {
        if (controller is UIActivityViewController) throw ShareException(ShareError.AlreadyOpen)
        controller = controller.presentedViewController ?: return controller
    }
}

@Suppress("DEPRECATION")
private fun keyWindow(): UIWindow? =
    UIApplication.sharedApplication.connectedScenes
        .filterIsInstance<UIWindowScene>()
        .firstOrNull { it.activationState == UISceneActivationStateForegroundActive }
        ?.keyWindow
        ?: UIApplication.sharedApplication.keyWindow

/** On iPad the sheet is a popover and needs an anchor: the centre of the presenter, without arrow. */
@OptIn(ExperimentalForeignApi::class)
private fun anchorPopover(
    controller: UIActivityViewController,
    presenter: UIViewController,
) {
    val popover = controller.popoverPresentationController ?: return
    val view = presenter.view
    popover.sourceView = view
    popover.sourceRect = view.bounds.useContents { CGRectMake(size.width / 2, size.height / 2, 0.0, 0.0) }
    popover.permittedArrowDirections = 0u
}
