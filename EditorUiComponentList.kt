package xtr.keymapper.editor

import android.content.Context
import androidx.annotation.MenuRes
import xtr.keymapper.R
import xtr.keymapper.editor.component.Camera
import xtr.keymapper.editor.component.Crosshair
import xtr.keymapper.editor.component.Dpad
import xtr.keymapper.editor.component.Key
import xtr.keymapper.editor.component.MouseWalk
import xtr.keymapper.editor.component.RightClick
import xtr.keymapper.editor.component.SwipeKey

internal class EditorUiComponentList : ArrayList<EditorUiComponent>() {

    fun newFactory(callback: EditorUiComponentCallback, context: Context): Factory {
        return Factory(callback, context)
    }

    fun addMatchingComponentForId(
        @MenuRes id: Int,
        callback: EditorUiComponentCallback?,
        context: Context?,
        x: Float,
        y: Float,
        addComponent: (EditorUiComponent) -> Boolean
    ) = when (id) {
        R.id.add -> Key(callback, context, x, y)
        R.id.crosshair -> Crosshair(callback, context, x, y)
        R.id.swipe_key -> SwipeKey(callback, context, x, y)
        R.id.mouse_right -> RightClick(callback, context, x, y)
        R.id.camera -> Camera(callback, context, x, y)
        R.id.mouse_walk -> MouseWalk(callback, context, x, y)
        R.id.dpad -> {
            Dpad(callback, context, x, y).pickType { addComponent(it) }
            null
        }
        R.id.mouse_left -> {
            forEachComponentOfClass(Crosshair::class.java) { crosshair ->
                crosshair.animateLeftClick(x, y)
            }
            null
        }
        else -> null
    }?.let(addComponent)

    inline fun <reified T : EditorUiComponent> forEachComponentOfClass(action: (T) -> Unit) {
        for (component in this) {
            if (component is T) {
                action(component)
            }
        }
    }

    fun <T> forEachComponentOfClass(clazz: Class<T>, action: (T) -> Unit) {
        for (component in this) {
            if (clazz.isInstance(component)) {
                action(clazz.cast(component)!!)
            }
        }
    }

    internal inner class Factory(
        private val mCallback: EditorUiComponentCallback?,
        private val context: Context?
    ) {
        fun addKeys(keys: List<xtr.keymapper.keymap.element.Key?>) {
            keys.forEach { key ->
                if (key != null) {
                    add(Key(mCallback, context, key))
                }
            }
        }

        fun addSwipeKeys(swipeKeys: List<xtr.keymapper.keymap.element.SwipeKey?>) {
            swipeKeys.forEach { swipeKey ->
                if (swipeKey != null) {
                    add(SwipeKey(mCallback, context, swipeKey))
                }
            }
        }

        fun addDpads(dpadArray: Array<xtr.keymapper.keymap.element.Dpad?>) {
            for (dpad in dpadArray) {
                if (dpad != null) {
                    add(Dpad(mCallback, context, dpad))
                }
            }
        }
    }
}