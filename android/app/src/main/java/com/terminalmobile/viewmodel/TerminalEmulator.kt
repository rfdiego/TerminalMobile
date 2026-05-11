package com.terminalmobile.viewmodel

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle

private val TERM_PALETTE = arrayOf(
    Color(  12,  12,  12), Color(197,  15,  31), Color( 19, 161,  14), Color(193, 156,   0),
    Color(   0,  55, 218), Color(136,  23, 152), Color( 58, 150, 221), Color(204, 204, 204),
    Color( 118, 118, 118), Color(231,  72,  86), Color( 22, 198,  12), Color(249, 241, 165),
    Color(  59, 120, 255), Color(180,   0, 158), Color( 97, 214, 214), Color(242, 242, 242),
)

// idx: -1=default, 0-15=palette, 16-255=256-color cube, >=0x1000000=packed RGB
private fun idxToColor(idx: Int): Color = when {
    idx < 0    -> Color.Unspecified
    idx < 16   -> TERM_PALETTE.getOrElse(idx) { Color.White }
    idx < 232  -> { val i=idx-16; val r=i/36; val g=(i%36)/6; val b=i%6
                    Color(if(r==0)0 else 55+r*40, if(g==0)0 else 55+g*40, if(b==0)0 else 55+b*40) }
    idx < 256  -> { val v=8+(idx-232)*10; Color(v,v,v) }
    else       -> Color((idx and 0xFF0000 shr 16), (idx and 0xFF00 shr 8), idx and 0xFF)
}

class TerminalEmulator(private val cols: Int = 120, private val rows: Int = 40) {

    private data class Style(val fg: Int=-1, val bg: Int=-1, val bold: Boolean=false, val dim: Boolean=false)
    private data class Cell(val ch: Char=' ', val style: Style=Style())

    private val screen = Array(rows) { Array(cols) { Cell() } }
    private val scrollback = ArrayDeque<AnnotatedString>()
    private val MAX_SCROLLBACK = 2000

    private var curRow = 0; private var curCol = 0
    private var savedRow = 0; private var savedCol = 0
    private var curStyle = Style()

    private val ESC = Char(27)
    private val BEL = Char(7)

    fun feed(raw: String) {
        var i = 0
        while (i < raw.length) {
            when (val c = raw[i]) {
                ESC  -> if (i + 1 < raw.length) { i++; i = handleEsc(raw, i) }
                '\r' -> curCol = 0
                '\n' -> newline()
                '\b' -> if (curCol > 0) curCol--
                '\t' -> curCol = minOf(cols - 1, (curCol / 8 + 1) * 8)
                else -> if (c.code >= 32) putChar(c)
            }
            i++
        }
    }

    private fun putChar(c: Char) {
        if (curCol >= cols) { curCol = 0; newline() }
        screen[curRow][curCol] = Cell(c, curStyle)
        curCol++
    }

    private fun newline() {
        curRow++
        if (curRow >= rows) {
            scrollback.addFirst(rowToAnnotated(screen[0]))
            while (scrollback.size > MAX_SCROLLBACK) scrollback.removeLast()
            for (r in 0 until rows - 1) screen[r] = screen[r + 1].copyOf()
            screen[rows - 1] = Array(cols) { Cell() }
            curRow = rows - 1
        }
    }

    private fun Array<Cell>.copyOf() = Array(size) { this[it] }

    private fun handleEsc(raw: String, i0: Int): Int {
        var i = i0
        return when (raw[i]) {
            '[' -> {
                i++
                val p0 = i
                while (i < raw.length && raw[i] !in '@'..'~') i++
                if (i < raw.length) handleCSI(raw.substring(p0, i), raw[i])
                i
            }
            ']' -> {
                i++
                while (i < raw.length) {
                    if (raw[i] == BEL) { i++; break }
                    if (raw[i] == ESC && i + 1 < raw.length && raw[i+1] == '\\') { i += 2; break }
                    i++
                }
                i - 1
            }
            '7' -> { savedRow = curRow; savedCol = curCol; i }
            '8' -> { curRow = savedRow.coerceIn(0, rows-1); curCol = savedCol.coerceIn(0, cols-1); i }
            'M' -> { if (curRow > 0) curRow-- else { for (r in rows-1 downTo 1) screen[r]=screen[r-1].copyOf(); screen[0]=Array(cols){Cell()} }; i }
            else -> i
        }
    }

