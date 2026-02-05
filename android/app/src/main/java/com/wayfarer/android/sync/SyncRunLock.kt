package com.wayfarer.android.sync

import java.util.concurrent.locks.ReentrantLock

/**
 * 同步链路的进程内互斥锁：
 * - 避免并发上传/拉取触发 refresh 并发，导致服务端判定 refresh reuse。
 * - 让手动同步与后台 Worker 至少做到“串行执行”。
 */
object SyncRunLock {
    private val lock = ReentrantLock()

    fun <T> runExclusive(block: () -> T): T {
        lock.lock()
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }

    fun tryRunExclusive(block: () -> Unit): Boolean {
        if (!lock.tryLock()) return false
        return try {
            block()
            true
        } finally {
            lock.unlock()
        }
    }
}

