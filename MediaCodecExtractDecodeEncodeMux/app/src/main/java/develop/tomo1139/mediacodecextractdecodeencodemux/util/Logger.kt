package develop.tomo1139.mediacodecextractdecodeencodemux.util

import android.util.Log


object Logger {
    fun e(arg1: Any) {
        val e = Throwable().stackTrace
        val classNames = e[1].className.split("\\.".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
        val className = classNames[classNames.size - 1]
        val classNameAndMethodName = className + " " + e[1].methodName + "() " + "line:" + e[1].lineNumber
        Log.e("debug", classNameAndMethodName + " >>> " + arg1)
    }
}