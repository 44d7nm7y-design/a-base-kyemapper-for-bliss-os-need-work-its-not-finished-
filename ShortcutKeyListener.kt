package xtr.keymapper.editor

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.EditText

fun onKeyboardKeyPress(view: View, keyCode: Int, event: KeyEvent): Boolean {
    val key = event.displayLabel.toString()
    if (view is EditText) {
        if (key.matches("[a-zA-Z0-9]+".toRegex())) {
            view.setText(key)
        } else {
            view.text.clear()
        }
    }
    return true
}

fun onMouseClick(v: View?, event: MotionEvent?): Boolean {
    if (v !is EditText || event == null) return false

    if (event.isFromSource(InputDevice.SOURCE_CLASS_POINTER)) {
        if (event.action == MotionEvent.ACTION_BUTTON_PRESS) {
            when (event.actionButton) {
                MotionEvent.BUTTON_TERTIARY -> {
                    v.setText("MMB")
                    return true
                }
                MotionEvent.BUTTON_SECONDARY -> {
                    v.setText("RMB")
                    return true
                }
            }
        }
    }
    return false
}

fun keyCodePrefix(e: EditText?): String {
    val text = e?.text?.toString().orEmpty()
    return "KEY_$text"
}