package dev.mjwatch.mobile

import android.app.UiAutomation
import android.content.Intent
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityManager
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.*
import java.io.File

/** Real TalkBack remains connected; default UiAutomation would suppress it. */
class TalkBackFocusTest {
    @Test fun chooserBackRestoresAccessibilityFocusToCast() {
        val instrumentation=InstrumentationRegistry.getInstrumentation();val context=instrumentation.targetContext
        Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("isolatedRemote")=="true")
        Assert.assertTrue("TalkBack must be installed on the isolated phone",context.getSystemService(AccessibilityManager::class.java).installedAccessibilityServiceList.any {it.id.contains("talkback",ignoreCase=true)})
        val automation=instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        fun find(root:AccessibilityNodeInfo?,predicate:(AccessibilityNodeInfo)->Boolean):AccessibilityNodeInfo? {
            if(root==null)return null
            if(predicate(root))return root
            for(i in 0 until root.childCount)find(root.getChild(i),predicate)?.let {return it}
            return null
        }
        fun isCast(node:AccessibilityNodeInfo)=node.isClickable&&find(node){it.contentDescription?.toString()?.startsWith("Где смотреть?")==true}!=null
        fun await(message:String,predicate:(AccessibilityNodeInfo)->Boolean):AccessibilityNodeInfo {
            val until=SystemClock.uptimeMillis()+15000
            while(SystemClock.uptimeMillis()<until) {
                find(automation.rootInActiveWindow,predicate)?.let {return it}
                SystemClock.sleep(100)
            }
            fun dump(node:AccessibilityNodeInfo?,depth:Int=0) {
                if(node==null)return
                println("A11Y "+" ".repeat(depth)+"${node.className} text=${node.text} desc=${node.contentDescription} aFocus=${node.isAccessibilityFocused} input=${node.isFocused} clickable=${node.isClickable}")
                for(i in 0 until node.childCount)dump(node.getChild(i),depth+1)
            }
            dump(automation.rootInActiveWindow)
            throw AssertionError(message)
        }
        val launch=Intent(context,MainActivity::class.java).putExtra("remoteReleaseId","demo:a11y").putExtra("remoteTitle","Доступность")
        ActivityScenario.launch<MainActivity>(launch).use {
            SystemClock.sleep(1200)
            val cast=await("Accessible cast button"){isCast(it)}
            if(!cast.isAccessibilityFocused)Assert.assertTrue(cast.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS))
            val action=await("TalkBack focused cast"){it.isAccessibilityFocused&&isCast(it)}
            Assert.assertTrue(action.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            await("Accessible chooser title"){it.text?.toString()=="Где смотреть?"}
            SystemClock.sleep(400)
            automation.executeShellCommand("input keyevent KEYCODE_BACK").use {command->java.io.FileInputStream(command.fileDescriptor).readBytes()}
            await("Back restores TalkBack focus to cast"){it.isAccessibilityFocused&&isCast(it)}
            SystemClock.sleep(350)
            val dir=File(context.getExternalFilesDir(null),"phone-tv-proof").apply {mkdirs()}
            File(dir,"13-talkback-cast-focus.png").outputStream().use {out->Assert.assertTrue(automation.takeScreenshot()!!.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out))}
        }
    }
}
