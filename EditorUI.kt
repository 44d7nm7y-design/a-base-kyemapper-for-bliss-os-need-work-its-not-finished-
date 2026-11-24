package xtr.keymapper.editor

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.View.OnLayoutChangeListener
import android.view.ViewGroup
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.ui.platform.ComposeView
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import xtr.keymapper.R
import xtr.keymapper.Utils
import xtr.keymapper.activity.MainActivity
import xtr.keymapper.editor.component.Camera
import xtr.keymapper.editor.component.Crosshair
import xtr.keymapper.editor.component.MouseWalk
import xtr.keymapper.editor.component.RightClick
import xtr.keymapper.keymap.KeymapConfig
import xtr.keymapper.keymap.KeymapProfile
import xtr.keymapper.keymap.KeymapProfiles
import xtr.keymapper.macro.MacroIdUtils
import xtr.keymapper.macro.MacroStatus
import xtr.keymapper.macro.MacroView
import xtr.keymapper.macro.MacroView.OnFinishListener

class EditorUI(
    private val context: Context,
    private val editorCallback: EditorCallback?,
    private val profileName: String?,
    private val startMode: Int
) : LifecycleOwner, SavedStateRegistryOwner {

    // Receives keyboard input while editing a key binding
    private var keyInFocus: KeyInFocusListener? = null

    private val mHandler = Handler(Looper.getMainLooper())

    /* The active keymap config and a backup copy */
    private var profile: KeymapProfile? = null
    private var profileBackup: KeymapProfile? = null

    private var overlayOpen = false

    // The settings/catalog UI (hidden in SHOW_KEYMAP_ONLY mode)
    private val settingsOverlay: SettingsOverlay?

    // The main editor view and the container holding all key buttons
    private val mainView: ViewGroup?
    private val keysContainerView: ViewGroup

    private var macroDialog: MacroDialog? = null

    private val editorUiComponents = EditorUiComponentList()

    // Compose Dialog Management
    private var importExportComposeView: ComposeView? = null
    private var importExportLifecycle: LifecycleRegistry? = null
    private var importExportSavedStateController: SavedStateRegistryController? = null

    // LifecycleOwner implementation
    private val lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle = lifecycleRegistry

    // SavedStateRegistryOwner implementation
    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    override val savedStateRegistry: SavedStateRegistry = savedStateRegistryController.savedStateRegistry

    init {
        val layoutInflater = context.getSystemService(LayoutInflater::class.java)

        if (startMode != SHOW_KEYMAP_ONLY) {
            context.stopService(Intent(context, ShowKeymapService::class.java))
            settingsOverlay = SettingsOverlay(context, startMode)
            mainView = settingsOverlay.createView(layoutInflater)
            keysContainerView = settingsOverlay.binding.keyContainer

            settingsOverlay.inflateMenuResource(startMode, layoutInflater)
            settingsOverlay.setOnActionSelectedListener { id: Int ->
                this.onActionSelected(id)
            }
        } else {
            keysContainerView = FrameLayout(context)
            keysContainerView.layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            settingsOverlay = null
            mainView = null
        }
    }

    interface KeyInFocusListener {
        fun setText(key: String?)
    }

    fun open(overlayWindow: Boolean) {
        settingsOverlay?.overlayWindow = overlayWindow
        if (mainView?.windowToken == null && mainView?.parent == null) {
            if (overlayWindow) {
                openOverlayWindow()
            } else {
                overlayOpen = false
                if (context is EditorActivity) {
                    (context as Activity).setContentView(mainView)
                } else { // For MainActivity
                    (context as Activity).addContentView(
                        mainView,
                        ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                    )
                }
            }
        }

        mainView?.setOnKeyListener(View.OnKeyListener { v: View?, keyCode: Int, event: KeyEvent? ->
            if (event != null) {
                this.onKey(v, keyCode, event)
            } else {
                false
            }
        })
        mainView?.isFocusable = true

        loadKeymapAfterView()
    }

    fun openSettings() {
        (context as MainActivity).addContentView(
            mainView,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
    }

    fun showControls(alpha: Float) {
        openOverlayWindow(alpha)
    }

    private fun openOverlayWindow(alpha: Float = -1f) {
        val overlayView = (if (startMode == SHOW_KEYMAP_ONLY) keysContainerView else mainView)!!
        if (overlayOpen) {
            removeView(overlayView)
        }

        val mWindowManager = context.getSystemService(WindowManager::class.java)
        val mParams = if (startMode == SHOW_KEYMAP_ONLY) {
            Utils.getPointerLayoutParams(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
            )
        } else {
            WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else WindowManager.LayoutParams.TYPE_SYSTEM_ALERT,
                WindowManager.LayoutParams.FLAG_FULLSCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR,
                PixelFormat.TRANSLUCENT
            )
        }

        if (alpha < 1 && alpha > 0) overlayView.alpha = alpha

        mWindowManager.addView(overlayView, mParams)
        overlayOpen = true
        if (startMode != SHOW_KEYMAP_ONLY) hideSystemBars()
    }

    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val overlayView = (if (startMode == SHOW_KEYMAP_ONLY) keysContainerView else mainView)!!
            val windowInsetsController = overlayView.windowInsetsController
            if (windowInsetsController != null) {
                windowInsetsController.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    /**
     * For events received by view
     * @return true if we consume the event
     */
    fun onKey(v: View?, keyCode: Int, event: KeyEvent): Boolean {
        if (keyInFocus != null) {
            val key = event.displayLabel.toString()
            if (key.matches("[a-zA-Z0-9]+".toRegex())) {
                keyInFocus!!.setText(key)
                return true
            }
        }
        return false
    }

    /**
     * Called when a button in catalog has been clicked.
     * 
     * @param id the relevant id of the menu item for the card.
     */
    fun onActionSelected(id: Int) {
        val defaultX = mainView!!.pivotX
        val defaultY = mainView.pivotY

        when (id) {
            R.id.save -> {
                hideView()
            }
            R.id.macro -> {
                showMacroDialog()
            }
            R.id.reset -> {
                profile = profileBackup
                reloadKeymap()
            }
            R.id.import_export -> {
                val configString = generateConfigString()
                showImportExportDialog(configString)
            }
            else -> {
                editorUiComponents.addMatchingComponentForId(
                    id,
                    mCallback,
                    context,
                    defaultX,
                    defaultY
                ) { editorUiComponents.add(it) }
            }
        }
    }

    private fun showMacroDialog() {
        macroDialog = MacroDialog(context, overlayOpen, profile)
        macroDialog!!.show(View.OnClickListener { v: View? ->
            if (v != null) addMacro()
            if (macroDialog != null) {
                macroDialog!!.dismiss()
                macroDialog = null
            }
        })
    }

    private fun addMacro() {
        mainView!!.isFocusable = true
        val macroStatus = MacroStatus(context, settingsOverlay!!.binding.catalog)
        val macroView = MacroView(context, OnFinishListener { macroView_: MacroView? ->
            macroStatus.stop()

            keysContainerView.removeView(macroView_)
            macroView_!!.invalidate()

            mainView.setOnKeyListener(View.OnKeyListener { v: View?, keyCode: Int, event: KeyEvent? ->
                this@EditorUI.onKey(v, keyCode, event!!)
            })
            settingsOverlay.unHideButtons()
            showMacroDialog()
        })

        settingsOverlay.hideButtons()
        macroStatus.start()

        keysContainerView.addView(macroView)
        mainView.setOnKeyListener(View.OnKeyListener { v: View?, keyCode: Int, event: KeyEvent? ->
            macroView.onKey(event)
        })

        settingsOverlay.binding.catalog.setOnClickListener { macroView.clearCanvasAndFinish() }
    }

    fun hideView() {
        if (startMode == START_EDITOR) saveKeymap()
        if (startMode != SHOW_KEYMAP_ONLY) {
            settingsOverlay!!.onDestroyView()

            removeView(mainView!!)
            editorCallback?.onHideView()

            val keymapConfig = KeymapConfig(context)
            if (keymapConfig.showControls) {
                ShowKeymapService.start(context, profileName)
            }
        } else {
            removeView(keysContainerView)
        }

        closeImportExportDialog()
    }

    private fun removeView(view: ViewGroup) {
        if (overlayOpen && view.isAttachedToWindow) {
            context.getSystemService(WindowManager::class.java).removeView(view)
        }
        view.removeAllViews()
        view.invalidate()
    }

    fun loadKeymapAfterView() {
        profileBackup = KeymapProfiles(context).getProfile(profileName, false)
        keysContainerView.addOnLayoutChangeListener(OnLayoutChangeListener { v: View?, left: Int, top: Int, right: Int, bottom: Int, oldLeft: Int, oldTop: Int, oldRight: Int, oldBottom: Int ->
            if (right != oldRight || left != oldLeft || top != oldTop || bottom != oldBottom) {
                if (profile != null) {
                    saveKeymap()
                    reloadKeymap()
                }
            }
        })
        keysContainerView.post { this.loadKeymap() }
    }

    private fun reloadKeymap() {
        keysContainerView.removeAllViews()
        editorUiComponents.clear()
        loadKeymap()
    }

    private fun loadKeymap() {
        profile = KeymapProfiles(context).getProfile(profileName, false)

        profile!!.scale(
            keysContainerView.width.toFloat(),
            keysContainerView.height.toFloat()
        )

        val editorUiComponentFactory = editorUiComponents.newFactory(mCallback, context)

        editorUiComponentFactory.addKeys(profile!!.keys)
        editorUiComponentFactory.addSwipeKeys(profile!!.swipeKeys)
        editorUiComponentFactory.addDpads(profile!!.dpadArray)

        if (profile!!.mouseAimConfig != null) {
            editorUiComponents.add(Crosshair(mCallback, context, profile!!.mouseAimConfig))
        }
        if (profile!!.rightClick != null) {
            editorUiComponents.add(RightClick(mCallback, context, profile!!.rightClick))
        }
        if (profile!!.camera != null) {
            editorUiComponents.add(
                Camera(
                    mCallback,
                    context,
                    profile!!.camera.x,
                    profile!!.camera.y
                )
            )
        }
        if (profile!!.mouseWalk != null) {
            editorUiComponents.add(MouseWalk(mCallback, context, profile!!.mouseWalk))
        }
    }

    private fun saveKeymap() {
        val linesToWrite = ArrayList<String?>()

        editorUiComponents.forEach { linesToWrite.add(it.dataLine) }

        MacroIdUtils.getLines(linesToWrite, profile)

        val profiles = KeymapProfiles(context)

        profiles.saveProfile(
            profileName, linesToWrite, profile!!.packageName, !profile!!.disabled,
            keysContainerView.width, keysContainerView.height
        )
    }

    private fun generateConfigString(): String {
        val linesToWrite = ArrayList<String>()
        editorUiComponents.forEach { linesToWrite.add(it.dataLine) }

        MacroIdUtils.getLines(linesToWrite, profile)
        return linesToWrite.joinToString("\n")
    }

    private fun handleImportAction() {
        // TODO: Implement import logic
    }

    private fun handleExportAction() {
        // TODO: Implement export logic
    }

    private fun showImportExportDialog(configCode: String) {
        if (importExportComposeView != null) return

        importExportLifecycle = LifecycleRegistry(this)
        importExportSavedStateController = SavedStateRegistryController.create(this)
        importExportSavedStateController?.performAttach()
        importExportSavedStateController?.performRestore(null)
        importExportLifecycle?.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)

        importExportComposeView = ComposeView(context)
        
        importExportComposeView!!.setViewTreeLifecycleOwner(this)
        importExportComposeView!!.setViewTreeSavedStateRegistryOwner(this)

        importExportComposeView!!.setContent {
            ImportExportDialog(
                configCode,
                { closeImportExportDialog() },
                { handleImportAction() },
                { handleExportAction() }
            )
        }

        if (context is Activity) {
            context.addContentView(
                importExportComposeView,
                ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            )
        } else {
            val wm = context.getSystemService(WindowManager::class.java)
            val params = WindowManager.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_SYSTEM_ALERT,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                PixelFormat.TRANSLUCENT
            )
            wm.addView(importExportComposeView, params)
        }
        
        importExportLifecycle!!.handleLifecycleEvent(Lifecycle.Event.ON_START)
        importExportLifecycle!!.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    private fun closeImportExportDialog() {
        val view = importExportComposeView ?: return
        
        importExportLifecycle?.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        importExportLifecycle?.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        importExportLifecycle?.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)

        try {
            if (overlayOpen && view.isAttachedToWindow) {
                context.getSystemService(WindowManager::class.java).removeView(view)
            }
        } catch (_: IllegalArgumentException) {
            // ignored
        }

        importExportComposeView = null
    }

    private val mCallback: EditorUiComponentCallback = object : EditorUiComponentCallback {
        override fun isOverlayOpen(): Boolean {
            return overlayOpen
        }

        override fun getProfile(): KeymapProfile? = this@EditorUI.profile

        override fun getKeysContainerView(): ViewGroup = this@EditorUI.keysContainerView

        override fun removeComponent(component: EditorUiComponent?, vararg viewsToRemove: View?) {
            for (view in viewsToRemove) {
                keysContainerView.removeView(view)
            }
            editorUiComponents.remove(component)
        }

        override fun setOnKeyListener(keyInFocusListener: KeyInFocusListener?) {
            keyInFocus = keyInFocusListener
        }
    }

    companion object {
        private const val TAG = "EditorUI"

        /* Start modes */
        const val START_SETTINGS: Int = 0 // Shows full editor with catalog/menu
        const val START_EDITOR: Int = 1 // Shows editor without catalog (save/reset available)
        const val SHOW_KEYMAP_ONLY: Int = 2 // Used to show an overlay with active keymapping
        
        fun resizeView(view: View, x: Int, y: Int) {
            val layoutParams = view.layoutParams
            layoutParams.width += x
            layoutParams.height += y
            view.requestLayout()
        }
    }
}