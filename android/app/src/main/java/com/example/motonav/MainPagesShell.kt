package com.example.motonav

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import kotlin.math.cos
import kotlin.math.sin

/** Five independent, lazily created pages; bottom navigation never scrolls away. */
class MainPagesShell(private val activity:AppCompatActivity, saved:Bundle?,
                     private val build:(Int)->View, private val onSelected:(Int)->Unit):LinearLayout(activity) {
    private val S=NavStyle
    private fun dp(v:Int)=S.dpi(activity,v)
    private val titles=listOf("导航","设备","语音","设置","更多")
    private val host=FrameLayout(activity)
    private val pages=arrayOfNulls<ScrollView>(5)
    private val restoreY=saved?.getIntArray("main_page_scroll")?.takeIf{it.size==5} ?: IntArray(5)
    private val tabs=ArrayList<LinearLayout>()
    private val icons=ArrayList<PageIcon>()
    private val labels=ArrayList<TextView>()
    private val indicators=ArrayList<FrameLayout>()
    var selectedPage=0;private set

    init {
        orientation=VERTICAL;setBackgroundColor(S.BG)
        WindowCompat.setDecorFitsSystemWindows(activity.window,false)
        addView(host,LayoutParams(-1,0,1f))
        val navigation=LinearLayout(activity).apply {
            orientation=HORIZONTAL;setPadding(dp(5),dp(5),dp(5),dp(5))
            Neumorphic.applyRaised(this, 18f)
        }
        titles.forEachIndexed { index,title ->
            val tab=LinearLayout(activity).apply {
                orientation=VERTICAL;gravity=Gravity.CENTER;isFocusable=true
                contentDescription=title;minimumHeight=dp(58)
                setOnClickListener{showPage(index)}
            }
            val indicator=FrameLayout(activity)
            val icon=PageIcon(index)
            indicator.addView(icon,FrameLayout.LayoutParams(dp(23),dp(23),Gravity.CENTER))
            tab.addView(indicator,LayoutParams(dp(44),dp(29)))
            val label=TextView(activity).apply {
                text=title;textSize=11.5f;setTextColor(S.SUB);gravity=Gravity.CENTER
                isSingleLine=true;includeFontPadding=false;setPadding(0,dp(4),0,0)
            }
            tab.addView(label,LayoutParams(-1,-2))
            navigation.addView(tab,LayoutParams(0,dp(58),1f))
            tabs+=tab;icons+=icon;labels+=label;indicators+=indicator
        }
        addView(navigation,LayoutParams(-1,-2))
        ViewCompat.setOnApplyWindowInsetsListener(this){view,insets->
            val bars=insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime=insets.getInsets(WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left,bars.top,bars.right,maxOf(bars.bottom,ime.bottom));insets
        }
    }

    fun showPage(index:Int) {
        if(index !in 0..4)return
        pages[selectedPage]?.visibility=GONE
        selectedPage=index
        val page=pages[index] ?: ScrollView(activity).apply {
            id=View.generateViewId();isFillViewport=true;clipToPadding=true
            addView(build(index),FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT))
            host.addView(this,FrameLayout.LayoutParams(-1,-1));pages[index]=this
            post{scrollTo(0,restoreY[index])}
        }
        page.visibility=VISIBLE
        tabs.forEachIndexed{i,tab->
            val selected=i==index;tab.isSelected=selected
            labels[i].setTextColor(if(selected)S.PINK else S.SUB)
            labels[i].typeface=if(selected)S.BOLD else S.MEDIUM
            indicators[i].background=if(selected) Neumorphic.recessed(activity,14f) else null
            icons[i].active=selected
        }
        onSelected(index)
    }

    fun save(out:Bundle) {
        out.putInt("main_page_selected",selectedPage)
        out.putIntArray("main_page_scroll",IntArray(5){pages[it]?.scrollY ?: restoreY[it]})
    }

    private inner class PageIcon(private val index:Int):View(activity) {
        var active=false
            set(value){if(field!=value){field=value;invalidate()}}
        private val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style=Paint.Style.STROKE;strokeWidth=1.8f;strokeCap=Paint.Cap.ROUND;strokeJoin=Paint.Join.ROUND
        }
        override fun onDraw(canvas:Canvas) {
            super.onDraw(canvas)
            canvas.save();canvas.scale(width/24f,height/24f);paint.color=if(active)S.PINK else S.SUB
            fun line(x1:Float,y1:Float,x2:Float,y2:Float)=canvas.drawLine(x1,y1,x2,y2,paint)
            when(index) {
                0->{
                    canvas.drawCircle(12f,12f,9f,paint)
                    canvas.drawPath(Path().apply{moveTo(7f,13f);lineTo(12f,8f);lineTo(17f,13f);moveTo(12f,8f);lineTo(12f,18f)},paint)
                }
                1->{
                    canvas.drawCircle(12f,12f,9f,paint)
                    canvas.drawCircle(12f,12f,2f,paint)
                    canvas.drawPath(Path().apply{moveTo(12f,3f);lineTo(12f,6f);moveTo(12f,18f);lineTo(12f,21f)},paint)
                }
                2->{
                    canvas.drawPath(Path().apply{moveTo(4f,9f);lineTo(8f,9f);lineTo(12f,5f);lineTo(12f,19f);lineTo(8f,15f);lineTo(4f,15f);close()},paint)
                    canvas.drawArc(RectF(10f,7f,20f,17f),-55f,110f,false,paint)
                    canvas.drawArc(RectF(7f,3f,24f,21f),-50f,100f,false,paint)
                }
                3->{
                    canvas.drawCircle(12f,12f,6.5f,paint);canvas.drawCircle(12f,12f,2.5f,paint)
                    repeat(8){val a=it*Math.PI/4;line((12+6.5*cos(a)).toFloat(),(12+6.5*sin(a)).toFloat(),(12+9*cos(a)).toFloat(),(12+9*sin(a)).toFloat())}
                }
                4->{
                    paint.style=Paint.Style.FILL
                    canvas.drawCircle(6f,12f,1.7f,paint);canvas.drawCircle(12f,12f,1.7f,paint);canvas.drawCircle(18f,12f,1.7f,paint)
                    paint.style=Paint.Style.STROKE
                }
            }
            canvas.restore()
        }
    }
}
