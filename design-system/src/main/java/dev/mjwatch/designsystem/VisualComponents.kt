@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
package dev.mjwatch.designsystem

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import coil.compose.AsyncImage
import dev.mjwatch.core.*
import kotlinx.coroutines.delay

@Composable internal fun tvUnit(value:Float):Dp=(value*LocalConfiguration.current.screenWidthDp/1280f).dp
@Composable internal fun Wordmark() {
    Text(buildAnnotatedString {withStyle(SpanStyle(color=MaterialTheme.colorScheme.primary)) {append("MJ")};append("Watch")},fontSize=23.sp,fontWeight=FontWeight.Bold,letterSpacing=(-0.8).sp)
}
@Composable internal fun Badge(text:String,accent:Boolean=false,warning:Boolean=false) {
    val color=when {warning->if(MaterialTheme.colorScheme.background.luminance()<.5) Color(0xFFF1C879) else Color(0xFF775500);accent->MaterialTheme.colorScheme.primary;else->MaterialTheme.colorScheme.onSurfaceVariant}
    Text(text,color=color,fontSize=12.sp,lineHeight=16.sp,modifier=Modifier.background(if(accent) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,RoundedCornerShape(8.dp)).padding(horizontal=8.dp,vertical=4.dp))
}
@Composable internal fun Pill(text:String,selected:Boolean,tv:Boolean=false,onClick:()->Unit) {
    val colors=MaterialTheme.colorScheme
    if(tv) androidx.tv.material3.Button(onClick=onClick,shape=androidx.tv.material3.ButtonDefaults.shape(shape=RoundedCornerShape(12.dp)),scale=androidx.tv.material3.ButtonDefaults.scale(focusedScale=1f,pressedScale=1f),colors=androidx.tv.material3.ButtonDefaults.colors(containerColor=if(selected) colors.primaryContainer else colors.surface,contentColor=if(selected) colors.onPrimaryContainer else colors.onSurfaceVariant,focusedContainerColor=if(selected) colors.primaryContainer else colors.surface,focusedContentColor=if(selected) colors.onPrimaryContainer else colors.onSurface),border=androidx.tv.material3.ButtonDefaults.border(focusedBorder=tvFocusBorder(12.dp)),modifier=Modifier.heightIn(min=48.dp).semantics {this.selected=selected}) {Text(text,fontSize=18.sp,lineHeight=24.sp,color=androidx.tv.material3.LocalContentColor.current)}
    else FilterChip(selected=selected,onClick=onClick,modifier=Modifier.heightIn(min=48.dp).tvFocusRing(true,RoundedCornerShape(12.dp)),label={Text(text,fontSize=14.sp,lineHeight=20.sp)},shape=RoundedCornerShape(12.dp),border=null,colors=FilterChipDefaults.filterChipColors(containerColor=colors.surface,labelColor=colors.onSurfaceVariant,selectedContainerColor=colors.primaryContainer,selectedLabelColor=colors.onPrimaryContainer))
}
@Composable internal fun SettingsToggle(label:String,checked:Boolean,onCheckedChange:(Boolean)->Unit,modifier:Modifier=Modifier,description:String?=null,icon:ImageVector?=null) {
    Row(modifier.fillMaxWidth().heightIn(min=48.dp).tvFocusRing(true,MaterialTheme.shapes.small).toggleable(value=checked,role=Role.Switch,onValueChange=onCheckedChange).padding(16.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(16.dp)) {
        if(icon!=null) Icon(icon,null,Modifier.size(24.dp))
        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
            Text(label,style=MaterialTheme.typography.bodyLarge)
            if(description!=null) Text(description,style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked=checked,onCheckedChange=null)
    }
}
@Composable internal fun SettingsCheckbox(label:String,checked:Boolean,onCheckedChange:(Boolean)->Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min=48.dp).tvFocusRing(true,MaterialTheme.shapes.small).toggleable(value=checked,role=Role.Checkbox,onValueChange=onCheckedChange).padding(horizontal=12.dp,vertical=8.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
        Checkbox(checked=checked,onCheckedChange=null)
        Text(label,style=MaterialTheme.typography.bodyMedium,modifier=Modifier.weight(1f))
    }
}
@Composable internal fun PreferenceChoice(text:String,selected:Boolean,tv:Boolean=false,onClick:()->Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min=48.dp).tvFocusRing(tv).selectable(selected=selected,role=Role.RadioButton,onClick=onClick)
        .semantics {stateDescription=if(selected)"Выбрано" else "Не выбрано"}.padding(horizontal=8.dp,vertical=8.dp),
        verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
        RadioButton(selected=selected,onClick=null)
        Text(text,modifier=Modifier.weight(1f),color=MaterialTheme.colorScheme.onSurface)
    }
}
@Composable internal fun SectionTitle(text:String,action:String?=null,onClick:()->Unit={}) {
    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
        Text(text,fontSize=22.sp,lineHeight=28.sp,fontWeight=FontWeight.Bold,modifier=Modifier.weight(1f).semantics {heading()})
        if(action!=null) TextButton(onClick=onClick) {Text(action);Icon(Icons.Outlined.ArrowForward,null,Modifier.padding(start=6.dp).size(18.dp))}
    }
}
@Composable internal fun NativeCard(tv:Boolean,modifier:Modifier=Modifier,selected:Boolean=false,onLongClick:(()->Unit)?=null,onClick:()->Unit,content:@Composable ()->Unit) {
    val shape=RoundedCornerShape(16.dp);val colors=MaterialTheme.colorScheme
    if(tv) androidx.tv.material3.Card(onClick=onClick,onLongClick=onLongClick,modifier=modifier,scale=androidx.tv.material3.CardDefaults.scale(focusedScale=1f,pressedScale=1f),shape=androidx.tv.material3.CardDefaults.shape(shape=shape),colors=androidx.tv.material3.CardDefaults.colors(containerColor=if(selected) colors.primaryContainer else colors.surface,focusedContainerColor=colors.primaryContainer),border=androidx.tv.material3.CardDefaults.border(focusedBorder=tvFocusBorder(16.dp))) {content()}
    else Card(modifier=modifier.combinedClickable(onClick=onClick,onLongClick=onLongClick),shape=shape,colors=CardDefaults.cardColors(containerColor=if(selected) colors.primaryContainer else colors.surface),border=if(selected) BorderStroke(1.dp,colors.primary) else null) {content()}
}
@Composable internal fun Artwork(metadata:Metadata?,config:Config,modifier:Modifier=Modifier,backdrop:Boolean=false,demo:Boolean=false) {
    Box(modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
        if(demo) Image(painterResource(R.drawable.demo_orbit),null,Modifier.fillMaxSize(),contentScale=ContentScale.Crop,alignment=BiasAlignment(.38f,0f))
        else {
            Icon(Icons.Outlined.Movie,null,Modifier.align(Alignment.Center).size(40.dp),tint=MaterialTheme.colorScheme.onSurfaceVariant)
            val uri=if(config.metadataProvider=="tmdb") if(backdrop) metadata?.backdrop ?: metadata?.poster else metadata?.poster else null
            if(uri!=null) AsyncImage(model=uri,imageLoader=LocalImages.current,contentDescription=null,modifier=Modifier.fillMaxSize(),contentScale=ContentScale.Crop,alignment=BiasAlignment(.38f,0f))
        }
    }
}
@Composable internal fun ArtScrim(modifier:Modifier=Modifier,horizontal:Boolean=false) {
    Box(modifier.background(if(horizontal) Brush.horizontalGradient(0f to Color(0xE80C0E15),.6f to Color(0x330C0E15),1f to Color.Transparent) else Brush.verticalGradient(0f to Color.Transparent,.45f to Color.Transparent,1f to Color(0xFF0C0E15))))
}
internal fun Release.displayTitle()=when(id) {"demo:orbit"->"Тихая орбита";"demo:series"->"Тестовый сериал";else->title}
internal fun fileLabel(path:String):String=path.substringAfterLast('/').substringBeforeLast('.')
internal fun sizeLabel(bytes:Long?)=bytes?.let {if(it>=1_000_000_000) "%.1f ГБ".format(it/1e9) else "%.1f МБ".format(it/1e6)} ?: "Размер не указан"
internal fun titleKind(m:Metadata) = if(m.kind=="tv") "Сериал" else "Фильм"
@Composable internal fun WatchTabs(labels:List<String>,selected:String,tv:Boolean=false,onSelect:(String)->Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(if(tv) 16.dp else 20.dp)) {
        labels.forEach {label->
            val active=selected==label
            Column(Modifier.width(IntrinsicSize.Max).heightIn(min=48.dp).selectableTab(active,tv) {onSelect(label)},horizontalAlignment=Alignment.CenterHorizontally) {
                Text(label,color=if(active) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,fontSize=if(tv) 18.sp else 14.sp,lineHeight=if(tv) 24.sp else 20.sp,fontWeight=FontWeight.Medium,modifier=Modifier.padding(vertical=12.dp))
                Box(Modifier.height(3.dp).widthIn(min=32.dp).fillMaxWidth().background(if(active) MaterialTheme.colorScheme.primary else Color.Transparent))
            }
        }
    }
}
@Composable private fun Modifier.selectableTab(selected:Boolean,tv:Boolean,onClick:()->Unit):Modifier {
    var focused by remember {mutableStateOf(false)}
    return this.onFocusChanged {focused=it.isFocused}.border(if(focused) 2.dp else 0.dp,if(focused) MaterialTheme.colorScheme.primary else Color.Transparent,RoundedCornerShape(8.dp)).background(if(focused) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,RoundedCornerShape(8.dp)).selectable(selected=selected,role=Role.Tab,onClick=onClick).padding(horizontal=4.dp)
}

@Composable internal fun Modifier.tvFocusRing(tv:Boolean,shape:Shape=RoundedCornerShape(24.dp)):Modifier {
    if(!tv) return this
    var focused by remember {mutableStateOf(false)}
    val color=MaterialTheme.colorScheme.primary
    return this.onFocusChanged {focused=it.isFocused}.drawWithContent {
        drawContent()
        if(focused) {
            val offset=4.dp.toPx()
            val outline=shape.createOutline(Size(size.width+offset*2,size.height+offset*2),layoutDirection,this)
            translate(-offset,-offset) {drawOutline(outline,color,style=Stroke(2.dp.toPx()))}
        }
    }
}

/** Keep an already visible TV control in place; the platform pivot otherwise scrolls the hero offscreen. */
internal object TVBringIntoView:androidx.compose.foundation.gestures.BringIntoViewSpec {
    override fun calculateScrollDistance(offset:Float,size:Float,containerSize:Float):Float=when {
        size>containerSize->offset
        offset<0f->offset
        offset+size>containerSize->offset+size-containerSize
        else->0f
    }
}
