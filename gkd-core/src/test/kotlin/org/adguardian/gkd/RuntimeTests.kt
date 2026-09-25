package org.adguardian.gkd

import li.gkd.selector.*
import java.io.File
import java.util.Base64
import java.io.DataInputStream

private class N(val attrs: Map<String, Any?>, val children: List<N> = emptyList()) {
    var parent: N? = null
    init { children.forEach { it.parent = this } }
}
private val adapter = object : NodeAdapter<N>() {
    override fun getAttr(target: Any, name: String): Any? {
        if (target !is N) return null
        return when (name) {
            "parent" -> target.parent
            "childCount" -> target.children.size
            "index" -> target.parent?.children?.indexOf(target) ?: 0
            "depth" -> generateSequence(target.parent) { it.parent }.count()
            else -> target.attrs[name]
        }
    }
    override fun getInvoke(target: Any, name: String, args: List<Any>): Any? =
        if (target is N && name == "getChild") target.children.getOrNull((args[0] as Number).toInt()) else null
    override fun getName(node: N) = node.attrs["name"] as? String
    override fun getChildCount(node: N) = node.children.size
    override fun getChild(node: N, index: Int) = node.children.getOrNull(index)
    override fun getParent(node: N) = node.parent
    override fun getNodeKey(node: N): Any = node
}
private fun node(text: String? = null, vid: String? = null, vararg children: N) = N(
    mapOf("name" to "android.view.View", "text" to text, "vid" to vid,
          "visibleToUser" to true, "clickable" to true, "width" to 80, "height" to 40), children.toList())
private fun raw(key: Int, selector: String, vararg extra: Pair<String,Any?>): Map<String,Any?> =
    mapOf("key" to key, "matches" to selector) + mapOf(*extra)
private fun group(vararg rules: Map<String,Any?>, extra: Map<String,Any?> = emptyMap()): Map<String,Any?> =
    mapOf("key" to 1, "name" to "分段广告-测试", "rules" to rules.toList()) + extra
private fun session(g: Map<String,Any?>) = RuleSession<N>("app.example", listOf(g), emptyList())
private fun RuleSession<N>.query(root: N, time: Long, activity: String? = "app.example.Main", window: Int = 1) =
    find(root, adapter, activity, window, time, setOf("开屏广告", "局部广告", "全屏广告", "分段广告"))
