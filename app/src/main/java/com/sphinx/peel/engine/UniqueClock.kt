package com.sphinx.peel.engine

import kotlin.random.Random

/// Every core call needs a time unlike any before it for this account, even
/// two in the same millisecond. Ported from PeelCore's Engine.swift
/// (`UniqueClock`).
class UniqueClock(last: Long) {
    var last: Long = last
        private set

    fun next(): String {
        val now = System.currentTimeMillis() + Random.nextLong(0, 1000)
        last = maxOf(now, last + 1)
        return last.toString()
    }
}
