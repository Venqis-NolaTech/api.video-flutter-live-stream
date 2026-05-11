package video.api.flutter.livestream

import android.util.Log
import io.github.thibaultbee.streampack.core.logger.ILogger
import io.github.thibaultbee.streampack.core.logger.Logger as StreamPackLogger
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Forwards StreamPack internal logs to [android.util.Log] for debugging preview/stream issues.
 */
internal object StreamPackAndroidLoggerInstaller {
    private val installed = AtomicBoolean(false)

    fun installOnce() {
        if (!installed.compareAndSet(false, true)) return
        StreamPackLogger.logger = object : ILogger {
            override fun e(tag: String, message: String, tr: Throwable?) {
                if (tr != null) Log.e(tag, message, tr) else Log.e(tag, message)
            }

            override fun w(tag: String, message: String, tr: Throwable?) {
                if (tr != null) Log.w(tag, message, tr) else Log.w(tag, message)
            }

            override fun i(tag: String, message: String, tr: Throwable?) {
                if (tr != null) Log.i(tag, message, tr) else Log.i(tag, message)
            }

            override fun v(tag: String, message: String, tr: Throwable?) {
                if (tr != null) Log.v(tag, message, tr) else Log.v(tag, message)
            }

            override fun d(tag: String, message: String, tr: Throwable?) {
                if (tr != null) Log.d(tag, message, tr) else Log.d(tag, message)
            }
        }
    }
}
