package com.cyeam.cnote

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView

/** One shared native selector: current value, rounded menu and checked selection. */
// The native Activity uses framework widgets and its system theme.
@SuppressLint("AppCompatCustomView")
class WorkspaceSelector(context:Context,value:Int,size:Float=28f,private val choose:(Int)->Unit):TextView(context) {
    private data class Option(val value:Int,val name:String,val icon:Int)
    private val options=listOf(Option(0,"CNote",0xf444),Option(4,"作业",0xf1e2),Option(5,"OneDrive",0xf2c1))
    private val dark=resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK==Configuration.UI_MODE_NIGHT_YES
    private val ink=Color.parseColor(if(dark)"#DFE7F3" else "#253047")
    private val muted=Color.parseColor(if(dark)"#A4AFC0" else "#75839A")
    private val accent=Color.parseColor(if(dark)"#8DB7FF" else "#2870E8")
    private val surface=Color.parseColor(if(dark)"#191E29" else "#FFFFFF")
    private val selectedFill=Color.parseColor(if(dark)"#253550" else "#EDF3FF")
    private val border=Color.parseColor(if(dark)"#343F51" else "#E6EBF2")
    private val iconFont by lazy { Typeface.createFromAsset(context.assets,"editor/bootstrap-icons.ttf") }
    private val arrow=Chevron(muted)
    private var popup:PopupWindow?=null
    var value:Int=value
        private set
    init {
        tag="workspace-switcher";textSize=size;typeface=Typeface.DEFAULT_BOLD;setTextColor(ink)
        gravity=Gravity.CENTER_VERTICAL;includeFontPadding=false;minHeight=dp(46)
        setPadding(dp(2),dp(4),dp(6),dp(4));compoundDrawablePadding=dp(10)
        setCompoundDrawablesRelativeWithIntrinsicBounds(null,null,arrow,null)
        background=ripple(Color.TRANSPARENT,12)
        isFocusable=true;setOnClickListener { showOptions() }
        updateValue(value)
        accessibilityDelegate=object:View.AccessibilityDelegate(){
            override fun onInitializeAccessibilityNodeInfo(host:View,info:AccessibilityNodeInfo){super.onInitializeAccessibilityNodeInfo(host,info);info.className="android.widget.Spinner"}
        }
    }
    fun updateValue(selected:Int) {
        value=selected;val name=options.first { it.value==selected }.name
        text=name;contentDescription="$name，切换功能"
    }
    private fun dp(value:Int)=(value*resources.displayMetrics.density+.5f).toInt()
    private fun rounded(color:Int,radius:Int,stroke:Boolean=false)=GradientDrawable().apply { setColor(color);cornerRadius=dp(radius).toFloat();if(stroke)setStroke(dp(1),border) }
    private fun ripple(color:Int,radius:Int)=RippleDrawable(ColorStateList.valueOf(Color.argb(24,80,125,200)),rounded(color,radius),rounded(Color.WHITE,radius))
    private fun showOptions() {
        if(popup?.isShowing==true){popup?.dismiss();return}
        val panel=LinearLayout(context).apply { orientation=LinearLayout.VERTICAL;setPadding(dp(8),dp(8),dp(8),dp(8));background=rounded(surface,20,true);clipToOutline=true }
        val menuWidth=minOf(maxOf(dp(236),width+dp(12)),resources.displayMetrics.widthPixels-dp(40))
        val window=PopupWindow(panel,menuWidth,ViewGroup.LayoutParams.WRAP_CONTENT,true).apply {
            setBackgroundDrawable(rounded(surface,20));elevation=dp(14).toFloat();isOutsideTouchable=true;inputMethodMode=PopupWindow.INPUT_METHOD_NOT_NEEDED
        }
        for(option in options) {
            val checked=option.value==value
            val row=LinearLayout(context).apply {
                orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;setPadding(dp(14),0,dp(14),0)
                background=ripple(if(checked)selectedFill else Color.TRANSPARENT,13);isSelected=checked;isFocusable=true
            }
            val icon=TextView(context).apply { text=option.icon.toChar().toString();typeface=iconFont;textSize=20f;gravity=Gravity.CENTER;setTextColor(if(checked)accent else muted);importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO }
            row.addView(icon,LinearLayout.LayoutParams(dp(26),dp(28)).apply { marginEnd=dp(12) })
            row.addView(TextView(context).apply { text=option.name;textSize=16f;setTextColor(if(checked)accent else ink);typeface=if(checked)Typeface.create("sans-serif-medium",Typeface.NORMAL) else Typeface.DEFAULT;includeFontPadding=false },LinearLayout.LayoutParams(0,-2,1f))
            row.addView(TextView(context).apply { text=if(checked)"✓" else "";textSize=18f;gravity=Gravity.CENTER;setTextColor(accent);contentDescription=if(checked)"当前选中" else null;importantForAccessibility=if(checked)View.IMPORTANT_FOR_ACCESSIBILITY_YES else View.IMPORTANT_FOR_ACCESSIBILITY_NO },LinearLayout.LayoutParams(dp(24),dp(28)))
            row.setOnClickListener { window.dismiss();if(option.value!=value)choose(option.value) }
            panel.addView(row,LinearLayout.LayoutParams(-1,dp(54)).apply { if(option!=options.last())bottomMargin=dp(4) })
        }
        popup=window;arrow.expanded=true
        window.setOnDismissListener { arrow.expanded=false;popup=null }
        window.showAsDropDown(this,-dp(2),dp(8),Gravity.START)
    }
    override fun onDetachedFromWindow(){popup?.dismiss();super.onDetachedFromWindow()}
    private inner class Chevron(color:Int):Drawable() {
        private val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color=color;style=Paint.Style.STROKE;strokeWidth=resources.displayMetrics.density*1.8f;strokeCap=Paint.Cap.ROUND;strokeJoin=Paint.Join.ROUND }
        var expanded=false
            set(value){field=value;invalidateSelf()}
        override fun getIntrinsicWidth()=dp(16)
        override fun getIntrinsicHeight()=dp(16)
        override fun draw(canvas:Canvas){
            val w=bounds.width().toFloat();val h=bounds.height().toFloat();val top=if(expanded).61f else .39f;val bottom=if(expanded).39f else .61f
            val path=Path().apply { moveTo(bounds.left+w*.25f,bounds.top+h*top);lineTo(bounds.left+w*.5f,bounds.top+h*bottom);lineTo(bounds.left+w*.75f,bounds.top+h*top) };canvas.drawPath(path,paint)
        }
        override fun setAlpha(alpha:Int){paint.alpha=alpha;invalidateSelf()}
        override fun setColorFilter(filter:ColorFilter?){paint.colorFilter=filter;invalidateSelf()}
        @Deprecated("Drawable opacity") override fun getOpacity()=PixelFormat.TRANSLUCENT
    }
}