private var cases = 0
private fun test(name: String, body: () -> Unit) {
    try { body(); cases++; println("PASS $name") }
    catch (error: Throwable) { throw AssertionError(name, error) }
}
private fun readFixture(input: DataInputStream): Any? = when(input.readUnsignedByte().toChar()) {
    'n' -> null
    't' -> true
    'f' -> false
    'd' -> input.readDouble()
    's' -> ByteArray(input.readInt()).also { input.readFully(it) }.toString(Charsets.UTF_8)
    'l' -> List(input.readInt()) { readFixture(input) }
    'm' -> LinkedHashMap<String,Any?>().also { map -> repeat(input.readInt()) {
        val key=readFixture(input) as String;map[key]=readFixture(input)
    } }
    else -> error("Invalid test fixture")
}
fun main(args: Array<String>) {
    test("original selector parent sibling target and methods") {
        val close = node(vid="close")
        val ad = node(text="广告")
        val root = node(children = arrayOf(close, ad))
        val selector = Selector.compile("@[vid=\"close\"] + [text=\"广告\"][index=parent.childCount.minus(1)]").value
        check(adapter.querySelector(root, selector) === close)
        check(adapter.querySelector(root, Selector.compile("[text=\"允许\"]").value) == null)
    }
    test("negative selectors prevent normal dialog action") {
        val s = session(group(raw(0,"[vid=\"close\"]", "excludeMatches" to "[text=\"支付\"]")))
        check(s.query(node(children=arrayOf(node(vid="close"),node(text="支付"))),1000) == null)
    }
    test("preKeys needs successful prior action and supports two steps") {
        val s=session(group(raw(0,"[vid=\"ad_more\"]"),raw(1,"[text=\"不感兴趣\"]","preKeys" to listOf(0))))
        val menu=node(children=arrayOf(node(text="不感兴趣")))
        check(s.query(menu,1000)==null)
        val first=s.query(node(children=arrayOf(node(vid="ad_more"))),1100)!!
        s.complete(first,1100,false)
        check(s.query(menu,1200)==null)
        val retry=s.query(node(children=arrayOf(node(vid="ad_more"))),1400)!!
        s.complete(retry,1400,true)
        check(s.query(menu,1600)?.rule?.key==1)
    }
    test("matched rule actionDelay rechecks target and window") {
        val s=session(group(raw(0,"[vid=\"close\"]","actionDelay" to 300)))
        val tree=node(children=arrayOf(node(vid="close")))
        check(s.query(tree,1000)==null)
        check(s.nextWakeUp(1000)==300L)
        check(s.query(tree,1200)==null)
        check(s.query(tree,1300)!=null)
        check(s.query(node(),1400)==null)
        check(s.query(tree,1500,window=2)==null)
        check(s.query(tree,1800,window=2)!=null)
    }
    test("matchDelay and matchTime use entered scope not every event") {
        val s=session(group(raw(0,"[vid=\"close\"]"),extra=mapOf("matchDelay" to 200,"matchTime" to 500)))
        val tree=node(children=arrayOf(node(vid="close")))
        check(s.query(tree,1000)==null)
        check(s.query(tree,1200)!=null)
        check(s.query(tree,1800)==null)
    }
    test("shared maximum and cooldown honor rule keys") {
        val s=session(group(raw(0,"[vid=\"one\"]"),raw(1,"[vid=\"two\"]"),extra=mapOf(
            "actionMaximumKey" to 0,"actionCdKey" to 0,"actionMaximum" to 2,"actionCd" to 500)))
        val first=s.query(node(children=arrayOf(node(vid="one"))),1000)!!;s.complete(first,1000,true)
        val tree=node(children=arrayOf(node(vid="two")))
        check(s.query(tree,1200)==null)
        val second=s.query(tree,1500)!!;s.complete(second,1500,true)
        check(s.query(tree,3000)==null)
    }
    test("activity prefixes and excludes are not discarded") {
        val s=session(group(raw(0,"[vid=\"close\"]","activityIds" to ".Main","excludeActivityIds" to ".MainPayment")))
        val tree=node(children=arrayOf(node(vid="close")))
        check(s.query(tree,1000,"app.example.Other")==null)
        check(s.query(tree,1100,"app.example.MainPayment")==null)
        check(s.query(tree,1200,"app.example.Main")!=null)
    }
    test("global per app exclusions retained") {
        val g=group(raw(0,"[text=\"跳过\"]"),extra=mapOf("name" to "开屏广告-全局","apps" to listOf(mapOf("id" to "bank.app","enable" to false))))
        val s=RuleSession<N>("bank.app",emptyList(),listOf(g))
        check(s.query(node(children=arrayOf(node(text="跳过"))),1000)==null)
    }
    test("global rules do not match unspecified system apps or launchers") {
        val g=group(raw(0,"[text=\"跳过\"]"),extra=mapOf("name" to "开屏广告"))
        val tree=node(children=arrayOf(node(text="跳过")))
        val s=RuleSession<N>("system.app",emptyList(),listOf(g))
        s.setEnvironment(true,false)
        check(s.query(tree,1000)==null)
        s.setEnvironment(false,true)
        check(s.query(tree,1100)==null)
        s.setEnvironment(false,false)
        check(s.query(tree,1200)!=null)
    }
    test("retired alternatives in preKeys do not invalidate the present predecessor") {
        val s=session(group(raw(0,"[vid=\"one\"]"),raw(50,"[vid=\"two\"]","preKeys" to listOf(0,7,9))))
        check(s.query(node(children=arrayOf(node(vid="two"))),1000)==null)
        val first=s.query(node(children=arrayOf(node(vid="one"))),1100)!!
        s.complete(first,1100,true)
        check(s.query(node(children=arrayOf(node(vid="two"))),1200)?.rule?.key==50)
    }
    test("group shorthand and anyMatches") {
        val g=mapOf<String,Any?>("key" to 0,"name" to "开屏广告","rules" to "[text=\"跳过\"]")
        check(session(g).query(node(children=arrayOf(node(text="跳过"))),1000)!=null)
        val s=session(group(mapOf("key" to 0,"anyMatches" to listOf("[text=\"跳过\"]","[vid=\"close\"]"))))
        check(s.query(node(children=arrayOf(node(vid="close"))),1000)!=null)
    }
    test("category off has no action or forced polling") {
        val s=session(group(raw(0,"[vid=\"close\"]"),extra=mapOf("forcedTime" to 1000)))
        check(s.find(node(children=arrayOf(node(vid="close"))),adapter,null,1,1000,emptySet())==null)
        check(s.nextWakeUp(1000)==-1L)
    }
    test("app reset keeps action maximum across activities") {
        val s=session(group(raw(0,"[vid=\"close\"]"),extra=mapOf("actionMaximum" to 1,"resetMatch" to "app")))
        val tree=node(children=arrayOf(node(vid="close")))
        val m=s.query(tree,1000)!!;s.complete(m,1000,true)
        check(s.query(tree,5000,"app.example.Second")==null)
    }
    test("position and swipe expressions preserve screen coordinates") {
        val point=RulePosition(mapOf("left" to "width * 0.9", "top" to "height * 0.5"))
            .resolve(10f,20f,110f,220f,1080f,2400f)!!
        check(point.first==100f && point.second==120f)
        val p=RulePosition(mapOf("x" to "screenWidth / 2", "y" to "screenHeight * 0.3"))
            .resolve(0f,0f,100f,100f,1080f,2400f)!!
        check(p.first==540f && p.second==720f)
        check(RulePosition(mapOf("x" to "1/0", "y" to "0")).resolve(0f,0f,10f,10f,100f,100f)==null)
    }
    test("disabling a rule drops an already matched delayed action") {
        val s=session(group(raw(0,"[vid=\"close\"]","actionDelay" to 100)))
        val tree=node(children=arrayOf(node(vid="close")))
        check(s.query(tree,1000)==null && s.hasPendingMatch)
        s.setRuleFilter { false }
        check(s.query(tree,1100)==null && !s.hasPendingMatch)
        check(s.nextWakeUp(1100)==-1L)
    }
    test("stale completion after session reset cannot consume a new counter") {
        val s=session(group(raw(0,"[vid=\"close\"]"),extra=mapOf("actionMaximum" to 1)))
        val tree=node(children=arrayOf(node(vid="close")))
        val old=s.query(tree,1000)!!
        s.reset()
        val fresh=s.query(tree,1100)!!
        s.complete(old,1100,true)
        check(s.query(tree,1200)!=null)
        s.complete(fresh,1200,true)
    }
    test("user cancellation restarts only the pending action delay") {
        val s=session(group(raw(0,"[vid=\"close\"]","actionDelay" to 300)))
        val tree=node(children=arrayOf(node(vid="close")))
        check(s.query(tree,1000)==null && s.hasPendingMatch)
        s.cancelPending()
        check(!s.hasPendingMatch && s.nextWakeUp(1100)==-1L)
        check(s.query(tree,1200)==null)
        check(s.query(tree,1300)==null)
        check(s.query(tree,1500)!=null)
    }
    test("user cancellation keeps app budget until a genuine session reset") {
        val s=session(group(raw(0,"[vid=\"close\"]"),extra=mapOf("actionMaximum" to 1,"resetMatch" to "app")))
        val tree=node(children=arrayOf(node(vid="close")))
        val first=s.query(tree,1000)!!;s.complete(first,1000,true)
        s.cancelPending()
        check(s.query(tree,5000)==null)
        check(s.query(tree,6000,"app.example.Second")==null)
        s.reset()
        check(s.query(tree,7000)!=null)
    }
    test("target eligibility leaves positive evidence selectors unfiltered") {
        val close=node(vid="close")
        val tree=node(children=arrayOf(node(text="广告"),node(vid="marker"),close))
        val s=session(group(mapOf("matches" to listOf("[text=\"广告\"]","[vid=\"close\"]"),
            "anyMatches" to "[vid=\"marker\"]")))
        val result=s.find(tree,adapter,"app.example.Main",1,1000,setOf("分段广告")) { _, target -> target === close }
        check(result?.node === close)
    }
    test("target eligibility cannot hide exclusion evidence") {
        val close=node(vid="close")
        val tree=node(children=arrayOf(node(text="支付"),close))
        val s=session(group(raw(0,"[vid=\"close\"]","excludeMatches" to "[text=\"支付\"]")))
        check(s.find(tree,adapter,"app.example.Main",1,1000,setOf("分段广告")) { _, target -> target === close } == null)
    }
    test("target eligibility checks later anyMatches alternatives") {
        val accepted=node(vid="two")
        val tree=node(children=arrayOf(node(vid="one"),accepted))
        val s=session(group(mapOf("anyMatches" to listOf("[vid=\"one\"]","[vid=\"two\"]"))))
        val result=s.find(tree,adapter,"app.example.Main",1,1000,setOf("分段广告")) { _, target -> target === accepted }
        check(result?.node === accepted)
    }
    test("denied target cannot arm action delay or a retry wake") {
        val s=session(group(raw(0,"[vid=\"close\"]","actionDelay" to 300)))
        val tree=node(children=arrayOf(node(vid="close")))
        check(s.find(tree,adapter,"app.example.Main",1,1000,setOf("分段广告")) { _, _ -> false } == null)
        check(!s.hasPendingMatch && s.nextWakeUp(1000)==-1L)
    }
    test("none is a state transition not an implicit click") {
        val s=session(group(raw(0,"[vid=\"one\"]","action" to "none"),raw(1,"[vid=\"two\"]","preKeys" to 0)))
        val first=s.query(node(children=arrayOf(node(vid="one"))),1000)!!
        check(first.rule.action=="none")
        s.complete(first,1000,true)
        check(s.query(node(children=arrayOf(node(vid="two"))),1200)?.rule?.key==1)
    }
    test("forced retry ends instead of polling forever") {
        val s=session(group(raw(0,"[vid=\"close\"]"),extra=mapOf("forcedTime" to 500)))
        check(s.query(node(),1000)==null)
        check(s.nextWakeUp(1000)==120L)
        check(s.query(node(),1500)==null)
        check(s.nextWakeUp(1500)==-1L)
    }
    if(args.size>1) test("all uploaded ad rules construct with preserved fields and references") {
        val data=File(args[1]).inputStream().buffered().use { readFixture(DataInputStream(it)) }.obj()
        val globals=data["globalGroups"].items().map { it.obj() }
        var count=0
        var apps=0
        var positions=0
        for(appRaw in data["apps"].items()) {
            val app=appRaw.obj()
            val groups=app["groups"].items().map { it.obj() }
            count+=RuleSession<N>(app["id"] as String,groups,emptyList()).ruleCount
            RuleSession<N>(app["id"] as String,groups,globals)
            apps++
            for(g in groups)for(raw in g["rules"].items()) {
                val r=raw.obj()
                if(r["position"]!=null) {
                    check(RulePosition(r["position"].obj()).resolve(0f,0f,1000f,2000f,1080f,2400f)!=null) { r.toString() }
                    positions++
                }
                if(r["swipeArg"]!=null) {
                    val swipe=RuleSwipe(r["swipeArg"].obj())
                    check(swipe.start.resolve(0f,0f,1000f,2000f,1080f,2400f)!=null)
                    check(swipe.end.resolve(0f,0f,1000f,2000f,1080f,2400f)!=null)
                }
            }
        }
        count+=RuleSession<N>("example.unlisted",emptyList(),globals).ruleCount
        check(apps==753 && count==2462) { "apps=$apps rules=$count" }
        println("Validated $apps packages $count rules $positions position expressions")
    }
    if (args.isNotEmpty()) {
        var count=0
        File(args[0]).forEachLine { encoded ->
            if (encoded.isNotBlank()) {
                val source=String(Base64.getDecoder().decode(encoded), Charsets.UTF_8)
                val result=Selector.compile(source)
                check(result is SelectorCompileResult.Success) { "Selector failed: $source\n${(result as SelectorCompileResult.Failure).error}" }
                count++
            }
        }
        check(count==2622) { "Unexpected corpus size $count" }
        println("PASS all $count imported selector expressions compile with the actual GKD selector")
    }
    println("Runtime tests passed: $cases")
}
