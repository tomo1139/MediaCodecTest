package develop.tomo1139.mediacodectest

import android.util.Log

/**
 * Created by tomo on 2017/06/24.
 */

object D {

    val TAG = "dbg"

    fun sleep(milliSeconds: Long) {
        D.p("sleep start")
        try {
            Thread.sleep(milliSeconds)
        } catch (e: InterruptedException) {
            e.printStackTrace()
        }

        D.p("sleep end")
    }

    fun p(arg1: Any) {
        if (!BuildConfig.DEBUG) {
            return
        }
        val e = Throwable().stackTrace
        val classNames = e[1].className.split("\\.".toRegex()).dropLastWhile({ it.isEmpty() }).toTypedArray()
        val className = classNames[classNames.size - 1]
        val classNameAndMethodName = className + " " + e[1].methodName + "() " + "line:" + e[1].lineNumber
        Log.e(TAG, classNameAndMethodName + " >>> " + arg1)
    }

    fun printStackTrace() {
        if (!BuildConfig.DEBUG) {
            return
        }
        Log.e(TAG, "\n========== printStackTrace ==========")
        val e = Throwable().stackTrace
        var s: String
        for (i in e.size - 1 .. 1) {
            s = "[" + e[i].fileName + " l." + e[i].lineNumber + "] " + e[i].className + " " + e[i].methodName + "()"
            Log.e(TAG, s)
        }
        Log.e(TAG, "=====================================\n\n")
    }

    private var time: Long = 0
    fun onTimeStamp() {
        val t = System.currentTimeMillis()
        Log.e(TAG, t.toString() + " : " + if (time.toInt() == 0) 0 else t - time)
        time = t
    }
}
