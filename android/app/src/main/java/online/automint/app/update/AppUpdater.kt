package online.automint.app.update

import android.app.Activity
import android.content.Context

class AppUpdater(context: Context, ui: UpdateUi) {

    private val strategy: UpdateStrategy = SelfHostedUpdateStrategy(context, ui)

    fun check(activity: Activity) = strategy.check(activity)

    fun onDestroy() = strategy.onDestroy()

}
