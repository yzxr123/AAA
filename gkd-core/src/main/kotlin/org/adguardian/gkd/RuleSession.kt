package org.adguardian.gkd

import li.gkd.selector.MatchOptions
import li.gkd.selector.NodeAdapter
import li.gkd.selector.Selector

internal typealias Obj = Map<String, Any?>
internal fun Any?.items(): List<Any?> = when (this) { null -> emptyList(); is List<*> -> this; else -> listOf(this) }
internal fun Any?.texts() = items().map { it as String }
internal fun Any?.ints() = items().map { (it as Number).toInt() }
@Suppress("UNCHECKED_CAST")
internal fun Any?.obj(): Obj = this as? Map<String, Any?> ?: emptyMap()
internal fun Obj.long(key: String, fallback: Long = 0) = (this[key] as? Number)?.toLong() ?: fallback
internal fun Obj.intOrNull(key: String) = (this[key] as? Number)?.toInt()
internal fun Obj.inherit(group: Obj, key: String): Any? = this[key] ?: group[key]
internal fun Any?.number(fallback: Long = 0) = (this as? Number)?.toLong() ?: fallback

class CompiledRule internal constructor(
    val groupKey: Int,
    val groupName: String,
    val global: Boolean,
    val ordinal: Int,
    internal val raw: Obj,
    internal val group: Obj,
    private val selectorCache: MutableMap<String, Selector>,
) {
    val key = raw.intOrNull("key")
    val category = groupName.substringBefore('-')
    val action = raw["action"] as? String ?: when {
        raw["position"] != null -> "clickCenter"
        raw["swipeArg"] != null -> "swipe"
        else -> "click"
    }
    val position = raw["position"]?.obj()?.let(::RulePosition)
    val swipe = raw["swipeArg"]?.obj()?.let(::RuleSwipe)
    val matchRoot = raw.inherit(group, "matchRoot") == true
    val options = MatchOptions(raw.inherit(group, "fastQuery") == true)
    val order = raw.inherit(group, "order").number().toInt()
    val resetMatch = raw.inherit(group, "resetMatch") as? String ?: "activity"
    val matchDelay = raw.inherit(group, "matchDelay").number()
    val actionDelay = raw.inherit(group, "actionDelay").number()
    val matchTime = (raw.inherit(group,"matchTime") as? Number)?.toLong()
    val forcedTime = raw.inherit(group,"forcedTime").number()
    val priorityTime = raw.inherit(group,"priorityTime").number()
    val priorityMaximum = raw.inherit(group,"priorityActionMaximum").number(1)
    internal val preKeys = raw["preKeys"].ints()
    internal val scopeKeys = group["scopeKeys"].ints()
    internal val cdKey = (raw.inherit(group,"actionCdKey") as? Number)?.toInt()
    internal val maximumKey = (raw.inherit(group,"actionMaximumKey") as? Number)?.toInt()
    internal val matches = selectors("matches")
    internal val anyMatches = selectors("anyMatches")
    internal val excludeMatches = selectors("excludeMatches")
    internal val excludeAllMatches = selectors("excludeAllMatches")
    internal val activityIds = raw.inherit(group, "activityIds").texts()
    internal val excludeActivityIds = raw.inherit(group, "excludeActivityIds").texts()
    internal val appOverrides = raw.inherit(group,"apps").items().map { it.obj() }
    internal val disabled = raw["enable"] == false || group["enable"] == false
    internal var actionCd = raw.inherit(group,"actionCd").number(1000)
    internal var actionMaximum = (raw.inherit(group,"actionMaximum") as? Number)?.toInt()
    internal var counter = Counter()
    internal var cooldown = Cooldown()
    internal var preRules: Set<CompiledRule> = emptySet()
    internal var scopeSince = -1L
    internal var pendingSince = -1L
    internal var pendingNode: Any? = null
    internal var failedUntil = 0L

    init {
        require(action in setOf("click","clickNode","clickCenter","longClick","longClickNode","longClickCenter","back","none","swipe")) { "Unknown GKD action $action" }
        require(matches.isNotEmpty() || anyMatches.isNotEmpty()) { "Rule has no positive selector $groupName/$key" }
    }
    private fun selectors(field: String) = raw[field].texts().map { source ->
        selectorCache.getOrPut(source) { Selector.compile(source).value }
    }
    internal fun clearPending() { pendingSince = -1; pendingNode = null }
    internal fun reset(now: Long) {
        scopeSince = now; counter.value = 0; cooldown.last = -1
        clearPending(); failedUntil = 0
    }
    internal fun inScope(packageId: String, activity: String?, systemApp: Boolean, launcher: Boolean): Boolean {
        if (disabled) return false
        fun fixed(value: String) = if (value.startsWith('.')) packageId + value else value
        if (activity != null && excludeActivityIds.any { activity.startsWith(fixed(it)) }) return false
        if (activity != null && activityIds.isNotEmpty() && activityIds.none { activity.startsWith(fixed(it)) }) return false
        if (global) {
            val app = appOverrides.find { it["id"] == packageId }
            if (app?.get("enable") == false) return false
            if (app == null && (raw.inherit(group,"matchAnyApp") == false ||
                (systemApp && raw.inherit(group,"matchSystemApp") != true) ||
                (launcher && raw.inherit(group,"matchLauncher") != true))) return false
            if (app != null && activity != null) {
                if (app["excludeActivityIds"].texts().any { activity.startsWith(fixed(it)) }) return false
                val included = app["activityIds"].texts()
                if (included.isNotEmpty() && included.none { activity.startsWith(fixed(it)) }) return false
            }
        }
        return true
    }
    internal class Counter(var value: Int = 0)
    internal class Cooldown(var last: Long = -1)
}

