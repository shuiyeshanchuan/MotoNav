package com.example.motonav

import android.content.res.ColorStateList
import android.graphics.drawable.RippleDrawable
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

/** Four independent, lazily created pages; bottom navigation never scrolls away. */
class MainPagesShell(private val activity:AppCompatActivity, saved:Bundle?,
                     private val build:(Int)->View, private val onSelected:(Int)->Unit):LinearLayout(activity) {
    private val S=NavStyle
    private fun dp(v:Int)=S.dpi(activity,v)
    private val titles=listOf("导航","小圆屏","行驶信息","设置")
    private val host=FrameLayout(activity)
    private val pages=arrayOfNulls<ScrollView>(4)
    private val restoreY=run {
        val old=saved?.getIntArray("main_page_scroll")
        when {
            old == null -> IntArray(4)
            old.size == 4 -> old
            // v8.3.37-v8.3.51 had three tabs; its settings scroll position
            // is now the fourth tab. Older five-tab builds stored settings at
            // index 3.
            old.size == 3 -> intArrayOf(old[0], old[1], 0, old[2])
            old.size >= 5 -> intArrayOf(old[0], old[1], 0, old[3])
            else -> IntArray(4)
        }
    }
    private val tabs=ArrayList<LinearLayout>()
    private val labels=ArrayList<TextView>()
    var selectedPage=0;private set

    init {
        orientation=VERTICAL;setBackgroundColor(S.BG)
        WindowCompat.setDecorFitsSystemWindows(activity.window,false)
        addView(host,LayoutParams(-1,0,1f))
        val navigation=LinearLayout(activity).apply {
            orientation=HORIZONTAL;setPadding(dp(8),dp(6),dp(8),dp(6))
            background=S.round(activity,S.CARD,0f,S.STROKE)
        }
        titles.forEachIndexed { index,title ->
            val tab=LinearLayout(activity).apply {
                orientation=VERTICAL;gravity=Gravity.CENTER;isFocusable=true
                contentDescription=title;minimumHeight=dp(52)
                setOnClickListener{showPage(index)}
            }
            val label=TextView(activity).apply {
                text=title;textSize=15f;setTextColor(S.SUB);gravity=Gravity.CENTER
                isSingleLine=true;includeFontPadding=false
            }
            tab.addView(label,LayoutParams(-1,-2))
            navigation.addView(tab,LayoutParams(0,dp(52),1f).apply {
                leftMargin=dp(3);rightMargin=dp(3)
            })
            tabs+=tab;labels+=label
        }
        addView(navigation,LayoutParams(-1,-2))
        ViewCompat.setOnApplyWindowInsetsListener(this){view,insets->
            val bars=insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime=insets.getInsets(WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left,bars.top,bars.right,maxOf(bars.bottom,ime.bottom));insets
        }
    }

    fun showPage(index:Int) {
        if(index !in 0..3)return
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
            tab.background=RippleDrawable(ColorStateList.valueOf(S.CARD2),
                S.round(activity,if(selected)S.CARD2 else S.CARD,12f),null)
        }
        onSelected(index)
    }

    fun save(out:Bundle) {
        out.putInt("main_page_selected",selectedPage)
        out.putIntArray("main_page_scroll",IntArray(4){pages[it]?.scrollY ?: restoreY[it]})
    }

}
