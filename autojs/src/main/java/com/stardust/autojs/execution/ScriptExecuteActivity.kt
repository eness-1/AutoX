package com.stardust.autojs.execution

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isNotEmpty
import androidx.lifecycle.ViewModel
import com.aiselp.autox.utils.loadScriptExecute
import com.aiselp.autox.utils.saveScriptExecute
import com.aiselp.autox.utils.setupStartIntent
import com.stardust.autojs.ScriptEngineService
import com.stardust.autojs.annotation.ScriptInterface
import com.stardust.autojs.core.eventloop.EventEmitter
import com.stardust.autojs.core.eventloop.SimpleEvent
import com.stardust.autojs.engine.JavaScriptEngine
import com.stardust.autojs.engine.LoopBasedJavaScriptEngine
import com.stardust.autojs.engine.LoopBasedJavaScriptEngine.ExecuteCallback
import com.stardust.autojs.engine.ScriptEngine
import com.stardust.autojs.engine.ScriptEngineManager
import com.stardust.autojs.execution.ExecutionConfig.CREATOR.tag
import com.stardust.autojs.execution.ScriptExecuteActivity.ActivityScriptExecution
import com.stardust.autojs.execution.ScriptExecuteActivity.Companion.EXTRA_EXECUTION_ID
import com.stardust.autojs.execution.ScriptExecution.AbstractScriptExecution
import com.stardust.autojs.runtime.ScriptRuntime
import com.stardust.toast
import org.mozilla.javascript.ContinuationPending

/**
 * Created by Stardust on 2017/2/5.
 */
class ScriptExecuteActivity : AppCompatActivity() {
    val model by viewModels<Model>()
    val eventEmitter
        @ScriptInterface get() = model.eventEmitter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val execution = loadDef() ?: loadScriptExecute(intent, savedInstanceState)
        if (execution == null) {
            toast(this, "脚本环境异常")
            super.finish()
            return
        }
        if (execution is ActivityScriptExecution)
            execution.createEngine(this)
        model.init(execution)
        runScript(model.scriptExecution)
        emit("create", savedInstanceState)
    }

    fun onException(e: Throwable) {
        model.onException(e)
        super.finish()
    }

    override fun finish() {
        model.finish()
        super.finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(LOG_TAG, "onDestroy")
        model.destroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.saveScriptExecute(model.scriptExecution.source, model.scriptExecution.config)
        super.onSaveInstanceState(outState)
        emit("save_instance_state", outState)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val event = SimpleEvent()
        emit("back_pressed", event)
        if (!event.consumed) {
            super.onBackPressed()
        }
    }

    override fun onPause() {
        emit("pause")
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        emit("resume")
    }

    override fun onRestart() {
        super.onRestart()
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        emit("restore_instance_state", savedInstanceState)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val e = SimpleEvent()
        emit("key_down", keyCode, event, e)
        return e.consumed || super.onKeyDown(keyCode, event)
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        val e = SimpleEvent()
        emit("generic_motion_event", event, e)
        return super.onGenericMotionEvent(event)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        emit("activity_result", requestCode, resultCode, data)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        emit("create_options_menu", menu)
        return menu.isNotEmpty()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        val e = SimpleEvent()
        emit("options_item_selected", e, item)
        return e.consumed || super.onOptionsItemSelected(item)
    }

    fun emit(event: String, vararg args: Any?) {
        model.emit(event, *args)
    }

    class ActivityScriptExecution(
        private val mScriptEngineManager: ScriptEngineManager,
        task: ScriptExecutionTask?
    ) : AbstractScriptExecution(task) {
        @Volatile
        private var mScriptEngine: ScriptEngine<*>? = null
        fun createEngine(activity: Activity?): ScriptEngine<*> {
            mScriptEngine?.forceStop()
            mScriptEngine = mScriptEngineManager.createEngineOfSourceOrThrow(source, id)
            mScriptEngine!!.setTag(tag, config)
            return mScriptEngine!!
        }

        override fun getEngine(): ScriptEngine<*>? {
            return mScriptEngine
        }
    }

    class Model : ViewModel() {
        lateinit var scriptEngine: ScriptEngine<*>
        var executionListener: ScriptExecutionListener? = null
        lateinit var scriptExecution: ScriptExecution
        lateinit var runtime: ScriptRuntime
        lateinit var eventEmitter: EventEmitter

        fun init(execution: ScriptExecution) {
            scriptExecution = execution
            this.scriptEngine = execution.engine
            executionListener = execution.listener
            runtime = (execution.engine as JavaScriptEngine).runtime
            eventEmitter = EventEmitter(runtime.bridges)
        }

        fun onException(e: Throwable) {
            executionListener?.onException(scriptExecution, e)
        }

        fun onSuccess(result: Any?) {
            executionListener?.onSuccess(scriptExecution, result)
        }

        fun onStart() = executionListener?.onStart(scriptExecution)

        fun finish() {
            val exception = scriptEngine.uncaughtException
            if (exception != null) {
                onException(exception)
            } else {
                onSuccess(null)
            }
        }

        fun destroy() {
            if (::scriptEngine.isInitialized) {
                scriptEngine.put("activity", null)
                scriptEngine.setTag("activity", null)
                scriptEngine.destroy()
            }
        }

        fun emit(event: String, vararg args: Any?) {
            try {
                eventEmitter.emit(event, *args)
            } catch (e: Exception) {
                runtime.exit(e)
            }
        }
    }

    companion object {
        private const val LOG_TAG = "ScriptExecuteActivity"
        val EXTRA_EXECUTION_ID = ScriptExecuteActivity::class.java.name + ".execution_id"

        fun start(context: Context, execution: ActivityScriptExecution) {
            val i = Intent(context, ScriptExecuteActivity::class.java)
                .putExtra(EXTRA_EXECUTION_ID, execution.id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .addFlags(execution.config.intentFlags)
            setupStartIntent(i, execution.source, execution.config)
            context.startActivity(i)
        }
    }
}

fun ScriptExecuteActivity.loadDef(): ScriptExecution? {
    val executionId = intent.getIntExtra(EXTRA_EXECUTION_ID, ScriptExecution.NO_ID)
    if (executionId == ScriptExecution.NO_ID) return null

    val execution = ScriptEngineService.instance?.getScriptExecution(executionId)
    if (execution is ActivityScriptExecution) {
        return execution
    }
    return null
}

fun ScriptExecuteActivity.runScript(execution: ScriptExecution) {
    if (execution is ActivityScriptExecution) {
        runScript(this, execution)
    } else if (execution is RunnableScriptExecution) {
        execution.run()
    }
}

fun runScript(activity: ScriptExecuteActivity, execution: ActivityScriptExecution) {
    try {
        val scriptEngine = execution.engine!!
        scriptEngine.put("activity", activity)
        scriptEngine.setTag("activity", activity)
        scriptEngine.setTag(ScriptEngine.TAG_ENV_PATH, execution.config.path)
        scriptEngine.setTag(
            ScriptEngine.TAG_WORKING_DIRECTORY,
            execution.config.workingDirectory
        )
        scriptEngine.init()

        scriptEngine.setTag(ScriptEngine.TAG_SOURCE, execution.source)
        activity.model.onStart()
        val onException2 = activity::onException
        (scriptEngine as LoopBasedJavaScriptEngine).execute(
            execution.source, object : ExecuteCallback {
                override fun onResult(r: Any) {}
                override fun onException(e: Exception) {
                    onException2(e)
                }
            })
    } catch (pending: ContinuationPending) {
        pending.printStackTrace()
    } catch (e: Throwable) {
        activity.onException(e)
    }
}
