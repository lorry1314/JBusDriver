package com.cfzx.utils

import android.support.v4.util.LruCache
import io.reactivex.Flowable
import io.reactivex.schedulers.Schedulers
import me.jbusdriver.common.*
import java.util.concurrent.TimeUnit

object CacheLoader {
    private val TAG = "CacheLoader"

    private fun initMemCache(): LruCache<String, String> {
        val maxMemory = Runtime.getRuntime().maxMemory().toInt()
        val cacheSize = if (maxMemory / 8 > 4 * 1024 * 1024) 4 * 1024 * 1024 else maxMemory
        KLog.t(TAG).d("max cacheSize = ${cacheSize.toLong().formatFileSize()}")
        return object : LruCache<String, String>(cacheSize) { //4m
            override fun entryRemoved(evicted: Boolean, key: String?, oldValue: String?, newValue: String?) {
                KLog.i(String.format("entryRemoved : evicted = %s , key = %20s , oldValue = %30s , newValue = %30s", evicted.toString(), key, oldValue, newValue))
                if (evicted) oldValue.let { null } ?: oldValue.let { newValue }
            }

            override fun sizeOf(key: String, value: String): Int {
                val length = value.toByteArray().size
                KLog.i("key = $key  sizeOf = [$length]bytes format:${(this.size() + length).toLong().formatFileSize()}")
                return length
            }

        }
    }

    @JvmStatic val lru: LruCache<String, String> by lazy {
        initMemCache()
    }


    @JvmStatic val acache: ACache  by lazy {
        ACache.get(AppContext.instace)
    }

    /*============================cache====================================*/
    fun cacheLruAndDisk(pair: Pair<String, Any>, seconds: Int? = null) = with(AppContext.gson.toJson(pair.second)) {
        lru.put(pair.first, this)
        seconds?.let { acache.put(pair.first, AppContext.gson.toJson(pair.second), seconds) } ?: acache.put(pair.first, AppContext.gson.toJson(pair.second))
    }

    fun cacheLru(pair: Pair<String, Any>) = lru.put(pair.first, AppContext.gson.toJson(pair.second))
    fun cacheDisk(pair: Pair<String, Any>, seconds: Int? = null) = seconds?.let { acache.put(pair.first, v2Str(pair.second), seconds) } ?: acache.put(pair.first, v2Str(pair.second))

    private fun v2Str(obj: Any): String = when (obj) {
        is CharSequence -> obj.toString()
        else -> obj.toJsonString()
    }

    /*============================cache to flowable====================================*/
    fun fromLruAsync(key: String): Flowable<String> = Flowable.interval(0, 800, TimeUnit.MILLISECONDS, Schedulers.io()).flatMap {
        val v = lru[key]
        KLog.i("fromLruAsync : $key ,$v")
        v?.let { Flowable.just(it) } ?: Flowable.empty()
    }.timeout(6, TimeUnit.SECONDS, Flowable.empty()).take(1).subscribeOn(Schedulers.io())

    fun fromDiskAsync(key: String, add2Lru: Boolean = true): Flowable<String> = Flowable.interval(0, 800, TimeUnit.MILLISECONDS, Schedulers.io()).flatMap {
        val v = acache.getAsString(key)
        KLog.i("fromDiskAsync : $key ,$v")
        v?.let { Flowable.just(it) } ?: Flowable.empty()
    }.timeout(6, TimeUnit.SECONDS, Flowable.empty()).take(1).doOnNext { if (add2Lru) lru.put(key, it) }.subscribeOn(Schedulers.io())


    fun justLru(key: String): Flowable<String> {
        val v = lru[key]
        KLog.i("justLru : $key ,$v")
        return v?.let { Flowable.just(v) } ?: Flowable.empty()
    }

    fun justDisk(key: String, add2Lru: Boolean = true): Flowable<String> {
        val v = acache.getAsString(key)
        KLog.i("justDisk : $key add lru $add2Lru,$v")
        return v?.let { Flowable.just(v) } ?: Flowable.empty()
    }

    /*===============================remove cache=====================================*/
    /**
     * 只会先从lru中删除再删除disk的
     */
    fun removeCacheLike(vararg keys: String, isRegex: Boolean = false) {
        Schedulers.computation().createWorker().schedule {
            lru.snapshot().keys.let {
                cacheCopyKeys ->
                keys.forEach { removeKey ->
                    val filterAction: (String) -> Boolean = { s -> if (isRegex) s.contains(removeKey.toRegex()) else s.contains(removeKey) }
                    cacheCopyKeys.filter(filterAction).forEach {
                        KLog.i("removeCacheLike : $it")
                        cacheCopyKeys.remove(it); lru.remove(it);acache.remove(it)
                    }
                }
            }
        }
    }

}
