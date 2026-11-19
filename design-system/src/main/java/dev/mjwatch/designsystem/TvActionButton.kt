package dev.mjwatch.designsystem

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.*

@Composable internal fun tvFocusBorder(radius:Dp,color:Color=MaterialTheme.colorScheme.primary):Border {
    // An inset stroke keeps compact TV buttons safe in clipped containers.
    return Border(BorderStroke(2.dp,color),inset=(-1).dp,shape=RoundedCornerShape(radius-1.dp))
}

// Poster frames have their own clearance; buttons need no external padding.
internal val TvPosterClearance=8.dp

/** Compact TV actions; focus and press never resize the button. */
@Composable fun TvActionButton(text:String,modifier:Modifier=Modifier,enabled:Boolean=true,secondary:Boolean=false,icon:ImageVector?=null,loading:Boolean=false,interactionSource:MutableInteractionSource?=null,onClick:()->Unit) {
    val colors=MaterialTheme.colorScheme
    val shape=MaterialTheme.shapes.medium
    val source=interactionSource ?: remember {MutableInteractionSource()}
    val focused by source.collectIsFocusedAsState()
    val blocked=!enabled||loading
    val baseContainer=if(secondary) colors.surface else colors.primary
    val baseForeground=if(secondary) colors.onSurface else colors.onPrimary
    val container=if(!enabled&&!loading)baseContainer.copy(alpha=.45f).compositeOver(colors.background) else baseContainer
    val foreground=if(!enabled&&!loading)baseForeground.copy(alpha=.45f).compositeOver(colors.background) else baseForeground
    val focusBorder=tvFocusBorder(16.dp)
    // TV Material 1.0.1 adds an unavoidable disabled-content alpha. Own the disabled
    // semantics and activation guard so loading stays legible and retains D-pad focus.
    Button(onClick={if(!blocked)onClick()},interactionSource=source,
        modifier=modifier.heightIn(min=if(secondary) 48.dp else 52.dp).drawWithContent {
            drawContent()
            // Keep the lime stroke distinct from a lime action without growing the button.
            if(focused) {
                val inset=4.dp.toPx()
                drawRoundRect(colors.background,Offset(inset,inset),Size(size.width-2*inset,size.height-2*inset),
                    CornerRadius(12.dp.toPx()),style=Stroke(4.dp.toPx()))
            }
        }.semantics {
            if(blocked) disabled()
            if(loading) {stateDescription="Выполняется";progressBarRangeInfo=ProgressBarRangeInfo.Indeterminate}
        },
        shape=ButtonDefaults.shape(shape=shape),
        scale=ButtonDefaults.scale(focusedScale=1f,pressedScale=1f,disabledScale=1f,focusedDisabledScale=1f),
        colors=ButtonDefaults.colors(containerColor=container,contentColor=foreground,
            focusedContainerColor=container,focusedContentColor=foreground,
            pressedContainerColor=if(blocked)container else container.copy(red=container.red*.85f,green=container.green*.85f,blue=container.blue*.85f),
            pressedContentColor=if(blocked)foreground else foreground.copy(red=foreground.red*.85f,green=foreground.green*.85f,blue=foreground.blue*.85f)),
        border=ButtonDefaults.border(focusedBorder=focusBorder,pressedBorder=if(focused)focusBorder else Border.None,focusedDisabledBorder=if(loading)focusBorder else Border.None),
        contentPadding=PaddingValues(horizontal=if(secondary)20.dp else 24.dp,vertical=12.dp)) {
        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            val color=LocalContentColor.current
            if(loading) CircularProgressIndicator(Modifier.size(20.dp).clearAndSetSemantics {},color=color,strokeWidth=2.dp,trackColor=Color.Transparent)
            else if(icon!=null) Icon(icon,null,Modifier.size(20.dp),tint=color)
            Text(text,fontSize=18.sp,lineHeight=24.sp,fontWeight=FontWeight.Medium,color=color,textAlign=TextAlign.Center,modifier=Modifier.weight(1f,fill=false))
        }
    }
}
