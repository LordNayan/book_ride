package com.screensaathi

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.screensaathi.screen.ScreenElement
import com.screensaathi.screen.ScreenSnapshot
import com.screensaathi.rapido.PlaceNameNormalizer
import com.screensaathi.session.SessionController
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Walks the live accessibility tree of the foreground app into a flat indexed
 * snapshot (contracts/accessibility.schema.json), filtering out our overlay.
 * Rapido preview also uses bounded gestures on controls rechecked here.
 *
 * `lastEventUptime` gives a debounced "screen settled" signal so the highlight
 * never lands mid-transition.
 */
class ScreenReaderService : AccessibilityService() {

    @Volatile
    private var lastEventUptime: Long = 0L
    @Volatile private var rapidoTyping = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private val closeRapidoIme = Runnable {
        if (rapidoTyping || SessionController.instance?.managesRapidoKeyboard() != true) return@Runnable
        if (imeTopPx() == 0) return@Runnable
        val rapidoVisible = try {
            windows.any { it.root?.packageName?.toString() == "com.rapido.passenger" }
        } catch (_: Exception) { false }
        if (rapidoVisible) performGlobalAction(GLOBAL_ACTION_BACK)
    }

    /** Leave the search list visible without dismissing the keyboard during Paste. */
    fun dismissRapidoKeyboard() {
        mainHandler.removeCallbacks(closeRapidoIme)
        mainHandler.postDelayed(closeRapidoIme, 150L)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        when (event?.eventType) {
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED,
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_FOCUSED,
            -> lastEventUptime = SystemClock.uptimeMillis()

            // The one signal a screen transition is guaranteed to send. A tap
            // can visibly change the app without ever firing TYPE_VIEW_CLICKED
            // (measured on Uber: zero click events, on a clickable button as
            // much as a bare text row) — so this, not the click, is what tells
            // a stale highlight to drop.
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                lastEventUptime = SystemClock.uptimeMillis()
                SessionController.instance?.onWindowStateChanged()
                OverlayService.onSystemWindowsChanged()
                dismissRapidoKeyboard()
            }

            // The keyboard opening or closing arrives here, not as an inset on
            // our own non-focusable window. This is what lets the assistant get
            // out of the IME's way.
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> {
                OverlayService.onSystemWindowsChanged()
                dismissRapidoKeyboard()
            }

            // The user physically tapped something. That — not a timer, and not
            // an action taken on their behalf — is what advances a guided step.
            //
            // Our own overlay pill is a real Android View, so its own button
            // taps (mic, next, stop) ALSO fire a genuine TYPE_VIEW_CLICKED —
            // system-wide, this service sees clicks in any window, including
            // its own. That click has already been handled directly by the
            // button's own onClickListener; routing it through onUserClicked()
            // as well fires onNextTapped(), whose first line is
            // abandonRecording() — killing a recording within milliseconds of
            // the very mic tap that started it, if a guided task happened to
            // be mid-flight. Measured on device: mic recording observed
            // starting and stopping 237ms later with no user action in
            // between. Ignoring self-originated events is the fix: a tap
            // inside a third-party app is the only thing this signal should
            // ever mean.
            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                lastEventUptime = SystemClock.uptimeMillis()
                if (event.packageName != packageName) {
                    SessionController.instance?.onUserClicked()
                }
            }
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        if (instance === this) instance = null
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        if (instance === this) instance = null
        return super.onUnbind(intent)
    }

    /**
     * Top edge of the on-screen keyboard in screen pixels, or 0 when no IME is
     * showing.
     *
     * Read from the accessibility window list rather than from WindowInsets:
     * the assistant overlay is FLAG_NOT_FOCUSABLE, so the IME is attached to
     * the *app's* window and never reports an ime() inset to ours — asking our
     * own window would always answer "no keyboard". The accessibility service
     * sees every window on the display regardless of who has focus, which is
     * the only vantage point that can answer this for an overlay.
     */
    fun imeTopPx(): Int = try {
        windows
            .filter { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD }
            .minOfOrNull { w -> Rect().also { w.getBoundsInScreen(it) }.top }
            ?: 0
    } catch (e: Exception) {
        // The window list throws while the display is changing.
        0
    }

    /** True when no UI change events have arrived for [quietMs]. */
    private fun isSettled(quietMs: Long = 250L): Boolean =
        SystemClock.uptimeMillis() - lastEventUptime >= quietMs

    /**
     * One snapshot of the current screen. Own-package nodes are skipped so the
     * assistant never observes its own overlay.
     */
    /**
     * The node tree to read.
     *
     * `rootInActiveWindow` alone is not enough: it returns null whenever the
     * focused window is not the one the user is looking at — which is routine
     * while an app is settling, when a bottom sheet or dialog owns focus, or
     * when our own overlay is in play. Measured on Swiggy: snapshot() returned
     * elements=0 for that reason, and the resolver was blamed for a screen it
     * had never been given.
     *
     * Falls back to the window list, preferring real application windows over
     * system ones and topmost over lower layers.
     */
    private fun resolveRoot(): AccessibilityNodeInfo? {
        rootInActiveWindow?.let { return it }
        return try {
            windows
                .filter {
                    it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION ||
                        it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_SYSTEM
                }
                .sortedWith(
                    compareBy<android.view.accessibility.AccessibilityWindowInfo> {
                        if (it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION) 0 else 1
                    }.thenByDescending { it.layer }
                )
                .firstNotNullOfOrNull { it.root }
        } catch (e: Exception) {
            // The window list can throw while the screen is changing.
            null
        }
    }


    fun snapshot(): ScreenSnapshot {
        val root = resolveRoot() ?: return ScreenSnapshot.EMPTY
        val pkg = root.packageName?.toString() ?: ""
        val elements = ArrayList<ScreenElement>()
        val counter = intArrayOf(0)
        walk(root, elements, counter)
        return ScreenSnapshot(pkg, isSettled(), elements)
    }

    /** Debug-only detail that uiautomator's XML omits: actions on matching nodes. */
    fun rapidoActionsForDebug(filter: String): List<String> {
        if (!BuildConfig.DEBUG || filter.isBlank()) return emptyList()
        val root = resolveRoot() ?: return emptyList()
        if (root.packageName?.toString() != "com.rapido.passenger") return emptyList()
        val lines = ArrayList<String>()
        fun visit(node: AccessibilityNodeInfo?) {
            if (node == null || lines.size >= 10) return
            val label = listOf(node.text, node.contentDescription, node.hintText)
                .mapNotNull { it?.toString() }.joinToString(" ")
            if (label.contains(filter, ignoreCase = true)) {
                val b = Rect().also { node.getBoundsInScreen(it) }
                val actions = node.actionList.joinToString { "${it.id}:${it.label ?: ""}" }
                lines += "label='${label.take(120)}' id=${node.viewIdResourceName} " +
                    "edit=${node.isEditable} click=${node.isClickable} bounds=$b actions=[$actions]"
            }
            for (i in 0 until node.childCount) visit(node.getChild(i))
        }
        visit(root)
        for (window in windows) {
            if (lines.size >= 10) break
            visit(window.root)
        }
        return lines
    }

    private fun walk(
        node: AccessibilityNodeInfo?,
        out: ArrayList<ScreenElement>,
        counter: IntArray,
    ) {
        if (node == null || counter[0] >= MAX_ELEMENTS) return

        val rid = node.viewIdResourceName?.substringAfterLast('/') ?: ""
        // Skip our own overlay chrome. Filtering by package name would be wrong
        // here: the guided demo screen lives in this same package, so a
        // package-level filter throws away the very screen we must read.
        if (rid in OVERLAY_IDS) return
        val rawText = node.text?.toString()?.trim().orEmpty()
        val rawDesc = node.contentDescription?.toString()?.trim().orEmpty()
        // An empty field is labelled ONLY by its hint ("Search for 'Sweets'",
        // "Where to?"). Without this every blank input on the phone is
        // invisible to the resolver — which is exactly the element the user is
        // most likely to be asking about.
        val rawHint = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            node.hintText?.toString()?.trim().orEmpty()
        } else {
            ""
        }
        val text = when {
            rawText.isNotEmpty() -> rawText
            rawDesc.isNotEmpty() -> rawDesc
            else -> rawHint
        }
        val hasSignal = rid.isNotEmpty() || text.isNotEmpty() ||
            node.isClickable || node.isEditable

        if (hasSignal) {
            val b = Rect().also { node.getBoundsInScreen(it) }
            if (b.width() > MIN_PX && b.height() > MIN_PX) {
                out.add(
                    ScreenElement(
                        index = counter[0]++,
                        resourceId = rid,
                        text = text,
                        className = node.className?.toString()?.substringAfterLast('.') ?: "View",
                        bounds = b,
                        editable = node.isEditable,
                        clickable = node.isClickable,
                    )
                )
            }
        }
        for (i in 0 until node.childCount) {
            walk(node.getChild(i), out, counter)
        }
    }

    /**
     * Finds a node by resource id or text and performs a click action.
     */
    fun performClick(resourceId: String, textAny: List<String>): Boolean {
        val root = rootInActiveWindow ?: return false
        val node = findNode(root, resourceId, textAny, true) ?: return false
        val success = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        node.recycle()
        return success
    }

    fun performSetText(resourceId: String, textAny: List<String>, textToType: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val node = findNode(root, resourceId, textAny, false) ?: return false
        val args = android.os.Bundle()
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, textToType)
        val success = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        node.recycle()
        return success
    }

    /** Tap a unique Rapido control at the bounds verified in a settled snapshot. */
    fun tapRapidoLabel(label: String, bounds: Rect): Boolean {
        if (RAPIDO_FORBIDDEN.containsMatchIn(label)) return false
        val root = resolveRoot() ?: return false
        if (root.packageName?.toString() != "com.rapido.passenger") return false
        val matches = ArrayList<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo?) {
            if (node == null) return
            val names = listOf(node.text, node.contentDescription, node.hintText)
                .mapNotNull { it?.toString()?.trim() }
            val b = Rect().also { node.getBoundsInScreen(it) }
            if (b == bounds && names.any { it.equals(label, ignoreCase = true) }) {
                matches.add(node)
            }
            for (i in 0 until node.childCount) visit(node.getChild(i))
        }
        visit(root)
        if (matches.size != 1 || bounds.width() < 20 || bounds.height() < 20) return false
        // Compose reported a successful accessibility click on a search-result
        // ancestor without navigating. A bounded physical gesture worked on
        // this same live row, so use it consistently after rechecking the
        // unique label and guarding booking controls.
        if (rapidoForbiddenAt(root, bounds.centerX(), bounds.centerY())) return false
        return rapidoGesture(bounds, 90L)
    }

    /** The sole booking capability, called after a fresh spoken vehicle choice. */
    fun bookRapidoRide(vehicleLabel: String, quotedFare: String, bounds: Rect): Boolean {
        if (vehicleLabel !in setOf("Bike", "Auto", "Cab Daily", "Cab Economy")) return false
        val root = resolveRoot() ?: return false
        if (root.packageName?.toString() != "com.rapido.passenger") return false
        var fareScreen = false
        val selectedRows = ArrayList<AccessibilityNodeInfo>()
        val rowContainers = ArrayList<AccessibilityNodeInfo>()
        val buttons = ArrayList<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo?) {
            if (node == null) return
            val description = node.contentDescription?.toString().orEmpty()
            val id = node.viewIdResourceName?.substringAfterLast('/').orEmpty()
            if (description == "Choose your ride Screen") fareScreen = true
            if (description.startsWith("$vehicleLabel ride. Estimated fare ") &&
                description.endsWith(".Selected.")) selectedRows.add(node)
            if (id == "fe_list_item_$vehicleLabel") rowContainers.add(node)
            if (id == "fe_book_now_btn" && description == "Double tap to book $vehicleLabel") {
                buttons.add(node)
            }
            for (i in 0 until node.childCount) visit(node.getChild(i))
        }
        visit(root)
        if (!fareScreen || selectedRows.size != 1 || rowContainers.size != 1 || buttons.size != 1) return false
        val selected = selectedRows.single()
        val selectedBounds = Rect().also { selected.getBoundsInScreen(it) }
        val containerBounds = Rect().also { rowContainers.single().getBoundsInScreen(it) }
        if (selectedBounds != containerBounds) return false
        val currentFare = Regex("₹\\s*[0-9][0-9,]*(?:\\.[0-9]{1,2})?")
            .findAll(selected.contentDescription?.toString().orEmpty()).map { it.value }.toList().singleOrNull()
        if (currentFare != quotedFare) return false
        val buttonBounds = Rect().also { buttons.single().getBoundsInScreen(it) }
        if (buttonBounds != bounds || buttonBounds.width() < 100 || buttonBounds.height() < 50) return false
        return rapidoGesture(bounds, 90L)
    }

    /** Enter a location in Rapido's custom Pickup or Drop view. */
    fun typeRapidoLocation(bounds: Rect, value: String, fieldId: String): Boolean {
        if (fieldId !in setOf("pickup_text", "drop_text") ||
            !PlaceNameNormalizer.isEnglishPlace(value)) return false
        rapidoTyping = true
        try {
            val root = resolveRoot() ?: return false
            if (root.packageName?.toString() != "com.rapido.passenger") return false
            val matches = ArrayList<AccessibilityNodeInfo>()
            fun visit(node: AccessibilityNodeInfo?) {
                if (node == null) return
                val b = Rect().also { node.getBoundsInScreen(it) }
                val matchingField = node.viewIdResourceName?.substringAfterLast('/') == fieldId &&
                    (node.contentDescription?.toString()?.startsWith("Enter ${if (fieldId == "drop_text") "Drop" else "Pickup"} location Input Field.") == true ||
                        node.contentDescription?.toString()?.startsWith("${if (fieldId == "drop_text") "Drop" else "Pickup"} Location is ") == true)
                if (b == bounds && matchingField) matches.add(node)
                for (i in 0 until node.childCount) visit(node.getChild(i))
            }
            visit(root)
            if (matches.size != 1) return false
            val target = matches.single()
            if (!target.isEditable) return pasteRapidoLocation(bounds, value, fieldId)
            val args = android.os.Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
            }
            return target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        } finally {
            rapidoTyping = false
            dismissRapidoKeyboard()
        }
    }

    private fun pasteRapidoLocation(bounds: Rect, value: String, fieldId: String): Boolean {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Ride Helper location", value))
        if (!rapidoGesture(bounds, 800L)) return false
        repeat(20) {
            val rapidoVisible = windows.any {
                it.root?.packageName?.toString() == "com.rapido.passenger"
            }
            if (!rapidoVisible) return false
            val paste = ArrayList<AccessibilityNodeInfo>()
            fun find(node: AccessibilityNodeInfo?) {
                if (node == null) return
                if (node.text?.toString() == "Paste" || node.contentDescription?.toString() == "Paste") {
                    var clickable: AccessibilityNodeInfo? = node
                    while (clickable != null && !clickable.isClickable) clickable = clickable.parent
                    if (clickable != null) paste.add(clickable)
                }
                for (i in 0 until node.childCount) find(node.getChild(i))
            }
            // Samsung's floating edit toolbar can be present in the active
            // tree without appearing in any of the enumerated window roots.
            find(resolveRoot())
            for (window in windows) find(window.root)
            val candidates = paste.distinctBy {
                Rect().also { b -> it.getBoundsInScreen(b) }.toShortString()
            }.filter {
                val b = Rect().also { rect -> it.getBoundsInScreen(rect) }
                b.centerX() in 0..bounds.right && b.centerY() in (bounds.top - 250)..(bounds.bottom + 250)
            }
            if (candidates.size > 1) return false
            val candidate = candidates.singleOrNull()
            val pasted = if (candidate == null) false else {
                val pasteBounds = Rect().also { candidate.getBoundsInScreen(it) }
                candidate.performAction(AccessibilityNodeInfo.ACTION_CLICK) ||
                    rapidoGesture(pasteBounds, 90L)
            }
            if (pasted) {
                repeat(20) {
                    val current = resolveRoot()
                    if (current?.packageName?.toString() == "com.rapido.passenger") {
                        val labels = ArrayList<String>()
                        fun collect(node: AccessibilityNodeInfo?) {
                            if (node == null) return
                            if (node.viewIdResourceName?.substringAfterLast('/') == fieldId) {
                                labels.add(node.contentDescription?.toString().orEmpty())
                            }
                            for (i in 0 until node.childCount) collect(node.getChild(i))
                        }
                        collect(current)
                        val prefix = if (fieldId == "drop_text") "Drop" else "Pickup"
                        if (labels.singleOrNull()?.startsWith("$prefix Location is $value.", ignoreCase = true) == true) {
                            return true
                        }
                    }
                    Thread.sleep(100)
                }
                return false
            }
            Thread.sleep(100)
        }
        return false
    }

    private fun rapidoForbiddenAt(root: AccessibilityNodeInfo, x: Int, y: Int): Boolean {
        fun scan(node: AccessibilityNodeInfo?): Boolean {
            if (node == null) return false
            val label = listOf(node.text, node.contentDescription)
                .mapNotNull { it?.toString() }.joinToString(" ")
            val b = Rect().also { node.getBoundsInScreen(it) }
            if (RAPIDO_FORBIDDEN.containsMatchIn(label) && b.contains(x, y)) return true
            for (i in 0 until node.childCount) if (scan(node.getChild(i))) return true
            return false
        }
        return scan(root)
    }

    private fun rapidoGesture(bounds: Rect, durationMs: Long): Boolean {
        val completed = AtomicBoolean(false)
        val done = CountDownLatch(1)
        Handler(Looper.getMainLooper()).post {
            if (resolveRoot()?.packageName?.toString() != "com.rapido.passenger") {
                done.countDown()
                return@post
            }
            val restorePill = OverlayService.hidePillForRapidoGesture()
            Handler(Looper.getMainLooper()).postDelayed({
                if (resolveRoot()?.packageName?.toString() != "com.rapido.passenger") {
                    restorePill()
                    done.countDown()
                    return@postDelayed
                }
                val path = Path().apply { moveTo(bounds.centerX().toFloat(), bounds.centerY().toFloat()) }
                val gesture = GestureDescription.Builder()
                    .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
                    .build()
                val started = dispatchGesture(gesture, object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        restorePill()
                        completed.set(true)
                        done.countDown()
                    }
                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        restorePill()
                        done.countDown()
                    }
                }, Handler(Looper.getMainLooper()))
                if (!started) {
                    restorePill()
                    done.countDown()
                }
            }, 60L)
        }
        return done.await(durationMs + 2_500L, TimeUnit.MILLISECONDS) && completed.get()
    }

    private fun findNode(node: AccessibilityNodeInfo?, resourceId: String, textAny: List<String>, requiresClickable: Boolean): AccessibilityNodeInfo? {
        if (node == null) return null
        
        val rid = node.viewIdResourceName?.substringAfterLast('/') ?: ""
        if (rid in OVERLAY_IDS) return null
        
        val validAction = if (requiresClickable) node.isClickable else (node.isClickable || node.isEditable)
        if (resourceId.isNotEmpty() && rid == resourceId && validAction) {
            return AccessibilityNodeInfo.obtain(node)
        }
        
        val text = (node.text ?: node.contentDescription)?.toString() ?: ""
        if (textAny.isNotEmpty() && textAny.any { it.equals(text, ignoreCase = true) } && validAction) {
            return AccessibilityNodeInfo.obtain(node)
        }

        // Search children
        for (i in 0 until node.childCount) {
            val found = findNode(node.getChild(i), resourceId, textAny, requiresClickable)
            if (found != null) return found
        }
        return null
    }

    companion object {
        /**
         * Real app screens are far bigger than the old 120 cap suggested:
         * Swiggy's home screen alone reports 304 nodes, and its search bar sits
         * at #280 — so the depth-first walk truncated before ever reaching the
         * one control the user was asking for, and the resolver was blamed for
         * a target it had never been shown.
         *
         * Kept bounded (a runaway tree would stall the poll loop), but high
         * enough to cover ordinary consumer apps.
         */
        private const val MAX_ELEMENTS = 600
        private const val MIN_PX = 4
        private val RAPIDO_FORBIDDEN = Regex("(?i)\\b(book|pay|confirm|otp|submit)\\b|बुक|भुगतान|पुष्टि|ओटीपी")

        /** View ids belonging to the floating overlay itself — never guidance targets. */
        private val OVERLAY_IDS = setOf(
            "pill_root", "pill_row", "card_body", "state_dot", "pill_label",
            "instruction_text", "mic_button", "next_button", "debug_panel",
        )

        @Volatile
        var instance: ScreenReaderService? = null
            private set
    }
}
