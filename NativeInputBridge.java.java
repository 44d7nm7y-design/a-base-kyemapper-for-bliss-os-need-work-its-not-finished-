package xtr.keymapper.input;

import android.util.Log;

public class NativeInputBridge {
    private static final String TAG = "NativeInputBridge";

    static {
        try {
            // Loads 'libnative_input.so' compiled from C++
            System.loadLibrary("native_input");
            Log.i(TAG, "Native input library loaded successfully.");
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "Failed to load native_input library", e);
        }
    }

    /**
     * Native method to initialize the C++ kernel interface/device connection.
     * @param devicePath Path to the device node (e.g., "/dev/uinput" or custom kernel node)
     * @return true if successfully opened and initialized, false otherwise.
     */
    public native boolean initNativeDevice(String devicePath);

    /**
     * Native method to send raw Linux key events directly down to the C++ native layer.
     * @param scanCode Linux input event code (e.g., KEY_A = 30)
     * @param value    1 for KEY_PRESS, 0 for KEY_RELEASE, 2 for KEY_REPEAT
     * @return true if event dispatch succeeded.
     */
    public native boolean sendKeyEvent(int scanCode, int value);

    /**
     * Native method to release and close the native file descriptor.
     */
    public native void closeNativeDevice();
}