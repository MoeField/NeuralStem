package pub.miku.neuralstem.player

import android.os.Bundle
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult

/** 自定义命令的成功结果（`msg` 默认 "ok"）。 */
internal fun ok(msg: String = "ok"): SessionResult =
    SessionResult(SessionResult.RESULT_SUCCESS, Bundle().apply { putString("msg", msg) })

/** 自定义命令的失败结果（错误码语义与历史实现一致）。 */
internal fun err(msg: String): SessionResult =
    SessionResult(SessionError.ERROR_BAD_VALUE, Bundle().apply { putString("error", msg) })
