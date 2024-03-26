package ovh.plrapps.mapcompose.utils

import android.content.res.Resources

actual fun dpToPx(dp: Float): Float = dp * Resources.getSystem().displayMetrics.density