class RuleMatch<N : Any> internal constructor(
    val rule: CompiledRule,
    val node: N,
    internal val epoch: Long,
)

class RuleSession<N : Any>(
    val packageName: String,
    appGroups: List<Map<String, Any?>>,
    globalGroups: List<Map<String, Any?>>,
) {
    private val selectorCache = HashMap<String, Selector>()
    private var systemApp=false
    private var launcher=false
    fun setEnvironment(isSystemApp: Boolean,isLauncher: Boolean) { systemApp=isSystemApp;launcher=isLauncher }
    private val rules: List<CompiledRule>
    private var previousActivity: String? = null
    private var previousWindow = Int.MIN_VALUE
    private var initialized = false
    private var epoch = 0L
    private var lastTriggered: CompiledRule? = null
    private var priorActive: Set<CompiledRule> = emptySet()
    private var wakeAt = Long.MAX_VALUE
    private var pending: RuleMatch<N>? = null
    var hasPendingMatch: Boolean = false
        private set
    private var ruleFilter = java.util.function.Predicate<CompiledRule> { true }
    fun setRuleFilter(filter: java.util.function.Predicate<CompiledRule>) { ruleFilter=filter }
    val ruleCount: Int get() = rules.size
    val compiledSelectorCount: Int get() = selectorCache.size

    init {
        val output = ArrayList<CompiledRule>()
        fun addGroups(groups: List<Obj>, global: Boolean) {
            for (group in groups) {
                val name = group["name"] as String
                if (global) {
                    val prefix = group["disableIfAppGroupMatch"] as? String
                    if (prefix != null && appGroups.any {
                        it["enable"] != false && it["ignoreGlobalGroupMatch"] != true && (it["name"] as String).startsWith(prefix)
                    }) continue
                }
                val key = (group["key"] as Number).toInt()
                group["rules"].items().forEachIndexed { i, value ->
                    val raw = if (value is String) mapOf("matches" to value) else value.obj()
                    output.add(CompiledRule(key, name, global, i, raw, group, selectorCache))
                }
            }
        }
        addGroups(appGroups, false)
        addGroups(globalGroups, true)
        rules = output.sortedWith(compareBy<CompiledRule> { it.order }.thenBy { it.global })
        for (rule in rules) {
            val scope = rules.filter { other -> other.global == rule.global &&
                (other.groupKey == rule.groupKey || other.groupKey in rule.scopeKeys) }
            rule.preRules = scope.filter { it.key != null && it.key in rule.preKeys }.toSet()
            rule.cdKey?.let { key ->
                val ref = scope.find { it.key == key }
                if (ref != null) {
                    val old = rule.cooldown
                    rules.filter { it.cooldown === old }.forEach { it.cooldown = ref.cooldown }
                    rule.actionCd = rule.raw["actionCd"].number(ref.raw["actionCd"].number(rule.group["actionCd"].number(1000)))
                }
            }
            rule.maximumKey?.let { key ->
                val ref = scope.find { it.key == key }
                if (ref != null) {
                    val old = rule.counter
                    rules.filter { it.counter === old }.forEach { it.counter = ref.counter }
                    rule.actionMaximum = (rule.raw["actionMaximum"] ?: ref.raw["actionMaximum"] ?: rule.group["actionMaximum"] as? Number).let { (it as? Number)?.toInt() }
                }
            }
        }
    }

    fun reset() {
        initialized=false; epoch++; lastTriggered=null; pending=null; hasPendingMatch=false
        priorActive=emptySet(); wakeAt=Long.MAX_VALUE
    }

    /** User input cancels a pending chain without renewing the current scope's budgets. */
    fun cancelPending() {
        epoch++; lastTriggered=null; pending=null; hasPendingMatch=false
        wakeAt=Long.MAX_VALUE
        rules.forEach { it.clearPending() }
    }

    @JvmOverloads
    fun find(root: N, adapter: NodeAdapter<N>, activity: String?, windowId: Int,
             now: Long, enabledCategories: Set<String>,
             targetFilter: java.util.function.BiPredicate<CompiledRule, N> = java.util.function.BiPredicate { _, _ -> true }): RuleMatch<N>? {
        pending = null
        hasPendingMatch=false
        wakeAt = Long.MAX_VALUE
        val active = rules.filter { it.category in enabledCategories && ruleFilter.test(it) && it.inScope(packageName, activity, systemApp, launcher) }
        val activityChanged = initialized && activity != previousActivity
        val windowChanged = initialized && windowId != previousWindow
        if (!initialized || activityChanged || windowChanged) epoch++
        for (rule in rules) {
            if (!initialized) rule.reset(now)
            else if (activityChanged && rule in active && (rule.resetMatch == "activity" ||
                         (rule.resetMatch == "match" && rule !in priorActive))) rule.reset(now)
            if (windowChanged || rule !in active) rule.clearPending()
        }
        initialized = true; previousActivity=activity; previousWindow=windowId; priorActive=active.toSet()
        val ordered = active.sortedWith(compareByDescending<CompiledRule> {
            it.priorityTime > 0 && it.counter.value < it.priorityMaximum && now-it.scopeSince < it.priorityTime+it.matchDelay
        }.thenBy { it.order }.thenBy { it.global })
        for (rule in ordered) {
            if (rule.actionMaximum != null && rule.counter.value >= rule.actionMaximum!!) continue
            if (rule.preRules.isNotEmpty() && lastTriggered !in rule.preRules) continue
            val start = rule.scopeSince+rule.matchDelay
            if (now < start) { wake(start); continue }
            if (rule.matchTime != null && now > start+rule.matchTime) continue
            if (now < rule.failedUntil) { wake(rule.failedUntil); continue }
            if (rule.cooldown.last >= 0 && now < rule.cooldown.last+rule.actionCd) {
                wake(rule.cooldown.last+rule.actionCd); continue
            }
            if (rule.forcedTime > 0 && now < start+rule.forcedTime) {
                val age = now - rule.scopeSince
                val interval = when { age < 1000 -> 120; age < 3000 -> 280; else -> 600 }
                wake(minOf(start+rule.forcedTime, now+interval))
            }
            val target = query(rule, root, adapter, targetFilter)
            if (target == null) { rule.clearPending(); continue }
            hasPendingMatch=true
            if (rule.actionDelay > 0) {
                val nodeKey = adapter.getNodeKey(target)
                if (rule.pendingSince < 0 || rule.pendingNode != nodeKey) {
                    rule.pendingSince = now; rule.pendingNode = nodeKey
                }
                val due = rule.pendingSince+rule.actionDelay
                if (now < due) { wake(due); continue }
            }
            return RuleMatch(rule, target, epoch).also { pending=it }
        }
        return null
    }

    private fun query(rule: CompiledRule, root: N, adapter: NodeAdapter<N>,
                      targetFilter: java.util.function.BiPredicate<CompiledRule, N>): N? {
        fun find(selector: Selector, actionTarget: Boolean = false): N? {
            val node = if (rule.matchRoot || selector.isMatchRoot) adapter.getRoot(root) ?: root else root
            fun allowed(candidate: N) = !actionTarget || targetFilter.test(rule,candidate)
            selector.match(node,adapter,rule.options)?.let { if (allowed(it)) return it }
            if (selector.isMatchRoot) return null
            val first = adapter.querySelector(node,selector,rule.options) ?: return null
            if (allowed(first)) return first
            // Preserve the fast first-match path; only enumerate alternatives when it is denied.
            return adapter.querySelectorAll(node,selector,rule.options).firstOrNull(::allowed)
        }
        var result: N? = null
        if (rule.anyMatches.isNotEmpty()) {
            for (selector in rule.anyMatches) {
                result = find(selector,rule.matches.isEmpty())
                if (result != null) break
            }
            if (result == null) return null
        }
        for ((index,selector) in rule.matches.withIndex()) {
            // Earlier positive selectors are evidence, not the node receiving the action.
            result = find(selector,index == rule.matches.lastIndex) ?: return null
        }
        for (selector in rule.excludeMatches) if (find(selector) != null) return null
        // Same contract as the supplied A11yContext queryRule implementation.
        for (selector in rule.excludeAllMatches) if (find(selector) != null) return null
        return result
    }

    fun complete(match: RuleMatch<N>, now: Long, success: Boolean) {
        if (pending !== match || match.epoch != epoch) return
        pending = null
        val rule = match.rule
        rule.clearPending()
        if (success) {
            rule.cooldown.last=now; rule.counter.value++; lastTriggered=rule
            wake(now+120)
        } else {
            rule.failedUntil=now+300
            wake(rule.failedUntil)
        }
    }
    private fun wake(at: Long) { if (at<wakeAt) wakeAt=at }
    fun nextWakeUp(now: Long): Long = if (wakeAt==Long.MAX_VALUE) -1L else maxOf(1L,wakeAt-now)
}