    private fun handleCSI(params: String, final: Char) {
        val ps = if (params.isEmpty()) listOf(0) else params.split(';').map { it.toIntOrNull() ?: 0 }
        val p0 = ps[0]; val p1 = ps.getOrElse(1) { 0 }
        when (final) {
            'A' -> curRow = maxOf(0, curRow - maxOf(1, p0))
            'B' -> curRow = minOf(rows-1, curRow + maxOf(1, p0))
            'C' -> curCol = minOf(cols-1, curCol + maxOf(1, p0))
            'D' -> curCol = maxOf(0, curCol - maxOf(1, p0))
            'E' -> { curRow = minOf(rows-1, curRow + maxOf(1, p0)); curCol = 0 }
            'F' -> { curRow = maxOf(0, curRow - maxOf(1, p0)); curCol = 0 }
            'G' -> curCol = maxOf(0, minOf(cols-1, maxOf(1, p0) - 1))
            'H', 'f' -> {
                curRow = maxOf(0, minOf(rows-1, maxOf(1, p0) - 1))
                curCol = maxOf(0, minOf(cols-1, maxOf(1, p1) - 1))
            }
            'J' -> when (p0) {
                0 -> { for (c in curCol until cols) screen[curRow][c]=Cell()
                       for (r in curRow+1 until rows) screen[r]=Array(cols){Cell()} }
                1 -> { for (r in 0 until curRow) screen[r]=Array(cols){Cell()}
                       for (c in 0..curCol) screen[curRow][c]=Cell() }
                2 -> { for (r in 0 until rows) {
                           val a = rowToAnnotated(screen[r])
                           if (a.isNotBlank()) { scrollback.addFirst(a); while (scrollback.size > MAX_SCROLLBACK) scrollback.removeLast() }
                           screen[r] = Array(cols) { Cell() }
                       }; curRow = 0; curCol = 0 }
                3 -> { scrollback.clear(); for (r in 0 until rows) screen[r]=Array(cols){Cell()}; curRow=0; curCol=0 }
            }
            'K' -> when (p0) {
                0 -> for (c in curCol until cols) screen[curRow][c]=Cell()
                1 -> for (c in 0..curCol) screen[curRow][c]=Cell()
                2 -> screen[curRow] = Array(cols) { Cell() }
            }
            'L' -> { val n=maxOf(1,p0); for (r in rows-1 downTo curRow+n) screen[r]=screen[r-n].copyOf()
                     for (r in curRow until minOf(curRow+n,rows)) screen[r]=Array(cols){Cell()} }
            'M' -> { val n=maxOf(1,p0); for (r in curRow until rows-n) screen[r]=screen[r+n].copyOf()
                     for (r in rows-n until rows) screen[r]=Array(cols){Cell()} }
            'P' -> { val n=maxOf(1,p0); val row=screen[curRow]
                     for (c in curCol until cols-n) row[c]=row[c+n]
                     for (c in cols-n until cols) row[c]=Cell() }
            's' -> { savedRow=curRow; savedCol=curCol }
            'u' -> { curRow=savedRow.coerceIn(0,rows-1); curCol=savedCol.coerceIn(0,cols-1) }
            'm' -> handleSGR(ps)
        }
    }

    private fun handleSGR(ps: List<Int>) {
        var fg=curStyle.fg; var bg=curStyle.bg; var bold=curStyle.bold; var dim=curStyle.dim
        var i = 0
        while (i < ps.size) {
            when (val c = ps[i]) {
                0    -> { fg=-1; bg=-1; bold=false; dim=false }
                1    -> bold = true
                2    -> dim = true
                22   -> { bold=false; dim=false }
                in 30..37  -> fg = c-30
                in 90..97  -> fg = c-90+8
                39   -> fg = -1
                38   -> when (ps.getOrElse(i+1){-1}) {
                    5 -> { fg = ps.getOrElse(i+2){0}; i+=2 }
                    2 -> { fg = 0x1000000 or (ps.getOrElse(i+2){0} shl 16) or (ps.getOrElse(i+3){0} shl 8) or ps.getOrElse(i+4){0}; i+=4 }
                }
                in 40..47  -> bg = c-40
                in 100..107-> bg = c-100+8
                49   -> bg = -1
                48   -> when (ps.getOrElse(i+1){-1}) {
                    5 -> { bg = ps.getOrElse(i+2){0}; i+=2 }
                    2 -> { bg = 0x1000000 or (ps.getOrElse(i+2){0} shl 16) or (ps.getOrElse(i+3){0} shl 8) or ps.getOrElse(i+4){0}; i+=4 }
                }
            }
            i++
        }
        curStyle = Style(fg, bg, bold, dim)
    }

    private fun rowToAnnotated(row: Array<Cell>): AnnotatedString {
        var last = cols - 1
        while (last > 0 && row[last].ch == ' ' && row[last].style == Style()) last--
        val blank = last == 0 && row[0].ch == ' ' && row[0].style == Style()
        if (blank) return AnnotatedString("")
        return buildAnnotatedString {
            var i = 0
            while (i <= last) {
                val s = row[i].style
                var j = i + 1
                while (j <= last && row[j].style == s) j++
                val fgC = idxToColor(s.fg).let {
                    if (it == Color.Unspecified) Color(204, 204, 204)
                    else if (s.dim) it.copy(alpha = it.alpha * 0.6f) else it
                }
                withStyle(SpanStyle(
                    color = fgC,
                    background = idxToColor(s.bg),
                    fontWeight = if (s.bold) FontWeight.Bold else null,
                )) { append(String(CharArray(j - i) { row[i + it].ch })) }
                i = j
            }
        }
    }

    // Returns scrollback (oldest first) + non-blank current screen rows
    fun getSnapshot(): List<AnnotatedString> {
        val out = ArrayList<AnnotatedString>(scrollback.size + rows)
        scrollback.reversed().forEach { out.add(it) }
        var lastRow = rows - 1
        while (lastRow > 0 && rowToAnnotated(screen[lastRow]).isBlank()) lastRow--
        for (r in 0..lastRow) out.add(rowToAnnotated(screen[r]))
        return out
    }

    fun clear() {
        for (r in 0 until rows) screen[r] = Array(cols) { Cell() }
        scrollback.clear(); curRow = 0; curCol = 0; curStyle = Style()
    }
}