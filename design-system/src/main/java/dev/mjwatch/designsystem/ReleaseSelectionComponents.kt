package dev.mjwatch.designsystem

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.mjwatch.core.*
import kotlinx.coroutines.delay

@Composable internal fun ReleaseFilterChoices(
    title:String,icon:ImageVector,options:List<String>,selected:String,tv:Boolean,hint:String?=null,
    selectedFocus:FocusRequester?=null,onSelect:(String)->Unit,
) {
    SettingsCard(title,icon,tv) {
        FlowRow(modifier=if(tv) Modifier.focusGroup() else Modifier,
            horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(if(tv)8.dp else 0.dp)) {
            options.forEach {label->key(label) {
                val active=selected==label
                val modifier=if(tv&&active&&selectedFocus!=null) Modifier.focusRequester(selectedFocus) else Modifier
                Pill(if(label=="2160p")"4K" else label,active,tv,modifier=modifier) {onSelect(label)}
            }}
        }
        if(hint!=null)Text(hint,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable internal fun ReleaseFiltersDialog(
    tv:Boolean,count:Int?,initialFocus:FocusRequester,onReset:()->Unit,onDismiss:()->Unit,
    content:@Composable ColumnScope.()->Unit,
) {
    val configuration=LocalConfiguration.current
    Dialog(onDismissRequest=onDismiss,properties=DialogProperties(usePlatformDefaultWidth=false)) {
        val windowFocused=LocalWindowInfo.current.isWindowFocused
        var focusPlaced by remember {mutableStateOf(false)}
        LaunchedEffect(windowFocused) {
            if(tv&&windowFocused&&!focusPlaced) {
                focusPlaced=initialFocus.requestFocus()
            }
        }
        Surface(shape=MaterialTheme.shapes.large,color=MaterialTheme.colorScheme.background,modifier=Modifier.testTag("release-filters-dialog").padding(16.dp).widthIn(max=if(tv)840.dp else 640.dp).fillMaxWidth().heightIn(max=(configuration.screenHeightDp*.92f).dp)) {
            Column {
                Row(Modifier.fillMaxWidth()
                    .then(if(windowFocused&&focusPlaced) Modifier.testTag("release-filters-ready") else Modifier)
                    .padding(start=20.dp,end=8.dp,top=8.dp,bottom=8.dp),horizontalArrangement=Arrangement.spacedBy(12.dp),verticalAlignment=Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Tune,null,tint=MaterialTheme.colorScheme.primary)
                    Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                        Text("Фильтры раздач",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold,modifier=Modifier.semantics{heading()})
                        Text(if(count==null) "Применяем фильтры…" else "Найдено: $count",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.semantics{liveRegion=LiveRegionMode.Polite})
                    }
                    IconButton(onClick=onDismiss,modifier=Modifier.tvFocusRing(tv)){Icon(Icons.Outlined.Close,"Закрыть фильтры")}
                }
                HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant)
                Column(Modifier.weight(1f,fill=false).verticalScroll(rememberScrollState()).testTag("release-filters").padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp),content=content)
                HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant)
                Column(Modifier.padding(horizontal=16.dp,vertical=8.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                    Action(if(count==null) "Показать результаты" else "Показать результаты ($count)",tv,Modifier.fillMaxWidth(),icon=Icons.Outlined.Check,onClick=onDismiss)
                    TextButton(onClick=onReset,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).tvFocusRing(tv)){Text("Сбросить всё")}
                }
            }
        }
    }
}

internal val TorrentFile.canDownload get()=video&&size>0&&serverFileId>0

@Composable internal fun FileCard(file:TorrentFile,tv:Boolean,selected:Boolean,progress:Progress?=null,modifier:Modifier=Modifier,
    multiSelect:Boolean=false,onClick:()->Unit) {
    val location=remember {BringIntoViewRequester()}
    LaunchedEffect(selected,multiSelect) {if(!tv&&selected&&!multiSelect){delay(100);location.bringIntoView()}}
    val number=remember(file.path){episodeNumber(file.path)}
    val cardModifier=modifier.fillMaxWidth().testTag("file-${file.serverFileId}")
        .bringIntoViewRequester(location).semantics {
            if(multiSelect) {
                role=Role.Checkbox
                toggleableState=ToggleableState(selected)
                if(!file.canDownload) disabled()
            } else {
                this.selected=selected
                role=Role.RadioButton
            }
        }
    NativeCard(tv,cardModifier,selected,onClick=onClick) {
        Row(Modifier.padding(16.dp),horizontalArrangement=Arrangement.spacedBy(12.dp),verticalAlignment=Alignment.CenterVertically) {
            Surface(shape=MaterialTheme.shapes.small,color=if(selected)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,contentColor=if(selected)MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.size(44.dp)) {
                Box(contentAlignment=Alignment.Center) {
                    if(number.episode!=null&&!file.sample)Text(number.episode.toString().padStart(2,'0'),style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold)
                    else Icon(if(file.video)Icons.Outlined.Movie else Icons.Outlined.Description,null)
                }
            }
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                Text(number.episode?.let {"${number.season?.let {s->"Сезон $s · "} ?: ""}Серия $it"} ?: fileLabel(file.path),style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold)
                Text(file.path,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=2,overflow=TextOverflow.Ellipsis)
                FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                    Badge(sizeLabel(file.size))
                    if(file.sample)Badge("Sample",warning=true)
                    if(progress?.completed==true)Badge("Просмотрено",accent=true)
                    else if(progress!=null&&progress.positionMs>0)Badge("Продолжить с ${episodeTime(progress.positionMs)}",accent=true)
                }
                if(progress!=null&&!progress.completed&&(progress.durationMs ?: 0)>0)LinearProgressIndicator(progress={(progress.positionMs.toFloat()/progress.durationMs!!.toFloat()).coerceIn(0f,1f)},modifier=Modifier.fillMaxWidth().clearAndSetSemantics{},gapSize=0.dp,drawStopIndicator={})
            }
            if(multiSelect&&file.canDownload) Icon(
                if(selected)Icons.Outlined.CheckBox else Icons.Outlined.CheckBoxOutlineBlank,null,
                tint=if(selected)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            else if(selected)Icon(Icons.Outlined.CheckCircle,null,tint=MaterialTheme.colorScheme.primary)
        }
    }
}

private fun episodeTime(ms:Long):String="%d:%02d".format(ms/60000,ms/1000%60)
