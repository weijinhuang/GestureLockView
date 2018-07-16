package com.hwj.lockpattern

/**
 * Created by HuangWeiJin on 2018/07/16.
 */
interface OnPatternListener {
    fun onPatternStart()

    fun onPatternCleared()

    fun onPatternCellAdded(pattern: List<Cell>)

    fun onPatternDetected(pattern: List<Cell>)
}