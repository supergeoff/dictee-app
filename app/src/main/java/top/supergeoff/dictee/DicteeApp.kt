package top.supergeoff.dictee

import android.app.Application

/** Application : porte l'unique [AppModel], créé au premier usage. */
class DicteeApp : Application() {
    val model: AppModel by lazy { AppModel(this) }
}
