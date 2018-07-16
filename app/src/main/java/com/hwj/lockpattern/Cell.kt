package com.hwj.lockpattern

/**
 * Created by HuangWeiJin on 2018/07/16.
 */

class Cell(rowArg: Int, columnArg: Int) {
    var row = rowArg
    var column = columnArg

    companion object {
        @JvmStatic
        private val sCells = Array(3) { i -> Array(3) { j -> Cell(i, j) } }

        @Synchronized
        fun of(rowArg: Int, columnArg: Int): Cell {
            checkRange(rowArg, columnArg)
            return sCells[rowArg][columnArg]
        }

        private fun checkRange(rowArg: Int, columArg: Int) {
            if (rowArg < 0 || rowArg > 2 || columArg < 0 || columArg > 2) {
                throw IllegalArgumentException("行列必须在0-2的范围之内")
            }
        }
    }

    override fun toString(): String {
        return "(row=$row,clmn=$column)"
    }
}
