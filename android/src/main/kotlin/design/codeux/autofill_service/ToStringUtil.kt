package design.codeux.autofill_service

import android.app.assist.AssistStructure
import android.os.Build
import androidx.annotation.RequiresApi
import org.apache.commons.lang3.builder.*
import org.apache.commons.text.StringEscapeUtils
import java.nio.file.Path
import kotlin.reflect.full.*
import kotlin.reflect.jvm.isAccessible

private class ToStringStyle : MultilineRecursiveToStringStyle() {

    companion object {
        const val INFINITE_DEPTH = 0
    }

    var maxDepth = INFINITE_DEPTH

    init {
        isUseShortClassName = true
        isUseIdentityHashCode = false
    }

    private val spacesAccessor by lazy {
        MultilineRecursiveToStringStyle::class.declaredMemberProperties.single { it.name == "spaces" }.also {
            it.isAccessible = true
        }
    }

    private val spaces get() = spacesAccessor.get(this) as Int

    override fun appendDetail(buffer: StringBuffer?, fieldName: String?, value: Any?) {
        if (value is CharSequence) {
            buffer?.append(StringEscapeUtils.escapeJson(value.toString()))
        } else {
            super.appendDetail(buffer, fieldName, value)
        }
    }

    @RequiresApi(Build.VERSION_CODES.M)
    override fun accept(clazz: Class<*>?): Boolean {
        if (maxDepth != INFINITE_DEPTH && (spaces/2) > maxDepth) {
            return false
        }
        return true
//        return !setOf(AssistStructure.ViewNode::class.java)
//            .contains(clazz)
    }
}

fun Any.toStringReflective(maxDepth: Int = ToStringStyle.INFINITE_DEPTH): String =
    ReflectionToStringBuilder.toString(
        this,
        ToStringStyle().also { it.maxDepth = maxDepth }
    )

