package io.github.rwxzxy.guard.data

import android.util.Log
import androidx.core.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import java.io.File

val GuardJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

/**
 * 用 AtomicFile 保存的一个 JSON 文件，内存里保留一份 StateFlow。
 * 所有读写都在本进程完成（Shizuku 的服务进程不碰这些文件）。
 */
class JsonFileStore<T>(
    private val file: File,
    private val serializer: KSerializer<T>,
    private val default: () -> T,
) {
    private val mutex = Mutex()
    private val atomic = AtomicFile(file)
    private val _state = MutableStateFlow(load())
    val state: StateFlow<T> = _state.asStateFlow()

    private fun load(): T = try {
        if (file.exists()) {
            GuardJson.decodeFromString(serializer, atomic.readFully().decodeToString())
        } else {
            default()
        }
    } catch (e: Exception) {
        Log.w("JsonFileStore", "读取 ${file.name} 失败，使用默认值", e)
        default()
    }

    suspend fun update(transform: (T) -> T): T = mutex.withLock {
        val next = transform(_state.value)
        if (next != _state.value) {
            withContext(Dispatchers.IO) { write(next) }
            _state.value = next
        }
        next
    }

    private fun write(value: T) {
        val out = atomic.startWrite()
        try {
            out.write(GuardJson.encodeToString(serializer, value).encodeToByteArray())
            atomic.finishWrite(out)
        } catch (e: Exception) {
            atomic.failWrite(out)
            throw e
        }
    }
}
