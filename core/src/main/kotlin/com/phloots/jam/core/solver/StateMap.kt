package com.phloots.jam.core.solver

/**
 * Open-addressing hash map from a fixed-width (Long, Long, Long, Long) key — onBoard lo, onBoard hi and the two packed state words — to a
 * non-negative Int value. Keys are stored interleaved in one LongArray. Not thread-safe.
 */
internal class StateMap(initialCapacity: Int = 1 shl 12) {
    private var cap = Integer.highestOneBit(maxOf(16, initialCapacity) * 2 - 1)
    private var keys = LongArray(cap * 4)
    private var vals = IntArray(cap).also { it.fill(EMPTY) }
    var size = 0
        private set

    private fun hash(a: Long, b: Long, c: Long, e: Long): Int {
        var h = a * -0x61c8864680b583ebL
        h = (h xor (h ushr 32) xor b) * -0x40a7b892e31b1a47L
        h = (h xor (h ushr 29) xor c) * -0x61c8864680b583ebL
        h = (h xor (h ushr 32) xor e) * -0x40a7b892e31b1a47L
        h = h xor (h ushr 31)
        return h.toInt()
    }

    /** Value for the key, or -1 if absent. */
    fun get(a: Long, b: Long, c: Long, e: Long): Int {
        val mask = cap - 1
        var i = hash(a, b, c, e) and mask
        while (true) {
            val v = vals[i]
            if (v == EMPTY) return -1
            val k = 4 * i
            if (keys[k] == a && keys[k + 1] == b && keys[k + 2] == c && keys[k + 3] == e) return v
            i = (i + 1) and mask
        }
    }

    /** Inserts or overwrites. [value] must be >= 0. */
    fun put(a: Long, b: Long, c: Long, e: Long, value: Int) {
        insert(a, b, c, e, value, overwrite = true)
    }

    /** Adds the key with value 0 if absent. Returns true if it was added. */
    fun add(a: Long, b: Long, c: Long, e: Long): Boolean = insert(a, b, c, e, 0, overwrite = false)

    private fun insert(a: Long, b: Long, c: Long, e: Long, value: Int, overwrite: Boolean): Boolean {
        if ((size + 1) * 2 > cap) grow()
        val mask = cap - 1
        var i = hash(a, b, c, e) and mask
        while (true) {
            val k = 4 * i
            if (vals[i] == EMPTY) {
                keys[k] = a; keys[k + 1] = b; keys[k + 2] = c; keys[k + 3] = e; vals[i] = value; size++
                return true
            }
            if (keys[k] == a && keys[k + 1] == b && keys[k + 2] == c && keys[k + 3] == e) {
                if (overwrite) vals[i] = value
                return false
            }
            i = (i + 1) and mask
        }
    }

    /** Removes every entry (keeps the capacity). */
    fun clear() {
        if (size == 0) return
        vals.fill(EMPTY)
        size = 0
    }

    private fun grow() {
        val ok = keys
        val ov = vals
        cap *= 2
        keys = LongArray(cap * 4)
        vals = IntArray(cap).also { it.fill(EMPTY) }
        size = 0
        for (i in ov.indices) if (ov[i] != EMPTY) insert(ok[4 * i], ok[4 * i + 1], ok[4 * i + 2], ok[4 * i + 3], ov[i], true)
    }

    private companion object {
        const val EMPTY = Int.MIN_VALUE
    }
}
