package com.sagi.appleremotebridge

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlin.math.abs

class RemoteAccessibilityService:AccessibilityService(){
 companion object { @Volatile private var instance:RemoteAccessibilityService?=null; fun dispatch(c:RemoteCommand)=instance?.handle(c)?:false }
 override fun onServiceConnected(){instance=this}; override fun onDestroy(){if(instance===this)instance=null;super.onDestroy()}; override fun onAccessibilityEvent(e:AccessibilityEvent?){}; override fun onInterrupt(){}
 private fun handle(c:RemoteCommand)=when(c){RemoteCommand.BACK->performGlobalAction(GLOBAL_ACTION_BACK);RemoteCommand.HOME->performGlobalAction(GLOBAL_ACTION_HOME);RemoteCommand.OK->click();RemoteCommand.UP->move(D.UP);RemoteCommand.DOWN->move(D.DOWN);RemoteCommand.LEFT->move(D.LEFT);RemoteCommand.RIGHT->move(D.RIGHT);RemoteCommand.PLAY_PAUSE->false}
 private fun click():Boolean{val r=rootInActiveWindow?:return false;var n=r.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)?:findFocused(r)?:return false;while(true){if(n.isClickable&&n.performAction(AccessibilityNodeInfo.ACTION_CLICK))return true;n=n.parent?:break};return false}
 private fun move(d:D):Boolean{val r=rootInActiveWindow?:return false;val a=mutableListOf<AccessibilityNodeInfo>();collect(r,a);if(a.isEmpty())return false;val c=r.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)?:findFocused(r)?:a.first();val cr=Rect().also(c::getBoundsInScreen);val x=cr.centerX();val y=cr.centerY();val best=a.filter{it!=c}.mapNotNull{n->val q=Rect().also(n::getBoundsInScreen);val dx=q.centerX()-x;val dy=q.centerY()-y;val ok=when(d){D.UP->dy<0;D.DOWN->dy>0;D.LEFT->dx<0;D.RIGHT->dx>0};if(!ok)null else {val p=if(d==D.UP||d==D.DOWN)abs(dy) else abs(dx);val s=if(d==D.UP||d==D.DOWN)abs(dx) else abs(dy);n to p*1000L+s*3L}}.minByOrNull{it.second}?.first?:return false;return best.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)||best.performAction(AccessibilityNodeInfo.ACTION_FOCUS)}
 private fun collect(n:AccessibilityNodeInfo,o:MutableList<AccessibilityNodeInfo>){if(n.isVisibleToUser&&(n.isFocusable||n.isClickable))o+=n;for(i in 0 until n.childCount)n.getChild(i)?.let{collect(it,o)}}
 private fun findFocused(n:AccessibilityNodeInfo):AccessibilityNodeInfo?{if(n.isFocused||n.isAccessibilityFocused)return n;for(i in 0 until n.childCount)n.getChild(i)?.let{findFocused(it)?.let{return it}};return null}
 private enum class D{UP,DOWN,LEFT,RIGHT}
}
enum class RemoteCommand{UP,DOWN,LEFT,RIGHT,OK,BACK,HOME,PLAY_PAUSE}
