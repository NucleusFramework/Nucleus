package dev.nucleusframework.window.tao.workspace

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.nucleusframework.window.tao.TaoWindow

/**
 * A set of windows that act as one: the members of a satellite workspace, the
 * hosts a panel can be docked into, the windows a torn-off tab can be dropped
 * on.
 *
 * Tracks membership, focus recency and an optional pin, and derives the
 * [owner] from them: the pinned member, else the most recently focused one
 * (with [followFocus]), else the first to have joined. A member leaves on its
 * own when its native window is destroyed.
 *
 * Everything here runs on the Tao event-loop thread, which is also the Compose
 * dispatcher, so the state writes need no synchronisation.
 *
 * @param followFocus whether the owner follows keyboard focus between members.
 * @param onJoined called once [join] has added a window.
 * @param onLeft called once [leave] has removed a window, with the owner that
 *   remains — `null` when the group is empty — so the caller can re-home what
 *   the departed window hosted.
 */
internal class WindowGroup(
    val followFocus: Boolean,
    private val onJoined: (TaoWindow) -> Unit = {},
    private val onLeft: (left: TaoWindow, remainingOwner: TaoWindow?) -> Unit = { _, _ -> },
) {
    private class Hooks(
        val focus: (Boolean) -> Unit,
        val destroyed: () -> Unit,
    )

    private val memberList = mutableStateListOf<TaoWindow>()
    private val hooks = HashMap<TaoWindow, Hooks>()

    /** Members by focus recency, most recent first; only those focused while in the group. */
    private val recency = mutableStateListOf<TaoWindow>()

    /** The member [pinTo] selected, or `null` when the owner follows focus. Kept even for a non-member. */
    var pinned: TaoWindow? by mutableStateOf(null)
        private set

    /**
     * Windows that have joined, in join order.
     *
     * A snapshot of the live list, so reading it in composition subscribes to
     * it and comparing it with `==` means what it says — the observable list
     * Compose keeps underneath compares by identity, and would also change
     * shape under a caller iterating it while a window opens or closes.
     */
    val members: List<TaoWindow> get() = memberList.toList()

    /**
     * The pinned member if it is one, else the most recently focused member
     * when [followFocus] is on, else the first member; `null` while empty.
     */
    val owner: TaoWindow?
        get() =
            pinned?.takeIf { it in memberList }
                ?: recency.firstOrNull()?.takeIf { followFocus }
                ?: memberList.firstOrNull()

    /**
     * Every member: the [owner] first, then the rest by focus recency, then
     * the members never focused, in join order. The order to hit-test
     * overlapping windows in — the window the user worked in most recently is
     * the one most likely to be on top.
     */
    val membersByRecency: List<TaoWindow>
        get() {
            val first = owner ?: return emptyList()
            val ordered = ArrayList<TaoWindow>(memberList.size)
            ordered += first
            for (window in recency) if (window !== first) ordered += window
            for (window in memberList) if (window !in ordered) ordered += window
            return ordered
        }

    /** Adds [window]. Idempotent. */
    fun join(window: TaoWindow) {
        if (window in memberList) return
        val windowHooks =
            Hooks(
                focus = { focused -> if (focused) noteFocus(window) },
                destroyed = { leave(window) },
            )
        window.onFocusChanged(windowHooks.focus)
        window.onDestroyed(windowHooks.destroyed)
        hooks[window] = windowHooks
        memberList += window
        if (window.isFocused) noteFocus(window)
        onJoined(window)
    }

    /** Removes [window]; a no-op for a non-member. */
    fun leave(window: TaoWindow) {
        val windowHooks = hooks.remove(window) ?: return
        window.removeFocusListener(windowHooks.focus)
        window.removeDestroyedListener(windowHooks.destroyed)
        memberList -= window
        recency -= window
        if (pinned === window) pinned = null
        onLeft(window, owner)
    }

    /** Records [window] as the most recently focused member; ignored for a non-member. */
    fun noteFocus(window: TaoWindow) {
        if (window !in memberList) return
        recency -= window
        recency.add(0, window)
    }

    /** Makes [window] the [owner] regardless of focus; `null` returns to the focus-driven choice. */
    fun pinTo(window: TaoWindow?) {
        pinned = window
    }
}
