package io.flutter.plugins.webviewflutter

import android.app.Activity
import android.content.Intent
import android.util.Log

object ChooseFileIns {
    const val REQUEST_CODE = 6001
    val TAG = ChooseFileIns::class.java.simpleName
    var activity: Activity? = null

    val resCbMap = mutableMapOf<Int, BiConsumer<Int, Intent?>>()

    fun startChoose(pickIntent: Intent, reqCode: Int, cb: BiConsumer<Int, Intent?>) {
        activity?.startActivityForResult(pickIntent, reqCode)
        resCbMap[reqCode] = cb
    }

    fun onActivityResultData(requestCode: Int, resultCode: Int, data: Intent?) {
        Log.v(TAG, "enter onActivityResultData")
        if (resCbMap[requestCode] != null) {
            resCbMap[requestCode]?.accept(resultCode, data)
        }
    }
}

interface BiConsumer<T, U> {
    fun accept(t: T, u: U?)
}

