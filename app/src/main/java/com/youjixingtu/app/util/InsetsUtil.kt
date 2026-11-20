package com.youjixingtu.app.util

import android.app.Activity
import android.graphics.Color
import android.os.Build
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding

/**
 * 强制处理系统栏 insets。
 *
 * 部分国产 ROM / Android 15+ 会忽略 windowOptOutEdgeToEdgeEnforcement，
 * 内容仍顶进状态栏、底部导航被手势条盖住。这里不依赖该开关，而是直接读取
 * 真实的 systemBars insets，给根视图加等高的 top/bottom padding，并把系统栏
 * 设成不透明底色，保证任何机型都不重叠。
 */
object InsetsUtil {

    fun apply(activity: Activity, root: View) {
        // 系统栏设为不透明底色，避免内容透到状态栏/导航栏下面
        val window = activity.window
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            window.statusBarColor = Color.parseColor("#050811")
            window.navigationBarColor = Color.parseColor("#050811")
        }
        // 让 decor 不自动 fit：内容先按全屏布局，再由下面的 padding 精确控制，
        // 避免「decor fit + 手动 padding」两套机制叠加造成顶部空白过大。
        WindowCompat.setDecorFitsSystemWindows(window, false)

        // 记录根视图原有 padding（如 activity_main 的 paddingTop=8dp）
        val originalTop = root.paddingTop
        val originalBottom = root.paddingBottom

        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            // v1.15：同时处理输入法(IME) insets——edge-to-edge 下窗口不会自动给键盘让位，
            // 键盘弹出时会盖住输入框。底部取 systemBars 与 IME 的较大值，
            // 键盘弹出时把整个内容顶上去，输入框始终可见。
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            val bottom = maxOf(bars.bottom, ime.bottom)
            v.updatePadding(
                left = bars.left,
                top = originalTop + bars.top,
                right = bars.right,
                bottom = originalBottom + bottom
            )
            // 返回消费后的 insets，避免子视图重复叠加
            WindowInsetsCompat.CONSUMED
        }
        ViewCompat.requestApplyInsets(root)
    }
}
