package com.reamicro.fix.hook

import android.app.Activity
import android.app.Dialog
import android.content.ComponentCallbacks
import android.content.res.Configuration
import android.view.Window
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.reamicro.fix.R
import com.reamicro.fix.core.InjectedModuleContext
import com.reamicro.fix.hook.reader.FullTextSearchResult
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class HostFullTextSearchDialog(
    private val activity:Activity,
    private val root:File?,
    private val initialQuery:String,
    private val initialScroll:Int,
    private val initialScrollOffset:Int,
    private val initialTheme:StructureHost232Colors.Snapshot?,
    private val fontSelection:()->String,
    private val onSearch:(String)->Unit,
    private val onChoose:(FullTextSearchResult,Int)->Boolean,
    private val onClosed:(Int,Int)->Unit,
):Dialog(InjectedModuleContext.create(activity),R.style.EpubFullScreenDialog) {
    private data class State(val keyword:String="",val results:List<FullTextSearchResult> = emptyList(),val searching:Boolean=false,val error:String?=null,val active:Int=-1)
    private var state by mutableStateOf(State())
    private var darkHint by mutableStateOf(initialTheme?.dark ?: StructureHome130Style.isDark(activity))
    private val owner=ModuleComposeOwner(activity){dismiss()}
    private var compose:ComposeView?=null
    private var closed=false
    private var firstVisible=initialScroll
    private var firstVisibleOffset=initialScrollOffset
    private var scrollResetEpoch by mutableIntStateOf(0)
    private val callback=object:ComponentCallbacks {
        override fun onConfigurationChanged(config:Configuration){darkHint=StructureHome130Style.isDark(activity)}
        override fun onLowMemory()=Unit
    }
    fun render(keyword:String,results:List<FullTextSearchResult>,searching:Boolean,active:Int=-1,resetScroll:Boolean=false){
        if(!closed){
            state=State(keyword,results,searching,active=active)
            if(resetScroll)scrollResetEpoch++
        }
    }
    fun error(message:String){if(!closed)state=state.copy(searching=false,error=message,results=emptyList())}
    override fun show() {
        if(isShowing)return
        if(activity.isFinishing || activity.isDestroyed)return
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        owner.start()
        val view=ComposeView(context).apply {
            setViewTreeLifecycleOwner(owner);setViewTreeSavedStateRegistryOwner(owner)
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent { this@HostFullTextSearchDialog.SearchContent() }
        }
        compose=view;setContentView(view)
        window?.decorView?.apply {
            setViewTreeLifecycleOwner(owner);setViewTreeSavedStateRegistryOwner(owner)
            findViewById<android.view.View>(android.R.id.content)?.apply {setViewTreeLifecycleOwner(owner);setViewTreeSavedStateRegistryOwner(owner)}
        }
        setOnDismissListener {
            if(!closed){closed=true;compose?.disposeComposition();compose=null;owner.close()
                runCatching {activity.unregisterComponentCallbacks(callback)};onClosed(firstVisible,firstVisibleOffset)}
        }
        activity.registerComponentCallbacks(callback)
        try {
            val p=initialTheme ?: StructureHost232Colors.snapshot(activity,darkHint)
            configureEpubEdgeToEdgeWindow(window,p.pageArgb,p.dark)
            window?.setWindowAnimations(0)
            super.show()
        }
        catch(error:Exception){closed=true;view.disposeComposition();owner.close();activity.unregisterComponentCallbacks(callback);throw error}
    }
    @Composable private fun SearchContent() {
        val native=remember(darkHint) {initialTheme?.takeIf {it.dark==darkHint} ?: StructureHost232Colors.snapshot(activity,darkHint)}
        val palette=remember(native){StructureHome130Style.Palette(native)}
        val selection=remember {fontSelection()}
        val resources by produceState<ReaderSearchUiResources.TextResources?>(null, selection) {
            value=withContext(Dispatchers.IO) {ReaderSearchUiResources.text(activity,selection)}
        }
        LaunchedEffect(native){configureEpubEdgeToEdgeWindow(window,native.pageArgb,native.dark)}
        // 字体全部就绪后才首次测量列表，避免先用默认字体再替换造成跳动。
        val text=resources
        if(text==null){Box(Modifier.fillMaxSize().background(palette.content));return}
        MaterialTheme(colorScheme=native.controlsScheme(),typography=StructureHome130Style.typography(text.fonts,text.family)) {
            val keyboard=LocalSoftwareKeyboardController.current
            val requester=remember {FocusRequester()}
            var query by remember {mutableStateOf(initialQuery)}
            val scroll=rememberLazyListState(initialFirstVisibleItemIndex=initialScroll.coerceAtLeast(0),
                initialFirstVisibleItemScrollOffset=initialScrollOffset.coerceAtLeast(0))
            val scale=remember {EmbeddedHostUi.scale(activity)}
            val density=androidx.compose.ui.platform.LocalDensity.current
            val metrics=remember {EmbeddedHostUi.textMetrics}
            val labelSize=if(metrics.labelLargePx>0f)with(density){metrics.labelLargePx.toSp()} else metrics.labelLarge.sp
            val labelLine=if(metrics.labelLinePx>0f)with(density){metrics.labelLinePx.toSp()} else MaterialTheme.typography.labelLarge.lineHeight
            val statusTop=WindowInsets.safeDrawing.getTop(density)
            val searchStyle=MaterialTheme.typography.labelLarge.copy(fontSize=labelSize,lineHeight=labelLine,fontWeight=FontWeight(metrics.labelWeight))
            LaunchedEffect(Unit){if(initialQuery.isBlank()){requester.requestFocus();keyboard?.show()}}
            LaunchedEffect(scroll){snapshotFlow{scroll.firstVisibleItemIndex to scroll.firstVisibleItemScrollOffset}
                .collect{(index,offset)->firstVisible=index;firstVisibleOffset=offset}}
            LaunchedEffect(scrollResetEpoch){if(scrollResetEpoch>0)scroll.scrollToItem(0)}
            fun submit(){keyboard?.hide();onSearch(query.trim())}
            Column(Modifier.fillMaxSize().background(palette.content).drawBehind { drawRect(palette.page,size=androidx.compose.ui.geometry.Size(size.width,statusTop.toFloat())) }
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top+WindowInsetsSides.Horizontal)).imePadding()) {
                Row(Modifier.fillMaxWidth().background(palette.page).padding(horizontal=16.dp,vertical=8.dp),verticalAlignment=Alignment.CenterVertically) {
                    Row(Modifier.weight(1f).heightIn(min=(EmbeddedHostMetrics.SEARCH_HEIGHT*scale).dp)
                        .border((EmbeddedHostMetrics.SEARCH_BORDER*scale).dp,palette.borderVariant,CircleShape)
                        .padding(start=12.dp,end=4.dp),verticalAlignment=Alignment.CenterVertically) {
                        BasicTextField(query,{query=it;state=state.copy(error=null);if(it.isBlank())onSearch("")},Modifier.weight(1f).padding(horizontal=8.dp,vertical=8.dp).focusRequester(requester),
                            singleLine=true,textStyle=searchStyle.copy(color=palette.text),
                            cursorBrush=SolidColor(palette.primary),keyboardOptions=KeyboardOptions(imeAction=ImeAction.Search),keyboardActions=KeyboardActions(onSearch={submit()}),
                            decorationBox={inner->Box {if(query.isEmpty())Text("输入关键词搜索全书",style=searchStyle,color=palette.caption,maxLines=1);inner()}})
                        if(query.isNotEmpty())IconButton(onClick={query="";onSearch("")},modifier=Modifier.size(32.dp)) {
                            Icon(StructureHome130Icons.Close,"清空搜索",Modifier.size(18.dp),tint=palette.caption)
                        }
                    }
                    TextButton(onClick={submit()},enabled=query.isNotBlank()) { Text("搜索",color=if(query.isNotBlank())palette.primary else palette.caption) }
                    TextButton(onClick={dismiss()}) { Text("取消",color=palette.text) }
                }
                // 延迟执行的 key/content 必须捕获同一批结果，不能重新读取已被清空的新状态。
                val page=state
                val results=page.results
                val visible=query.trim()==page.keyword
                val status=ReaderSearchPresentation.status(query,page.keyword,page.searching,results.size,page.error)
                val sections=remember(results) {
                    ReaderSearchPresentation.sections(results.map {it.volumeTitle})
                }
                val bottomInset=with(density){WindowInsets.safeDrawing.getBottom(this).toDp()}
                Box(Modifier.weight(1f).fillMaxWidth().clipToBounds()) {
                LazyColumn(Modifier.fillMaxSize(),state=scroll,
                    contentPadding=PaddingValues(bottom=bottomInset+16.dp)) {
                    if(visible)sections.forEach { section ->
                        stickyHeader(key="volume:${section.start}") {
                            Row(Modifier.fillMaxWidth().height(48.dp).background(palette.content).padding(horizontal=16.dp),
                                verticalAlignment=Alignment.CenterVertically) {
                                Spacer(Modifier.weight(1f))
                                Text(section.volume,Modifier.weight(1f).padding(start=12.dp),
                                    style=MaterialTheme.typography.bodySmall,color=palette.caption,textAlign=TextAlign.End,
                                    maxLines=1,overflow=TextOverflow.Ellipsis)
                            }
                        }
                        itemsIndexed(results.subList(section.start,section.endExclusive),key={localIndex,result->
                            val index=section.start+localIndex
                            "${result.file.path}|${result.cfi}|$index"
                        }) { localIndex,result ->
                        val index=section.start+localIndex
                        val gap=if(index+1<section.endExclusive)16.dp else 0.dp
                        val active=index==page.active
                        Surface(onClick={if(onChoose(result,index))dismiss()},
                            modifier=Modifier.padding(start=16.dp,end=16.dp,bottom=gap).fillMaxWidth().semantics {selected=active},
                            shape=RoundedCornerShape(StructureContentStyle.corner),
                            color=if(active)palette.primarySoft else palette.page,
                            border=BorderStroke(if(active)1.5.dp else StructureContentStyle.metadataBorderWidth,
                                if(active)palette.primary else palette.borderHigh)) {
                        Column(Modifier.padding(StructureContentStyle.inset)) {
                            Row(verticalAlignment=Alignment.CenterVertically) {
                                Text(result.displayChapterTitle,Modifier.weight(1f),style=MaterialTheme.typography.bodySmall,
                                    color=if(active)palette.primary else palette.caption,maxLines=1,overflow=TextOverflow.Ellipsis)
                                if(active)Text("当前查看",Modifier.padding(start=8.dp),style=MaterialTheme.typography.bodySmall,color=palette.primary)
                            }
                            HorizontalDivider(Modifier.padding(vertical=12.dp),thickness=1.dp,color=palette.borderVariant)
                            val snippet=remember(result, palette.primary) {buildAnnotatedString {
                                append(result.snippet)
                                val start=result.snippetMatchStart.coerceIn(0,result.snippet.length)
                                val end=result.snippetMatchEnd.coerceIn(start,result.snippet.length)
                                if(end>start)addStyle(SpanStyle(color=palette.primary,fontWeight=FontWeight.Bold),start,end)
                            }}
                            Text(snippet,Modifier.fillMaxWidth(),style=MaterialTheme.typography.labelLarge.copy(fontWeight=FontWeight.Normal),
                                color=palette.text,minLines=2,maxLines=2,overflow=TextOverflow.Ellipsis)
                        }
                        }
                        }
                    }
                }
                // 计数固定在左侧，右侧卷标由 LazyColumn 吸顶并被下一卷自然顶走。
                if(status!=null)Row(Modifier.fillMaxWidth().align(Alignment.TopStart).padding(horizontal=16.dp),
                    verticalAlignment=Alignment.CenterVertically) {
                    Box(Modifier.weight(1f).height(48.dp).background(palette.content),contentAlignment=Alignment.CenterStart) {
                        Text(status,Modifier.fillMaxWidth(),
                            style=MaterialTheme.typography.labelLarge,color=if(page.error!=null)palette.error else palette.text,
                            maxLines=1,overflow=TextOverflow.Ellipsis)
                        if(page.searching && visible)LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.BottomCenter))
                    }
                    Spacer(Modifier.weight(1f))
                }
                if(visible)ReaderSearchFastScroller(scroll,palette,scale)
                }
            }
        }
    }
}
