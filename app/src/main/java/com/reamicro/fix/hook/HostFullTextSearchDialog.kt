package com.reamicro.fix.hook

import android.app.Activity
import android.app.Dialog
import android.content.ComponentCallbacks
import android.content.res.Configuration
import android.graphics.Typeface
import android.view.Window
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.border
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.draw.drawBehind
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.reamicro.fix.R
import com.reamicro.fix.core.InjectedModuleContext
import com.reamicro.fix.epub.editor.FontSelectionResolution
import com.reamicro.fix.hook.reader.FullTextSearchResult
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class HostFullTextSearchDialog(
    private val activity:Activity,
    private val root:File?,
    private val initialQuery:String,
    private val initialScroll:Int,
    private val initialTheme:StructureHost232Colors.Snapshot?,
    private val fontSelection:()->String,
    private val onSearch:(String)->Unit,
    private val onChoose:(FullTextSearchResult,Int)->Boolean,
    private val onClosed:(Int)->Unit,
):Dialog(InjectedModuleContext.create(activity),R.style.EpubFullScreenDialog) {
    private data class State(val keyword:String="",val results:List<FullTextSearchResult> = emptyList(),val searching:Boolean=false,val error:String?=null,val active:Int=-1)
    private var state by mutableStateOf(State())
    private var darkHint by mutableStateOf(initialTheme?.dark ?: StructureHome130Style.isDark(activity))
    private val owner=ModuleComposeOwner(activity){dismiss()}
    private var compose:ComposeView?=null
    private var closed=false
    private var firstVisible=initialScroll
    private val callback=object:ComponentCallbacks {
        override fun onConfigurationChanged(config:Configuration){darkHint=StructureHome130Style.isDark(activity)}
        override fun onLowMemory()=Unit
    }
    fun render(keyword:String,results:List<FullTextSearchResult>,searching:Boolean,active:Int=-1){
        if(!closed)state=State(keyword,results,searching,active=active)
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
                runCatching {activity.unregisterComponentCallbacks(callback)};onClosed(firstVisible)}
        }
        activity.registerComponentCallbacks(callback)
        try {super.show();val p=StructureHome130Style.palette(activity,darkHint);configureEpubEdgeToEdgeWindow(window,p.native.pageArgb,p.dark)}
        catch(error:Exception){closed=true;view.disposeComposition();owner.close();activity.unregisterComponentCallbacks(callback);throw error}
    }
    @Composable private fun SearchContent() {
        val native=remember(darkHint) {initialTheme?.takeIf {it.dark==darkHint} ?: StructureHost232Colors.snapshot(activity,darkHint)}
        val palette=remember(native){StructureHome130Style.Palette(native)}
        val fonts by produceState(ReaderSearchUiResources.cachedFonts ?: StructureHome130Style.Fonts(FontFamily.Serif, FontFamily.SansSerif)) {
            value=withContext(Dispatchers.IO) {ReaderSearchUiResources.fonts(activity)}
        }
        val selection=remember {fontSelection()}
        val family by produceState<FontFamily?>(null, selection) {
            value=withContext(Dispatchers.IO) {
            val face=when {
                selection=="system"->Typeface.DEFAULT
                selection.isBlank() || selection=="serif"->null
                else->FontSelectionResolution.file(selection,emptyList())?.let {runCatching {Typeface.createFromFile(it)}.getOrNull()}
            }
            face?.let {FontFamily(it)}
            }
        }
        LaunchedEffect(native){configureEpubEdgeToEdgeWindow(window,native.pageArgb,native.dark)}
        MaterialTheme(colorScheme=native.controlsScheme(),typography=StructureHome130Style.typography(fonts,family)) {
            val keyboard=LocalSoftwareKeyboardController.current
            val requester=remember {FocusRequester()}
            var query by remember {mutableStateOf(initialQuery)}
            val scroll=rememberLazyListState(initialFirstVisibleItemIndex=initialScroll.coerceAtLeast(0))
            val scale=EmbeddedHostUi.scale(activity)
            val density=androidx.compose.ui.platform.LocalDensity.current
            val metrics=EmbeddedHostUi.textMetrics
            val labelSize=if(metrics.labelLargePx>0f)with(density){metrics.labelLargePx.toSp()} else metrics.labelLarge.sp
            val labelLine=if(metrics.labelLinePx>0f)with(density){metrics.labelLinePx.toSp()} else MaterialTheme.typography.labelLarge.lineHeight
            val statusTop=WindowInsets.safeDrawing.getTop(density)
            val searchStyle=MaterialTheme.typography.labelLarge.copy(fontSize=labelSize,lineHeight=labelLine,fontWeight=FontWeight(metrics.labelWeight))
            LaunchedEffect(Unit){if(initialQuery.isBlank()){requester.requestFocus();keyboard?.show()}}
            LaunchedEffect(scroll){snapshotFlow{scroll.firstVisibleItemIndex}.collect{firstVisible=it}}
            fun submit(){keyboard?.hide();onSearch(query.trim())}
            Column(Modifier.fillMaxSize().background(palette.content).drawBehind { drawRect(palette.page,size=androidx.compose.ui.geometry.Size(size.width,statusTop.toFloat())) }.safeDrawingPadding().imePadding()) {
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
                val visible=query.trim()==state.keyword
                val status=ReaderSearchPresentation.status(query,state.keyword,state.searching,state.results.size,state.error)
                if(status!=null)Box(Modifier.fillMaxWidth()) {
                    StructureSectionHeader(status,if(state.error!=null)palette.error else palette.text,
                        Modifier.padding(horizontal=StructureContentStyle.inset))

                    if(state.searching && visible)LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.BottomCenter))
                }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                LazyColumn(Modifier.fillMaxSize(),state=scroll,
                    contentPadding=PaddingValues(start=16.dp,end=16.dp,bottom=16.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                    if(visible)itemsIndexed(state.results,key={index,r->"${r.file.path}|${r.cfi}|$index"}) { index,result ->
                        StructureContentCard(palette,onClick={if(onChoose(result,index))dismiss()}) {
                            val path=remember(result.file, root){ReaderSearchPresentation.path(result.file, root)}
                            StructureTruncatedText(path, MaterialTheme.typography.bodySmall,
                                if(index==state.active)palette.primary else palette.caption, Modifier.fillMaxWidth())
                            HorizontalDivider(Modifier.padding(vertical=12.dp),thickness=1.dp,color=palette.borderVariant)
                            val snippet=remember(result, palette.primary) {buildAnnotatedString {
                                val offset=ReaderSearchPresentation.snippetOffset(result.chapterTitle)
                                append(ReaderSearchPresentation.body(result.chapterTitle, result.snippet))
                                val start=result.snippetMatchStart.coerceIn(0,result.snippet.length)
                                val end=result.snippetMatchEnd.coerceIn(start,result.snippet.length)
                                if(end>start)addStyle(SpanStyle(color=palette.primary,fontWeight=FontWeight.Bold),offset+start,offset+end)
                            }}
                            Text(snippet,style=MaterialTheme.typography.labelLarge.copy(fontWeight=FontWeight.Normal),color=palette.text)
                        }
                    }
                }
                if(visible)ReaderSearchFastScroller(scroll,palette,scale)
                }
            }
        }
    }
}
