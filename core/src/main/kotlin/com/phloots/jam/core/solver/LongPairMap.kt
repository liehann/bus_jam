package com.phloots.jam.core.solver

/** Open-addressing hash map from a (Long, Long) key to a non-negative Int value. Not thread-safe. */
internal class LongPairMap(initialCapacity: Int = 1 shl 12) {
    private var cap = Integer.highestOneBit(maxOf(16, initialCapacity) * 2 - 1)
    private var ka = LongArray(cap)
    private var kb = LongArray(cap)
    private var vals = IntArray(cap) { EMPTY }
    var size = 0
        private set

    private fun hash(a: Long, b: Long): Int {
        var h = a * -0x61c8864680b583ebL + b
        h = (h xor (h ushr 31)) * -0x40a7b892e31b1a47L
        h = h xor (h ushr 29)
        return h.toInt()
    }

    /** Value for the key, or -1 if absent. */
    fun get(a: Long, b: Long): Int {
        val mask = cap - 1
        var i = hash(a, b) and mask
        while (true) {
            val v = vals[i]
            if (v == EMPTY) return -1
            if (ka[i] == a && kb[i] == b) return v
            i = (i + 1) and mask
        }
    }

    /** Inserts or overwrites. [value] must be >= 0. */
    fun put(a: Long, b: Long, value: Int) {
        if ((size + 1) * 2 > cap) grow()
        val mask = cap - 1
        var i = hash(a, b) and mask
        while (true) {
            if (vals[i] == EMPTY) {
                ka[i] = a; kb[i] = b; vals[i] = value; size++
                return
            }
            if (ka[i] == a && kb[i] == b) {
                vals[i] = value
                return
            }
            i = (i + 1) and mask
        }
    }

    /** Adds the key with value 0 if absent. Returns true if it was added. */
    fun add(a: Long, b: Long): Boolean {
        if ((size + 1) * 2 > cap) grow()
        val mask = cap - 1
        var i = hash(a, b) and mask
        while (true) {
            if (vals[i] == EMPTY) {
                ka[i] = a; kb[i] = b; vals[i] = 0; size++
                return true
            }
            if (ka[i] == a && kb[i] == b) return false
            i = (i + 1) and mask
        }
    }

    private fun grow() {
        val oa = ka
        val ob = kb
        val ov = vals
        cap *= 2
        ka = LongArray(cap)
        kb = LongArray(cap)
        vals = IntArray(cap) { EMPTY }
        size = 0
        for (i in ov.indices) if (ov[i] != EMPTY) put(oa[i], ob[i], ov[i])
    }

    private companion object {
        const val EMPTY = Int.MIN_VALUE
    }
}
