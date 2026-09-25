package org.adguardian.gkd

class RulePosition(private val values: Map<String, Any?>) {
    fun resolve(left: Float, top: Float, right: Float, bottom: Float,
                screenWidth: Float, screenHeight: Float): Pair<Float,Float>? {
        val vars=mapOf("left" to left.toDouble(),"top" to top.toDouble(),"right" to right.toDouble(),
            "bottom" to bottom.toDouble(), "width" to (right-left).toDouble(),"height" to (bottom-top).toDouble(),
            "screenWidth" to screenWidth.toDouble(),"screenHeight" to screenHeight.toDouble())
        fun value(key: String) = values[key]?.toString()?.let { Arithmetic(it, vars).evaluate() }
        return try {
            val x=when { values["left"]!=null -> left + value("left")!!
                values["right"]!=null -> right-value("right")!!
                values["x"]!=null -> value("x")!!
                else -> return null }
            val y=when { values["top"]!=null -> top+value("top")!!
                values["bottom"]!=null -> bottom-value("bottom")!!
                values["y"]!=null -> value("y")!!
                else -> return null }
            if (x.isFinite() && y.isFinite()) x.toFloat() to y.toFloat() else null
        } catch (_: IllegalArgumentException) { null }
    }
    fun coordinates(left: Float,top: Float,right: Float,bottom: Float,screenWidth: Float,screenHeight: Float): FloatArray? =
        resolve(left,top,right,bottom,screenWidth,screenHeight)?.let { floatArrayOf(it.first,it.second) }
}
class RuleSwipe(raw: Map<String, Any?>) {
    val start=RulePosition(raw["start"].obj())
    val end=if (raw["end"]!=null) RulePosition(raw["end"].obj()) else start
    val duration=raw.long("duration",200).coerceIn(1,60000)
}
private class Arithmetic(private val source: String,private val variables: Map<String,Double>) {
    private var index=0
    fun evaluate(): Double {
        require(source.length<=512)
        val result=add();space();require(index==source.length);return result
    }
    private fun space() { while(index<source.length && source[index].isWhitespace()) index++ }
    private fun take(c: Char): Boolean { space();if(index<source.length && source[index]==c){index++;return true};return false }
    private fun add(): Double {
        var result=multiply()
        while(true) result=when {take('+')->result+multiply();take('-')->result-multiply();else->return result}
    }
    private fun multiply(): Double {
        var result=atom()
        while(true) result=when {take('*')->result*atom();take('/')->result/atom();take('%')->result%atom();else->return result}
    }
    private fun atom(): Double {
        if(take('+')) return atom()
        if(take('-')) return -atom()
        if(take('(')){val value=add();require(take(')'));return value}
        space();val start=index
        if(index<source.length && (source[index].isLetter() || source[index]=='_')) {
            while(index<source.length && (source[index].isLetterOrDigit() || source[index]=='_'))index++
            return variables[source.substring(start,index)] ?: throw IllegalArgumentException("Unknown position variable")
        }
        while(index<source.length && (source[index].isDigit() || source[index]=='.')) index++
        require(index>start)
        return source.substring(start,index).toDouble()
    }
}
