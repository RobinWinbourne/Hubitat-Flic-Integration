/**
 *  Flic Integration (Buttons & Twist) V1.2
 */

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.transform.Field

@Field String TCP_DRIVER_NS   = "local.flic"
@Field String TCP_DRIVER_NAME = "Flic Integration TCP Driver"

// Twist function enums
@Field Map TW_FUNCS = [
    "brightness": "Brightness",
    "ct": "Colour Temperature",
    "color": "Colour",
    "sat": "Saturation",
    "vol": "Volume",
    "blinds": "Blinds Level"
]

// Push & Twist modes
@Field Map TW_PUSH_MODES = [
    "brightness": "Brightness",
    "ct": "Colour Temperature",
    "color": "Colour",
    "sat": "Saturation",
    "vol": "Volume",
    "blinds": "Blinds Level",
    "sel_basic": "Selector - Basic (3/6/9)",
    "sel_adv": "Selector - Advanced (1-11)"
]

@Field Integer SYNC_SUPPRESS_MS = 1200      // ignore “bounce-back” device events for 1.2s
@Field Integer SYNC_DEDUP_MS     = 400      // optional dedupe window for same value spam

@Field Integer MAX_MULTI_ACTIONS = 5

definition(
    name: "Flic Integration V1.2",
    namespace: "local.flic",
    author: "Robin Winbourne",
    description: "Discover Flic devices via TCP and Sync actions with Hubitat",
    category: "Convenience",
    singleInstance: true,
    oauth: true,
    iconUrl: "https://raw.githubusercontent.com/hubitat/HubitatPublic/master/icons/light.png",
    iconX2Url: "https://raw.githubusercontent.com/hubitat/HubitatPublic/master/icons/light@2x.png"
)

preferences {
    page(name: "mainPage")
    page(name: "discoverPage")
    page(name: "assignDevicePage")
    page(name: "configureDevicePage")
    page(name: "reviewPage")
    page(name: "removeConfiguredDevicePage")
}

mappings {
    path("/event") { action: [GET: "apiEvent"] }
}

def installed() { initialize() }
def updated() { initialize() }

def initialize() {
    ensureAppUid()
    ensureAccessToken()

    if (state.configured == null) state.configured = [:]
    if (state.discovered == null) state.discovered = []

    if (state.rt == null) state.rt = [:]
    if (state.rt.lastSelectorClickMs == null) state.rt.lastSelectorClickMs = [:]

    ensureAutoNumbers()
    invalidateSetConfigCache()
    setupTwoWaySync()
}

def appButtonHandler(String btn) {
    if (!btn) return

    // Expected formats:
    //  add|<bdaddr>|<keyPrefix>|<which>
    //  rem|<bdaddr>|<keyPrefix>|<which>|<idx>
    def parts = btn.toString().split("\\|") as List
    if (!parts || parts.size() < 4) return

    String op = parts[0]
    String bdaddr = normBdaddr(parts[1])
    String keyPrefix = parts[2]
    String which = parts[3]

    if (!bdaddr || !keyPrefix || !which) return

    Integer n = getActionCount(bdaddr, keyPrefix, which)

    if (op == "add") {
        if (n < MAX_MULTI_ACTIONS) {
            setActionCount(bdaddr, keyPrefix, which, n + 1)
        }
        return
    }

    if (op == "rem") {
        Integer idx = (parts.size() >= 5) ? safeInt(parts[4], null) : null
        if (idx == null) return
        if (idx < 1 || idx > n) return

        // Clear settings for the removed row
        clearActionRowSettings(bdaddr, keyPrefix, which, idx)

        // If removing a middle row, shift later rows down so UI stays compact
        if (idx < n) {
            (idx..(n - 1)).each { Integer i ->
                Integer j = i + 1
                shiftActionRowSettingsDown(bdaddr, keyPrefix, which, j, i)
            }
            // clear the old last row after shifting
            clearActionRowSettings(bdaddr, keyPrefix, which, n)
        }

        // Decrease count (min 1)
        setActionCount(bdaddr, keyPrefix, which, Math.max(1, n - 1))
        return
    }
}

/**
 * Shift row "fromIdx" -> "toIdx" by copying settings values.
 */
private void shiftActionRowSettingsDown(String bdaddr, String keyPrefix, String which, Integer fromIdx, Integer toIdx) {
    if (!fromIdx || !toIdx) return
    if (fromIdx < 1 || toIdx < 1) return

    String fromS = "_a${fromIdx}"
    String toS   = "_a${toIdx}"

    String fromActionKey  = k(bdaddr, "${keyPrefix}_${which}_action${fromS}")
    String toActionKey    = k(bdaddr, "${keyPrefix}_${which}_action${toS}")

    String fromTargetsKey = k(bdaddr, "${keyPrefix}_${which}_targets${fromS}")
    String toTargetsKey   = k(bdaddr, "${keyPrefix}_${which}_targets${toS}")

    // Read FROM action BEFORE we do anything that might confuse tracking
    String fromAct = (settings[fromActionKey] ?: "none").toString()

    // 1) Copy action enum first
    copySetting(fromActionKey, "enum", toActionKey, "enum")

    // --- NEW: keep uiPrev aligned so renderActionRowIndexed() doesn't think user changed action ---
    uiPrevSetActForRow(bdaddr, keyPrefix, which, toIdx, fromAct)
    // ------------------------------------------------------------------------------------------

    // 2) Copy targets using capability type derived from the *from* row action
    String capType = targetsInputTypeForAction(fromAct)
    copyDeviceSetting(fromTargetsKey, toTargetsKey, capType)

    // 3) Detail fields
    copySetting(k(bdaddr, "${keyPrefix}_${which}_level${fromS}"), "number",
                k(bdaddr, "${keyPrefix}_${which}_level${toS}"), "number")

    copySetting(k(bdaddr, "${keyPrefix}_${which}_webcore_url${fromS}"), "text",
                k(bdaddr, "${keyPrefix}_${which}_webcore_url${toS}"), "text")

    copySetting(k(bdaddr, "${keyPrefix}_${which}_ct${fromS}"), "number",
                k(bdaddr, "${keyPrefix}_${which}_ct${toS}"), "number")

    copySetting(k(bdaddr, "${keyPrefix}_${which}_sat${fromS}"), "number",
                k(bdaddr, "${keyPrefix}_${which}_sat${toS}"), "number")

    copySetting(k(bdaddr, "${keyPrefix}_${which}_vol${fromS}"), "number",
                k(bdaddr, "${keyPrefix}_${which}_vol${toS}"), "number")

    copySetting(k(bdaddr, "${keyPrefix}_${which}_blind${fromS}"), "number",
                k(bdaddr, "${keyPrefix}_${which}_blind${toS}"), "number")

    copySetting(k(bdaddr, "${keyPrefix}_${which}_color${fromS}"), "color",
                k(bdaddr, "${keyPrefix}_${which}_color${toS}"), "color")
}


/**
 * Copy one setting key -> another (non-device types).
 */
private void copySetting(String fromKey, String fromType, String toKey, String toType) {
    try {
        def v = settings[fromKey]
        if (v == null || v.toString() == "") {
            clearSettingKey(toKey)
            return
        }
        app.updateSetting(toKey, [type: toType, value: v])
    } catch (ignored) { }
}


/* ---------------- Logging helpers ---------------- */

private void infoEvt(String msg) { log.info msg }
private void warnEvt(String msg) { log.warn msg }



/* ---------------- Utils ---------------- */

private String normBdaddr(def bd) { return (bd ?: "").toString().trim().toLowerCase() }

private Integer safeInt(def s, Integer defVal) {
    try { return (s as Integer) } catch (e) { return defVal }
}

private BigDecimal safeDec(def s, BigDecimal defVal) {
    try { return (s as BigDecimal) } catch (e) { return defVal }
}


private Integer clampInt(Integer v, Integer lo, Integer hi) {
    if (v == null) return lo
    if (v < lo) return lo
    if (v > hi) return hi
    return v
}

private BigDecimal clamp01(BigDecimal v) {
    if (v == null) return null
    if (v < 0) return 0
    if (v > 1) return 1
    return v
}

private Integer pctFrom01(BigDecimal v01, Integer fallbackPct) {
    if (v01 == null) return clampInt(fallbackPct, 0, 100)
    return clampInt(Math.round((v01 as BigDecimal) * 100.0G) as Integer, 0, 100)
}

private String bdKeyPrefix(String bdaddr) {
    String bd = normBdaddr(bdaddr)
    String cleaned = bd.replaceAll(/[^0-9a-f]/, "")
    return "d_${cleaned}"
}

private String k(String bdaddr, String suffix) { return "${bdKeyPrefix(bdaddr)}_${suffix}" }

private String humanType(def b) {
    String inferred = inferDeviceType(b, null)
    return inferred == "twist" ? "Twist" : (inferred == "button" ? "Button" : "Unknown")
}

private String defaultLabelTwist(def discovered, String suffix) {
    String baseName = (discovered?.name ?: "Flic Twist").toString()
    return "Flic Twist - ${baseName} - ${suffix}"
}

private String defaultLabelButton(def discovered, String suffix) {
    String baseName = (discovered?.name ?: "Flic Button").toString()
    return "Flic Button - ${baseName} - ${suffix}"
}

/* ---------------- UI change tracking + reset helpers ---------------- */

private Map uiPrev() {
    if (state.rt == null) state.rt = [:]
    if (!(state.rt.uiPrev instanceof Map)) state.rt.uiPrev = [:]
    return (state.rt.uiPrev as Map)
}

private String uiPrevGet(String bdaddr, String key, String defVal = null) {
    String bd = normBdaddr(bdaddr)
    Map all = uiPrev()
    Map per = (all[bd] instanceof Map) ? (all[bd] as Map) : [:]
    def v = per[key]
    return (v == null ? defVal : v.toString())
}

private void uiPrevSet(String bdaddr, String key, def value) {
    String bd = normBdaddr(bdaddr)
    Map all = uiPrev()
    if (!(all[bd] instanceof Map)) all[bd] = [:]
    (all[bd] as Map)[key] = (value == null ? null : value.toString())
    state.rt.uiPrev = all
}

/**
 * Returns true if value changed since last time this page rendered for this bdaddr+key.
 * Also stores the new value.
 */
private boolean uiChanged(String bdaddr, String key, def newVal) {
    String n = (newVal == null ? "" : newVal.toString())
    String prev = uiPrevGet(bdaddr, key, "")
    boolean changed = (prev != n)
    uiPrevSet(bdaddr, key, n)
    return changed
}

/** Keep uiPrev in-sync when we programmatically move action rows, so renderActionRowIndexed() doesn't auto-clear. */
private void uiPrevSetActForRow(String bdaddr, String keyPrefix, String which, Integer idx, String act) {
    String k0 = "act_${keyPrefix}_${which}_${idx}"
    uiPrevSet(bdaddr, k0, (act ?: "none").toString())
}

/** Clear uiPrev tracking for an action row index (useful when deleting rows). */
private void uiPrevClearActForRow(String bdaddr, String keyPrefix, String which, Integer idx) {
    String k0 = "act_${keyPrefix}_${which}_${idx}"
    uiPrevSet(bdaddr, k0, null)   // uiPrevGet() will treat as "no previous"
}

/** Safely clear a setting (device inputs, enum, number, text, bool). */
private void clearSettingKey(String key) {
    if (!key) return

    // Best: removeSetting actually deletes it so settings[key] becomes null (not "null")
    try {
        app.removeSetting(key)
        return
    } catch (ignored) { }

    // Fallback: set to empty string (avoid persisting literal "null")
    try {
        app.updateSetting(key, [type: "text", value: ""])
    } catch (ignored2) {
        try { app.updateSetting(key, "") } catch (ignored3) { }
    }
}


/**
 * If currentLabel equals oldDefault, replace it with newDefault.
 * (Do nothing if user has customized the label.)
 */
private void bumpDefaultLabelIfUnchanged(String labelKey, String currentLabel, String oldDefault, String newDefault) {
    if (!labelKey) return
    String cur = (currentLabel ?: "").toString()
    if (!cur) return
    if ((oldDefault ?: "").toString() && cur == oldDefault.toString()) {
        try { app.updateSetting(labelKey, [type: "text", value: newDefault.toString()]) } catch (ignored) {}
    }
}

/**
 * Handle function-change for a Twist "level" block:
 * - If virtual: rename label field if it still matches the old default label.
 * - If real: clear selected target devices (capability may no longer match).
 *
 * Params:
 *  - fnKey: the enum setting key holding the function ("brightness"/"ct"/etc)
 *  - modeKey: "virtual"/"real"
 *  - labelKey: text key for virtual label
 *  - targetsKey: device input key for real targets
 *  - oldFn/newFn: function strings
 *  - oldSuffix/newSuffix: suffix used to compute default label
 */
private void onTwistFnChangedMaybe(
    String bdaddr,
    def discovered,
    String fnKey,
    String modeKey,
    String labelKey,
    String targetsKey,
    String oldFn,
    String newFn,
    String oldSuffix,
    String newSuffix
) {
    String mode = (settings[modeKey] ?: "none").toString()

    if (mode == "virtual") {
        String curLabel = (settings[labelKey] ?: "").toString()

        String oldDefault = defaultLabelTwist(discovered, oldSuffix)
        String newDefault = defaultLabelTwist(discovered, newSuffix)

        bumpDefaultLabelIfUnchanged(labelKey, curLabel, oldDefault, newDefault)
    }

    if (mode == "real") {
        // clear currently selected devices; user must re-pick compatible ones
        clearSettingKey(targetsKey)
    }
}

/**
 * Full reset of Push & Twist section (secondary + all selectors) when pushMode changes.
 * Clears: fn enums, twist modes, twist vlabels, twist targets, clicks modes, clicks vlabels,
 * and (optionally) button action/targets/levels in selector sections.
 */
private void resetPushTwistSection(String bdaddr) {
    // Secondary sel=1
    clearSettingKey(k(bdaddr, "tw_s1_twist_mode"))
    clearSettingKey(k(bdaddr, "tw_s1_twist_vlabel"))
    clearSettingKey(k(bdaddr, "tw_s1_twist_targets"))

    // Selector Basic + Advanced superset: wipe 1..11 AND 3/6/9 keys
    (1..11).each { Integer sel ->
        clearSettingKey(k(bdaddr, "tw_s${sel}_fn"))
        clearSettingKey(k(bdaddr, "tw_s${sel}_twist_mode"))
        clearSettingKey(k(bdaddr, "tw_s${sel}_twist_vlabel"))
        clearSettingKey(k(bdaddr, "tw_s${sel}_twist_targets"))

        clearSettingKey(k(bdaddr, "tw_s${sel}_clicks_mode"))
        clearSettingKey(k(bdaddr, "tw_s${sel}_clicks_vlabel"))

        // If selector buttons were in "real" mode and had actions configured, clear those too
        ["sc","dc","hd","rel"].each { which ->
            clearSettingKey(k(bdaddr, "tw_s${sel}_${which}_action"))
            clearSettingKey(k(bdaddr, "tw_s${sel}_${which}_targets"))
            clearSettingKey(k(bdaddr, "tw_s${sel}_${which}_level"))
        }
    }
}

private void invalidateSetConfigCache() {
    if (state?.rt == null) state.rt = [:]
    state.rt.lastSetConfigResult = null
    state.rt.lastSetConfigHash = null
}

/**
 * Determine the correct device-input type for a given action row,
 * matching what renderActionRowIndexed() uses for targetsKey.
 */
private String targetsInputTypeForAction(String act) {
    String a = (act ?: "none").toString()

    if (a == "setLevel")            return "capability.switchLevel"
    if (a == "lock" || a == "unlock") return "capability.lock"
    if (a == "setColor")            return "capability.colorControl"
    if (a == "setColorTemperature") return "capability.colorTemperature"
    if (a == "setSaturation")       return "capability.colorControl"
    if (a == "setVolume")           return "capability.audioVolume"
    if (a == "blindsSetLevel")      return "capability.windowShade"

    // webcore has no targets
    if (a == "webcore")             return null

    // toggle/on/off (and any other non-none) uses capability.switch
    if (a != "none")                return "capability.switch"

    return null
}

/**
 * Copy a device input (single or multiple) from one setting key to another.
 * Requires the correct "type" string (capability.*).
 *
 * IMPORTANT:
 * settings[fromKey] returns DeviceWrapper or List<DeviceWrapper].
 * app.updateSetting() expects device IDs (String or List<String>) for device inputs.
 */
private void copyDeviceSetting(String fromKey, String toKey, String capType) {
    if (!fromKey || !toKey) return

    // no targets for this action (or none selected)
    if (!capType) {
        clearSettingKey(toKey)
        return
    }

    try {
        def v = settings[fromKey]
        if (v == null) {
            clearSettingKey(toKey)
            return
        }

        // Convert DeviceWrapper(s) -> device id string(s)
        def ids = null

        if (v instanceof List) {
            List<String> out = []
            v.each { d ->
                try {
                    if (d?.id != null) out << d.id.toString()
                } catch (ignored) { }
            }
            ids = out
        } else {
            try {
                if (v?.id != null) ids = v.id.toString()
            } catch (ignored) { }
        }

        // If conversion failed or produced empty list, clear the destination
        if (ids == null || (ids instanceof List && (ids as List).isEmpty())) {
            clearSettingKey(toKey)
            return
        }

        // ⭐ CRITICAL FIX:
        // Hubitat can silently fail if a device-input key previously existed with a different capability type.
        // Remove it first so it can be recreated with the new type.
        try { app.removeSetting(toKey) } catch (ignored0) { }

        app.updateSetting(toKey, [type: capType, value: ids])

    } catch (ignored) {
        // Fail safe: don't blow away other fields if something goes sideways
        try { clearSettingKey(toKey) } catch (ignored2) {}
    }
}


private void renderDivider() {
    // Bold horizontal line between sections (kept inside current section to avoid reorder quirks)
    paragraph "<hr style='border:0;border-top:3px solid #000;margin:14px 0;'/>"
}

/* ---------------- Auto-label detection + mode-change helpers ---------------- */

private boolean isKnownTwistFnLabel(String s) {
    if (!s) return false
    String x = s.toString()
    return (TW_FUNCS?.values()?.collect { it.toString() }?.contains(x))
}

private boolean looksAutoTwistLabel(def discovered, String label, String suffixPrefix) {
    if (!label) return false
    String baseName = (discovered?.name ?: "Flic Twist").toString()
    String expectedPrefix = "Flic Twist - ${baseName} - ${suffixPrefix}"
    if (!label.toString().startsWith(expectedPrefix)) return false

    // After the prefix should be a known function label (Brightness/Colour/etc) or "Function"
    String tail = label.toString().substring(expectedPrefix.length())
    if (!tail) return false
    if (tail.startsWith(" ")) tail = tail.substring(1)
    return (tail == "Function" || isKnownTwistFnLabel(tail))
}

/**
 * If user toggles Real -> Virtual, refresh auto-label to the current default for the current fn.
 * Preserves custom labels (anything not matching our default pattern).
 *
 * suffixPrefix examples:
 *  - "Master "
 *  - "Push & Twist "
 *  - "3 o'clock "
 */
private void refreshAutoLabelOnModeToVirtual(def discovered, String modeKey, String labelKey, String suffixPrefix, String newSuffixFull) {
    String mode = (settings[modeKey] ?: "none").toString()
    if (mode != "virtual") return

    String cur = (settings[labelKey] ?: "").toString()
    if (!cur || looksAutoTwistLabel(discovered, cur, suffixPrefix)) {
        String newDefault = defaultLabelTwist(discovered, newSuffixFull)
        try { app.updateSetting(labelKey, [type: "text", value: newDefault.toString()]) } catch (ignored) {}
    }
}


/* ---------------- UI ---------------- */

def mainPage() {
    dynamicPage(name: "mainPage", title: "", install: true, uninstall: true) {
        section("<b>Flic Hub:</b>") {
            input "flicHost", "text", title: "Flic Hub IP", required: true
            input "flicPort", "number", title: "TCP Port (default is usually 46321)", required: true, defaultValue: 46321
        }

        section("<b>Device Discovery:</b>") {
            href name: "goDiscover",
                page: "discoverPage",
                title: "Configure Flic devices",
                description: "Click here to scan for and configure devices"
        }

        section("<b>Configured Devices:</b>") {
            def cfg = (state.configured ?: [:])
            if (!cfg || cfg.size() == 0) {
                paragraph "None configured yet"
            } else {
                cfg.values().sort { (it.name ?: "").toLowerCase() }.each { c ->
                    paragraph "• ${c.name} (${c.type})"
                }
            }
        }

        section("<b>Finalise Configuration:</b>") {
            href name: "goReview",
                page: "reviewPage",
                title: "Push configuration to Flic hub",
                description: "Click here to finalise"
        }
    }
}

def discoverPage() {
    dynamicPage(name: "discoverPage", title: "", nextPage: "assignDevicePage") {
        try {
            doDiscovery()
        } catch (e) {
            section("") { paragraph "Discovery error: ${e}" }
        }

        def list = (state.discovered ?: [])
        section("") {
            if (!list || list.size() == 0) {
                paragraph "<b>No devices found</b>"
            } else {
                paragraph "<b>${list.size()} Devices discovered on Flic hub:</b>"
                list.each { b ->
                    paragraph "• ${b.name} (${humanType(b)})"
                }
                paragraph "<b>\nClick next to start configuration</b>"
            }
        }
    }
}

def assignDevicePage() {
    def list = (state.discovered ?: [])
    dynamicPage(name: "assignDevicePage", title: "Pick a Flic device to configure") {

        if (!list || list.size() == 0) {
            section("") { paragraph "No devices discovered. Go back and run discovery again." }
            return
        }

        Map opts = [:]
        list.each { b ->
            String bd = normBdaddr(b.bdaddr)
            if (bd) opts[bd] = "${b.name} (${humanType(b)})"
        }

        section("") {
            input "selectedBdaddr", "enum",
                title: " ",
                required: false,
                multiple: false,
                options: opts,
                submitOnChange: true

            if (settings.selectedBdaddr) {
                href name: "goConfigure",
                    page: "configureDevicePage",
                    title: "Configure selected device",
                    description: ""
            }

            href name: "goMain",
                page: "mainPage",
                title: "Back",
                description: ""
        }
    }
}

def configureDevicePage() {
    def list = (state.discovered ?: [])
    String bdaddr = normBdaddr(settings.selectedBdaddr)
    def b = list?.find { normBdaddr(it.bdaddr) == bdaddr }

    dynamicPage(name: "configureDevicePage", title: "<b>Configure device</b>") {
        if (!bdaddr || !b) {
            section("") { paragraph "<b>No device selected... Go back</b>" }
            return
        }

        def existing = (state.configured ?: [:])[bdaddr]
        String inferredType = inferDeviceType(b, existing)

        ensureConfiguredShell(bdaddr, b, inferredType)

        section("") {
            paragraph "Device Name: ${b.name}\nDevice Type: ${inferredType}"

            state.rt = (state.rt ?: [:])
            state.rt.removeBdaddr = bdaddr

            href name: "goRemoveDevice",
                page: "removeConfiguredDevicePage",
                title: "Remove this device from configuration",
                description: ""
        }

        if (inferredType == "button") renderButtonConfigSection(bdaddr, b)
        else renderTwistConfigSection(bdaddr, b)

        saveConfiguredDevice(bdaddr, b, inferredType)
        renderFlicAppInstructions(bdaddr)

        section("") {
            href name: "backToList", page: "assignDevicePage", title: "Save and configure another device", description: ""
            href name: "backToMain", page: "mainPage", title: "Save and return to Main Menu", description: ""
        }
    }
}

def removeConfiguredDevicePage() {
    String bdaddr = normBdaddr(state?.rt?.removeBdaddr)
    def cfg = (state.configured ?: [:])
    def entry = bdaddr ? cfg[bdaddr] : null

    dynamicPage(name: "removeConfiguredDevicePage", title: "<b>Remove device</b>", install: false, uninstall: false) {
        if (!bdaddr || !entry) {
            section("") {
                paragraph "Device not found (already removed?)."
                href name: "backMain", page: "mainPage", title: "Back", description: ""
            }
            return
        }

        String confirmKey = k(bdaddr, "confirm_remove")

        section("<b>Please Confirm</b>") {
            paragraph """You are about to remove: ${entry?.name} (${entry?.type})
			BDADDR: ${bdaddr}
			Device Number: flic${entry?.flicNum}"""

            input confirmKey, "bool", title: "<b>Yes, remove this device</b>", defaultValue: false, submitOnChange: true

            if (settings[confirmKey] == true) {
                removeConfiguredDevice(bdaddr)
                paragraph "<b>Removed</b>"
                try { app.updateSetting(confirmKey, [type: "bool", value: "false"]) } catch (ignored) {}
                try { state.rt.removeBdaddr = null } catch (ignored) {}
                href name: "backMain2", page: "mainPage", title: "Back to main", description: ""
            }
        }
    }
}


/* ---------------- Config UI builders ---------------- */

def renderButtonConfigSection(String bdaddr, def discovered) {

    String clicksModeKey = k(bdaddr, "b_clicks_mode")
    String clicksVLabelKey = k(bdaddr, "b_clicks_vlabel")

    section("Button actions") {
        input clicksModeKey, "enum",
            title: "Control a virtual or HE device?",
            required: false,
            options: ["virtual":"Create a virtual button device", "real":"HE device(s)"],
            defaultValue: (settings[clicksModeKey]),
            submitOnChange: true

        if (settings[clicksModeKey] == "virtual") {
            input clicksVLabelKey, "text",
                title: "Virtual device name",
                required: false,
                defaultValue: (settings[clicksVLabelKey] ?: defaultLabelButton(discovered, "Button")),
                submitOnChange: true
        } else if (settings[clicksModeKey] == "real") {
            // Button-only devices support click / double / hold / release
            renderRealSwitchEventsBlock(bdaddr, "b", "Button", true, true)
        }
    }
}

private void renderTwistTargetPicker(String fn, String targetsKey, String titleOverride = null) {
    String t = titleOverride ?: "HE device(s)"
    if (fn == "brightness") {
        input targetsKey, "capability.switchLevel", title: (titleOverride ?: "Dimmer(s)"), multiple: true, required: false
    } else if (fn == "ct") {
        input targetsKey, "capability.colorTemperature", title: (titleOverride ?: "Colour temperature device(s)"), multiple: true, required: false
    } else if (fn == "color" || fn == "sat") {
        input targetsKey, "capability.colorControl", title: (titleOverride ?: "Colour control device(s)"), multiple: true, required: false
    } else if (fn == "vol") {
        input targetsKey, "capability.audioVolume", title: (titleOverride ?: "Volume device(s)"), multiple: true, required: false
    } else if (fn == "blinds") {
        input targetsKey, "capability.windowShade", title: (titleOverride ?: "Shade(s) / Blind(s)"), multiple: true, required: false
    } else {
        input targetsKey, "capability.switchLevel", title: t, multiple: true, required: false
    }
}

def renderTwistConfigSection(String bdaddr, def discovered) {

    String masterFnKey  = k(bdaddr, "tw_master_fn")
    String usePushKey   = k(bdaddr, "tw_use_push_twist")
    String pushModeKey  = k(bdaddr, "tw_push_mode")

    section("<b>Twist options:</b>") {

        input usePushKey, "bool",
            title: "Use Push & Twist",
            defaultValue: (settings[usePushKey] == null ? false : settings[usePushKey]),
            submitOnChange: true

        if (settings[usePushKey] == true) {
            input pushModeKey, "enum",
                title: "Push & Twist mode",
                required: false,
                options: TW_PUSH_MODES,
                defaultValue: (settings[pushModeKey] ?: "sel_adv"),
                submitOnChange: true
        }
    }

    boolean usePushTwist = (settings[usePushKey] == true)
    String pushMode = usePushTwist ? (settings[pushModeKey] ?: "sel_adv").toString() : "off"

    // --- Detect Push & Twist toggle/mode changes and reset whole section ---
    boolean prevUsePush = (uiPrevGet(bdaddr, "usePushTwist", "false") == "true")
    String prevPushMode = uiPrevGet(bdaddr, "pushMode", "")

    uiPrevSet(bdaddr, "usePushTwist", (usePushTwist ? "true" : "false"))
    uiPrevSet(bdaddr, "pushMode", (usePushTwist ? pushMode : ""))

    boolean pushTwistSectionChanged =
        (prevUsePush != usePushTwist) ||
        (usePushTwist && prevUsePush && (prevPushMode != pushMode))

    if (pushTwistSectionChanged) {
        resetPushTwistSection(bdaddr)
    }

    // CRITICAL:
    // On the same render cycle where we reset, Hubitat's `settings[...]` can still show old values.
    // So for Push & Twist fields, ignore settings for the remainder of this render.
    boolean ignorePushTwistSettingsThisRender = pushTwistSectionChanged

    boolean includeSelectors = (usePushTwist && (pushMode == "sel_basic" || pushMode == "sel_adv"))
    List<Integer> sels = includeSelectors ? ((pushMode == "sel_basic") ? [3, 6, 9] : (1..11).toList()) : []

    String masterFn = (settings[masterFnKey] ?: "none").toString()
    String masterFnLabel = (masterFn != 'none' ? (TW_FUNCS[masterFn] ?: 'Brightness') : 'Function').toString()

    // Master keys
    String mTwistModeKey    = k(bdaddr, "tw_m_twist_mode")
    String mTwistVLabelKey  = k(bdaddr, "tw_m_twist_vlabel")
    String mTwistTargetsKey = k(bdaddr, "tw_m_twist_targets")

    String mClicksModeKey   = k(bdaddr, "tw_m_clicks_mode")
    String mClicksVLabelKey = k(bdaddr, "tw_m_clicks_vlabel")

    // --- Master function change handling ---
    String prevMasterFn = uiPrevGet(bdaddr, "masterFn", "")
    uiPrevSet(bdaddr, "masterFn", masterFn)

    if (prevMasterFn && prevMasterFn != masterFn) {
        String oldLabel = (prevMasterFn != 'none' ? (TW_FUNCS[prevMasterFn] ?: prevMasterFn) : 'Function').toString()
        String newLabel = masterFnLabel

        String oldSuffix = "Master ${oldLabel}"
        String newSuffix = "Master ${newLabel}"

        onTwistFnChangedMaybe(
            bdaddr,
            discovered,
            masterFnKey,
            mTwistModeKey,
            mTwistVLabelKey,
            mTwistTargetsKey,
            prevMasterFn,
            masterFn,
            oldSuffix,
            newSuffix
        )
    }

    // --- Master twist mode change handling (Real <-> Virtual) ---
    // Fixes: label can get "stuck" if fn changes while in REAL mode, then user returns to VIRTUAL.
    String masterTwistModeNow = (settings[mTwistModeKey] ?: "none").toString()
    boolean masterTwistModeChanged = uiChanged(bdaddr, "masterTwistMode", masterTwistModeNow)
    if (masterTwistModeChanged && masterTwistModeNow == "virtual") {
        refreshAutoLabelOnModeToVirtual(
            discovered,
            mTwistModeKey,
            mTwistVLabelKey,
            "Master ",
            "Master ${masterFnLabel}"
        )
    }

    section("<b>Master:</b>") {

        input masterFnKey, "enum",
            title: "Master twist function (as selected in the Flic mobile app)",
            required: false,
            options: TW_FUNCS,
            defaultValue: (settings[masterFnKey]),
            submitOnChange: true

        input mTwistModeKey, "enum",
            title: "Master twist - Control a virtual or HE device?",
            required: false,
            options: ["virtual":"Create a virtual device", "real":"Select HE device(s)"],
            defaultValue: (settings[mTwistModeKey]),
            submitOnChange: true

        if (settings[mTwistModeKey] == "virtual") {
            input mTwistVLabelKey, "text",
                title: "Virtual device name",
                required: false,
                defaultValue: (settings[mTwistVLabelKey] ?: defaultLabelTwist(discovered, "Master ${masterFnLabel}")),
                submitOnChange: true
        } else if (settings[mTwistModeKey] == "real") {
            renderTwistTargetPicker(masterFn, mTwistTargetsKey)
        }

        input mClicksModeKey, "enum",
            title: "Master button - Control a virtual or HE device?",
            required: false,
            options: ["virtual":"Create a virtual device", "real":"Select HE device(s)"],
            defaultValue: (settings[mClicksModeKey]),
            submitOnChange: true

        if (settings[mClicksModeKey] == "virtual") {
            input mClicksVLabelKey, "text",
                title: "Virtual device name",
                required: false,
                defaultValue: (settings[mClicksVLabelKey] ?: defaultLabelTwist(discovered, "Master Button")),
                submitOnChange: true
        } else if (settings[mClicksModeKey] == "real") {
            renderRealSwitchEventsBlock(bdaddr, "tw_m", "Master Button", (usePushTwist == false), (usePushTwist == false))
        }
        renderDivider()
    }

    // ---------- PUSH & TWIST (secondary sel=1) ----------
    // ONLY when pushMode is one of the secondary funcs.
    if (usePushTwist && (pushMode in ["brightness", "ct", "color", "sat", "vol", "blinds"])) {

        String secFn = pushMode
        String secLabel = (TW_FUNCS[secFn] ?: secFn).toString()

        String sTwistModeKey    = k(bdaddr, "tw_s1_twist_mode")
        String sTwistVLabelKey  = k(bdaddr, "tw_s1_twist_vlabel")
        String sTwistTargetsKey = k(bdaddr, "tw_s1_twist_targets")

        // IMPORTANT: ignore stale settings on the render where pushMode changed
        def sModeVal    = ignorePushTwistSettingsThisRender ? null : settings[sTwistModeKey]
        def sLabelVal   = ignorePushTwistSettingsThisRender ? null : settings[sTwistVLabelKey]
        def sTargetsVal = ignorePushTwistSettingsThisRender ? null : settings[sTwistTargetsKey] // (not used directly)

        // --- Secondary twist mode change handling (Real <-> Virtual) ---
        // Fixes: label can get "stuck" if pushMode changes while in REAL mode, then user returns to VIRTUAL.
        String secModeNow = (settings[sTwistModeKey] ?: "none").toString()
        boolean secModeChanged = uiChanged(bdaddr, "secTwistMode", secModeNow)
        if (secModeChanged && secModeNow == "virtual") {
            refreshAutoLabelOnModeToVirtual(
                discovered,
                sTwistModeKey,
                sTwistVLabelKey,
                "Push & Twist ",
                "Push & Twist ${secLabel}"
            )
        }

        section("<b>Push & Twist:</b>") {
            paragraph "Secondary control: ${secLabel}"

            input sTwistModeKey, "enum",
                title: "${secLabel} Device - Control a virtual or HE device?",
                required: false,
                options: ["virtual":"Create a virtual device", "real":"Select HE device(s)"],
                defaultValue: (sModeVal),
                submitOnChange: true

            if ((ignorePushTwistSettingsThisRender ? (sModeVal) : settings[sTwistModeKey]) == "virtual") {
                input sTwistVLabelKey, "text",
                    title: "Virtual device name",
                    required: false,
                    defaultValue: (sLabelVal ?: defaultLabelTwist(discovered, "Push & Twist ${secLabel}")),
                    submitOnChange: true
            } else if ((ignorePushTwistSettingsThisRender ? (sModeVal) : settings[sTwistModeKey]) == "real") {
                renderTwistTargetPicker(secFn, sTwistTargetsKey)
            }
            renderDivider()
        }
    }

    // ---------- SELECTORS ----------
    // (selector fn changes already handled per-o'clock; still benefit from ignore flag on mode change)
    if (includeSelectors) {
        sels.each { sel ->

            String sFnKey           = k(bdaddr, "tw_s${sel}_fn")
            String sTwistModeKey    = k(bdaddr, "tw_s${sel}_twist_mode")
            String sTwistVLabelKey  = k(bdaddr, "tw_s${sel}_twist_vlabel")
            String sTwistTargetsKey = k(bdaddr, "tw_s${sel}_twist_targets")

            String sClicksModeKey   = k(bdaddr, "tw_s${sel}_clicks_mode")
            String sClicksVLabelKey = k(bdaddr, "tw_s${sel}_clicks_vlabel")

            // Ignore stale values on the render where pushMode/toggle changed
            def fnVal         = ignorePushTwistSettingsThisRender ? null : settings[sFnKey]
            def twistModeVal  = ignorePushTwistSettingsThisRender ? null : settings[sTwistModeKey]
            def twistLblVal   = ignorePushTwistSettingsThisRender ? null : settings[sTwistVLabelKey]
            def clicksModeVal = ignorePushTwistSettingsThisRender ? null : settings[sClicksModeKey]
            def clicksLblVal  = ignorePushTwistSettingsThisRender ? null : settings[sClicksVLabelKey]

            String fn = ((fnVal ?: "none").toString())
            String fnLabel = (fn != 'none' ? (TW_FUNCS[fn] ?: fn) : 'Function').toString()

            // --- Selector function change handling (per o'clock) ---
            String trackKey = "selFn_${sel}"
            String prevFn = uiPrevGet(bdaddr, trackKey, "")
            uiPrevSet(bdaddr, trackKey, fn)

            if (prevFn && prevFn != fn) {
                String oldLabel = (prevFn != 'none' ? (TW_FUNCS[prevFn] ?: prevFn) : 'Function').toString()
                String newLabel = fnLabel

                String oldSuffix = "${sel} o'clock ${oldLabel}"
                String newSuffix = "${sel} o'clock ${newLabel}"

                onTwistFnChangedMaybe(
                    bdaddr,
                    discovered,
                    sFnKey,
                    sTwistModeKey,
                    sTwistVLabelKey,
                    sTwistTargetsKey,
                    prevFn,
                    fn,
                    oldSuffix,
                    newSuffix
                )
            }

            // --- Selector twist mode change handling (Real <-> Virtual) ---
            String selModeNow = (settings[sTwistModeKey] ?: "none").toString()
            boolean selModeChanged = uiChanged(bdaddr, "selTwistMode_${sel}", selModeNow)
            if (selModeChanged && selModeNow == "virtual") {
                refreshAutoLabelOnModeToVirtual(
                    discovered,
                    sTwistModeKey,
                    sTwistVLabelKey,
                    "${sel} o'clock ",
                    "${sel} o'clock ${fnLabel}"
                )
            }

            section("<b>${sel} o\'clock:</b>") {

                input sFnKey, "enum",
                    title: "${sel} o'clock twist function (as selected in the Flic mobile app)",
                    required: false,
                    options: TW_FUNCS,
                    defaultValue: (fnVal),
                    submitOnChange: true

                input sTwistModeKey, "enum",
                    title: "${sel} o'clock ${fnLabel} - Control a virtual or HE device?",
                    required: false,
                    options: ["virtual":"Create a virtual device", "real":"Select HE device(s)"],
                    defaultValue: (twistModeVal),
                    submitOnChange: true

                if ((ignorePushTwistSettingsThisRender ? twistModeVal : settings[sTwistModeKey]) == "virtual") {
                    input sTwistVLabelKey, "text",
                        title: "Virtual device name",
                        required: false,
                        defaultValue: (twistLblVal ?: defaultLabelTwist(discovered, "${sel} o'clock ${fnLabel}")),
                        submitOnChange: true
                } else if ((ignorePushTwistSettingsThisRender ? twistModeVal : settings[sTwistModeKey]) == "real") {
                    renderTwistTargetPicker(fn, sTwistTargetsKey, "HE ${fnLabel} device(s)")
                }

                input sClicksModeKey, "enum",
                    title: "${sel} o'clock Button - Control a virtual or HE device?",
                    required: false,
                    options: ["virtual":"Create a virtual device", "real":"Select HE device(s)"],
                    defaultValue: (clicksModeVal),
                    submitOnChange: true

                if ((ignorePushTwistSettingsThisRender ? clicksModeVal : settings[sClicksModeKey]) == "virtual") {
                    input sClicksVLabelKey, "text",
                        title: "Virtual device name",
                        required: false,
                        defaultValue: (clicksLblVal ?: defaultLabelTwist(discovered, "${sel} o'clock Button")),
                        submitOnChange: true
                } else if ((ignorePushTwistSettingsThisRender ? clicksModeVal : settings[sClicksModeKey]) == "real") {
                    renderRealSwitchEventsBlock(bdaddr, "tw_s${sel}", "${sel} o'clock Button", false, false)
                }
                renderDivider()
            }
        }
    }
}


def renderRealSwitchEventsBlock(String bdaddr, String keyPrefix, String labelPrefix, boolean includeHold, boolean includeRelease) {

    renderActionRow(bdaddr, keyPrefix, labelPrefix, "sc", "Push")
    renderActionRow(bdaddr, keyPrefix, labelPrefix, "dc", "Double Push")

    if (includeHold) {
        renderActionRow(bdaddr, keyPrefix, labelPrefix, "hd", "Hold")
    }

    if (includeRelease) {
        renderActionRow(bdaddr, keyPrefix, labelPrefix, "rel", "Release")
    }
}

private void renderActionRow(String bdaddr, String keyPrefix, String labelPrefix, String which, String whichLabel) {
    Integer n = getActionCount(bdaddr, keyPrefix, which)

    // Inline (NO section wrapper) to prevent Hubitat re-ordering these blocks
    paragraph "<b>${labelPrefix} — ${whichLabel}</b>"

    // Render N actions
    (1..n).each { Integer idx ->
        renderActionRowIndexed(bdaddr, keyPrefix, labelPrefix, which, whichLabel, idx, n)
    }

    // Add another action button (up to MAX_MULTI_ACTIONS)
    if (n < MAX_MULTI_ACTIONS) {
        String btnName = "add|${normBdaddr(bdaddr)}|${keyPrefix}|${which}"
        input btnName, "button",
            title: "➕ Add another action",
            submitOnChange: true
    } else {
        paragraph "Max actions reached (${MAX_MULTI_ACTIONS})."
    }

    // Small spacing between event blocks
    paragraph ""
}

private void renderActionRowIndexed(
    String bdaddr,
    String keyPrefix,
    String labelPrefix,
    String which,
    String whichLabel,
    Integer idx,
    Integer total
) {
    String suf = "_a${idx}"

    String actionKey     = k(bdaddr, "${keyPrefix}_${which}_action${suf}")
    String targetsKey    = k(bdaddr, "${keyPrefix}_${which}_targets${suf}")

    String levelKey      = k(bdaddr, "${keyPrefix}_${which}_level${suf}")
    String webcoreUrlKey = k(bdaddr, "${keyPrefix}_${which}_webcore_url${suf}")

    String ctKey         = k(bdaddr, "${keyPrefix}_${which}_ct${suf}")
    String satKey        = k(bdaddr, "${keyPrefix}_${which}_sat${suf}")
    String volKey        = k(bdaddr, "${keyPrefix}_${which}_vol${suf}")
    String blindKey      = k(bdaddr, "${keyPrefix}_${which}_blind${suf}")
    String colorKey      = k(bdaddr, "${keyPrefix}_${which}_color${suf}")

    // Row header + optional remove button (inline)
    paragraph "<b>Action ${idx}</b>"

    if (total > 1) {
        String remBtn = "rem|${normBdaddr(bdaddr)}|${keyPrefix}|${which}|${idx}"
        input remBtn, "button",
            title: "➖ Remove action ${idx}",
            submitOnChange: true
    }

    input actionKey, "enum",
        title: " ",
        required: false,
        options: [
            "toggle"             : "Toggle",
            "on"                 : "On",
            "off"                : "Off",
            "setLevel"           : "Set Level",
            "lock"               : "Lock",
            "unlock"             : "Unlock",
            "setColor"           : "Set Color",
            "setColorTemperature": "Set Color Temperature",
            "setSaturation"      : "Set Saturation",
            "setVolume"          : "Set Volume",
            "blindsSetLevel"     : "Blinds Set Level",
            "webcore"            : "Execute a webCoRE Piston / HTTP Get"
        ],
        defaultValue: (settings[actionKey]),
        submitOnChange: true

    String act = (settings[actionKey] ?: "none").toString()

    // If action changed, clear fields that no longer apply
    String trackKey = "act_${keyPrefix}_${which}_${idx}"
    String prevAct = uiPrevGet(bdaddr, trackKey, "")
    uiPrevSet(bdaddr, trackKey, act)

    if (prevAct && prevAct != act) {
        clearSettingKey(levelKey)
        clearSettingKey(webcoreUrlKey)
        clearSettingKey(ctKey)
        clearSettingKey(satKey)
        clearSettingKey(volKey)
        clearSettingKey(blindKey)
        clearSettingKey(colorKey)
        clearSettingKey(targetsKey)
    }

    // ---- Per-action UI ----
    if (act == "webcore") {
        input webcoreUrlKey, "text",
            title: "webCoRE External URL (HTTP GET)",
            required: false,
            submitOnChange: true
        return
    }

    if (act == "setLevel") {
        input targetsKey, "capability.switchLevel", title: "Device(s)", multiple: true, required: false
        input levelKey, "number",
            title: "Level (1–100)",
            required: true,
            defaultValue: (settings[levelKey] != null ? settings[levelKey] : 100),
            range: "1..100",
            submitOnChange: true
        return
    }

    if (act == "lock" || act == "unlock") {
        input targetsKey, "capability.lock", title: "Lock(s)", multiple: true, required: false
        return
    }

    if (act == "setColor") {
        input targetsKey, "capability.colorControl", title: "Colour device(s)", multiple: true, required: false
        input colorKey, "color",
            title: "Pick a colour",
            required: true,
            submitOnChange: true
        return
    }

    if (act == "setColorTemperature") {
        input targetsKey, "capability.colorTemperature", title: "Colour temperature device(s)", multiple: true, required: false
        input ctKey, "number",
            title: "Colour temperature (Kelvin — typically 2000–6500)",
            required: true,
            defaultValue: (settings[ctKey] != null ? settings[ctKey] : 3000),
            range: "2000..6500",
            submitOnChange: true
        return
    }

    if (act == "setSaturation") {
        input targetsKey, "capability.colorControl", title: "Colour device(s)", multiple: true, required: false
        input satKey, "number",
            title: "Saturation (0–100)",
            required: true,
            defaultValue: (settings[satKey] != null ? settings[satKey] : 100),
            range: "0..100",
            submitOnChange: true
        return
    }

    if (act == "setVolume") {
        input targetsKey, "capability.audioVolume", title: "Volume device(s)", multiple: true, required: false
        input volKey, "number",
            title: "Volume (0–100)",
            required: true,
            defaultValue: (settings[volKey] != null ? settings[volKey] : 50),
            range: "0..100",
            submitOnChange: true
        return
    }

    if (act == "blindsSetLevel") {
        input targetsKey, "capability.windowShade", title: "Shade(s) / Blind(s)", multiple: true, required: false
        input blindKey, "number",
            title: "Position (0–100)",
            required: true,
            defaultValue: (settings[blindKey] != null ? settings[blindKey] : 50),
            range: "0..100",
            submitOnChange: true
        return
    }

    if (act != "none") {
        input targetsKey, "capability.switch", title: "Device(s)", multiple: true, required: false
        return
    }
}


private void renderFlicAppInstructions(String bdaddr) {
    def cfg = (state.configured ?: [:])[normBdaddr(bdaddr)]
    if (!cfg) return

    Integer flicNum = null
    try { flicNum = (cfg?.flicNum as Integer) } catch (ignored) { flicNum = null }

    // Keys + uniqueness checks
    String flicNumKey = k(bdaddr, "flic_num")
    Set<Integer> usedNums = usedFlicNums(bdaddr)

    // What the user is currently trying to set (or current stored number)
    Integer desiredNum = safeInt(settings[flicNumKey], flicNum)

    String cfgType = (cfg?.type ?: "").toString()

    section("<b>📱 Flic Mobile App Setup</b>") {

        if (flicNum != null) {
            paragraph "<b>Unique Device Number:</b> flic${flicNum}"
        }

        input flicNumKey, "number",
            title: "Edit Device Number (unique number used to configure actions in the Flic mobile app):",
            required: true,
            defaultValue: (settings[flicNumKey] != null ? settings[flicNumKey] : flicNum),
            submitOnChange: true

        if (desiredNum != null && desiredNum > 0 && usedNums.contains(desiredNum)) {
            paragraph "⚠ Number flic${desiredNum} is already in use by another configured device. Choose a different number."
        }

        // ✅ Buttons: explicitly tell user Mobile App config is not required
        if (cfgType == "button") {
            paragraph "<b>Configuration in the Flic Mobile App is not required for Flic Buttons.</b><br>This section only applies to <b>Flic Twist</b>."
            return
        }

        // Twist instructions content (existing behaviour)
        Map mappings = (cfg?.mappings ?: [:]) as Map
        if (flicNum && mappings && mappings.size() > 0) {
            paragraph buildFlicInstructionsText(flicNum, mappings)
        } else {
            paragraph "Configure the options above, then your Flic Mobile App setup instructions will appear here."
        }
    }
}

private String buildFlicInstructionsText(Integer flicNum, Map mappings) {
    List<String> lines = []

    lines << "Now add virtual devices and configure the following in the <b>Flic Mobile App</b>:"
    lines << ""

    // ---------- MASTER ----------
    def mTw = mappings?.master?.twist
    if (mTw && (mTw.mode in ["real","virtual"]) && mTw.fn && mTw.fn != "none") {
        def fn = mTw.fn
        lines << bullet("Twist = ${TW_FUNCS[fn]} → Flic Hub Studio → Virtual Device: <b>flic${flicNum}-${vdKind(fn)}0</b>")
    }

    addClickLines(lines, flicNum, 0, mappings?.master?.clicks)

    // ---------- PUSH & TWIST (secondary) ----------
    def sTw = mappings?.secondary?.twist
    if (sTw && (sTw.mode in ["real","virtual"]) && sTw.fn && sTw.fn != "none") {
        def fn = sTw.fn
        lines << ""
        lines << bullet("Push & Twist = ${TW_FUNCS[fn]} → Flic Hub Studio → Virtual Device: <b>flic${flicNum}-${vdKind(fn)}1</b>")
    }

    // NOTE: Secondary has no button clicks by design (sel=1 is reserved for secondary twist updates)

    // ---------- SELECTORS ----------
    Map selectors = mappings?.selectors ?: [:]
    if (selectors && selectors.size() > 0) {
        lines << ""
        lines << "<b>Push & Twist = Selector (Advanced)</b>:"

        selectors.keySet().sort { it as Integer }.each { selStr ->
            Integer sel = selStr as Integer
            def block = selectors[selStr]

            lines << "&nbsp;&nbsp;<b>${sel} o'clock:</b>"

            def tw = block?.twist
            if (tw && (tw.mode in ["real","virtual"]) && tw.fn && tw.fn != "none") {
                def fn = tw.fn
                lines << "&nbsp;&nbsp;&nbsp;• Twist = ${TW_FUNCS[fn]} → Flic Hub Studio → Virtual Device: <b>flic${flicNum}-${vdKind(fn)}${sel}</b>"
            }

            addClickLines(lines, flicNum, sel, block?.clicks, "&nbsp;&nbsp;&nbsp;")
        }
    }

    return lines.join("<br>")
}


private String bullet(String txt) {
    return "• ${txt}"
}

private String vdKind(String fn) {
    if (fn in ["brightness", "ct", "color", "sat"]) return "dimmer"
    if (fn == "vol") return "speaker"
    if (fn == "blinds") return "blinds"
    return "dimmer"
}

private void addClickLines(
    List<String> lines,
    Integer flicNum,
    Integer sel,
    Map clicks,
    String indent = ""
) {
    if (!clicks) return

    String mode = (clicks.mode ?: "none").toString()
    if (mode == "none") return

    if (mode == "real") {
        if (clicks.event1?.action && clicks.event1.action != "none") {
            lines << "${indent}• Push = Advanced → Flic Hub Studio → Message: <b>flic${flicNum}-button${sel}-event1</b>"
        }
        if (clicks.event2?.action && clicks.event2.action != "none") {
            lines << "${indent}• Double Push = Advanced → Flic Hub Studio → Message: <b>flic${flicNum}-button${sel}-event2</b>"
        }
        return
    }

    // virtual mode: still need Studio messages so Hubitat can fire the virtual button child
    if (mode == "virtual") {
        lines << "${indent}• Push = Advanced → Flic Hub Studio → Message: <b>flic${flicNum}-button${sel}-event1</b>"
        lines << "${indent}• Double Push = Advanced → Flic Hub Studio → Message: <b>flic${flicNum}-button${sel}-event2</b>"
        return
    }
}



/* ---------------- Save config + child cleanup ---------------- */

private void ensureConfiguredShell(String bdaddr, def discoveredButton, String inferredType) {
    String bd = normBdaddr(bdaddr)
    def cfg = (state.configured ?: [:])
    if (!cfg[bd]) {
        cfg[bd] = [
            bdaddr: bd,
            name: (discoveredButton?.name ?: "Flic Device"),
            type: inferredType,
            flicNum: null,
            savedAt: new Date().toString(),
            mappings: [:]
        ]
        state.configured = cfg
        ensureAutoNumbers()
    } else {
        cfg[bd].bdaddr = bd
        cfg[bd].name = (discoveredButton?.name ?: cfg[bd].name ?: "Flic Device")
        cfg[bd].type = inferredType
        state.configured = cfg
    }
}

// Rreturn used flic numbers excluding a bdaddr (if provided)
private Set<Integer> usedFlicNums(String excludeBdaddr = null) {
    String ex = excludeBdaddr ? normBdaddr(excludeBdaddr) : null
    def cfg = (state.configured ?: [:])
    Set<Integer> used = new HashSet<>()
    cfg.each { bd, e ->
        if (ex && normBdaddr(bd) == ex) return
        try {
            Integer n = (e?.flicNum as Integer)
            if (n != null && n > 0) used.add(n)
        } catch (ignored) {}
    }
    return used
}

// Remove device from configuration + delete any referenced child devices
private void removeConfiguredDevice(String bdaddrIn) {
    String bdaddr = normBdaddr(bdaddrIn)
    if (!bdaddr) return

    def cfg = (state.configured ?: [:])
    def entry = cfg[bdaddr]
    if (!entry) return

    try {
        cleanupVirtualChildren((entry?.mappings ?: [:]) as Map, [:])
    } catch (ignored) {}

    cfg.remove(bdaddr)
    state.configured = cfg

    // Re-assert uniqueness / fill any gaps
    ensureAutoNumbers()
    invalidateSetConfigCache() 
}


private void ensureAutoNumbers() {
    def cfg = (state.configured ?: [:])
    if (!cfg) return

    // Detect duplicates: num -> list(bdaddr)
    Map<Integer, List<String>> byNum = [:].withDefault { [] }
    cfg.each { bd, e ->
        Integer n = null
        try { n = (e?.flicNum as Integer) } catch (ignored) {}
        if (n != null && n > 0) byNum[n] << bd.toString()
    }

    // If a number is duplicated, keep the first (stable sort) and clear the rest for reassignment
    byNum.each { n, bds ->
        if (bds.size() > 1) {
            String keep = bds.toList().sort()[0]
            bds.each { bd ->
                if (bd != keep) {
                    try { cfg[bd].flicNum = null } catch (ignored) {}
                }
            }
        }
    }

    // Collect used numbers after clearing duplicates
    Set<Integer> used = new HashSet<>()
    cfg.values().each { c ->
        try {
            Integer n = c?.flicNum as Integer
            if (n != null && n > 0) used.add(n)
        } catch (ignored) {}
    }

    // Assign next free numbers for any missing entries
    cfg.keySet().toList().sort().each { bd ->
        def c = cfg[bd]
        Integer n = null
        try { n = c?.flicNum as Integer } catch (ignored) {}
        if (n != null && n > 0) return

        int next = 1
        while (used.contains(next)) next++
        c.flicNum = next
        used.add(next)
    }

    state.configured = cfg
}

private void saveConfiguredDevice(String bdaddrIn, def discoveredButton, String inferredType) {
    String bdaddr = normBdaddr(bdaddrIn)
    if (!bdaddr) return

    def cfg = (state.configured ?: [:])
    if (!cfg[bdaddr]) ensureConfiguredShell(bdaddr, discoveredButton, inferredType)

    // Apply user-edited HE<>Flic number (must be unique)
    String flicNumKey = k(bdaddr, "flic_num")
    Integer desired = safeInt(settings[flicNumKey], null)
    if (desired != null && desired > 0) {
        Set<Integer> used = usedFlicNums(bdaddr)
        if (!used.contains(desired)) {
            try { cfg[bdaddr].flicNum = desired } catch (ignored) {}
        }
    }

    // Ensure numbers are unique and fill any missing numbers
    ensureAutoNumbers()

    def entry = cfg[bdaddr] ?: [:]
    def prevMappings = (entry.mappings ?: [:])

    entry.bdaddr = bdaddr
    entry.name = (discoveredButton?.name ?: entry.name ?: "Flic Device")
    entry.type = inferredType
    entry.savedAt = new Date().toString()

    def newMappings = buildMappingsForCurrentForm(bdaddr, inferredType, discoveredButton)
    cleanupVirtualChildren(prevMappings, newMappings)

    entry.mappings = newMappings
    cfg[bdaddr] = entry
    state.configured = cfg
    invalidateSetConfigCache()
}

private void cleanupVirtualChildren(Map prevMappings, Map newMappings) {
    Set<String> prev = new HashSet<>()
    Set<String> now  = new HashSet<>()

    collectChildDnis(prevMappings, prev)
    collectChildDnis(newMappings, now)

    prev.each { dni -> if (!now.contains(dni)) deleteChildByDni(dni) }
}

private void collectChildDnis(def node, Set<String> out) {
    if (node == null) return
    if (node instanceof Map) {
        if (node.childDni) out.add(node.childDni.toString())
        node.values().each { collectChildDnis(it, out) }
    } else if (node instanceof List) {
        node.each { collectChildDnis(it, out) }
    }
}

private void deleteChildByDni(String dni) {
    if (!dni) return
    def child = getChildDevices()?.find { it.deviceNetworkId == dni }
    if (!child) return
    try {
        deleteChildDevice(dni)
    } catch (e) {
        warnEvt "Failed to delete child dni=${dni}: ${e}"
    }
}

/* ---------------- Build mappings ---------------- */

private Map buildMappingsForCurrentForm(String bdaddr, String inferredType, def discovered) {
    def m = [:]

    if (inferredType == "button") {
        m.clicks = buildButtonOnlyClicksBlock(bdaddr, discovered)
        return m
    }

    String masterFn = (settings[k(bdaddr, "tw_master_fn")] ?: "none").toString()
    boolean usePushTwist = (settings[k(bdaddr, "tw_use_push_twist")] == true)
    String pushMode = usePushTwist ? (settings[k(bdaddr, "tw_push_mode")] ?: "sel_adv").toString() : "off"

    boolean includeSelectors = (usePushTwist && (pushMode == "sel_basic" || pushMode == "sel_adv"))
    List<Integer> sels = includeSelectors ? ((pushMode == "sel_basic") ? [3, 6, 9] : (1..11).toList()) : []

    m.twistUi = [
        masterFn: masterFn,
        usePushTwist: usePushTwist,
        pushMode: pushMode,
        includeSelectors: includeSelectors,
        selectorSels: sels
    ]

    m.master = [
        twist: buildTwistLevelBlock(bdaddr, 0, true, discovered, masterFn),
        clicks: buildTwistClicksBlock(bdaddr, 0, true, discovered)
    ]

    if (usePushTwist && (pushMode in ["brightness", "ct", "color", "sat", "vol", "blinds"])) {
        m.secondary = [
            twist: buildTwistLevelBlock(bdaddr, 1, false, discovered, pushMode) // sel=1 secondary
        ]
    }

    m.selectors = [:]
    if (includeSelectors) {
        sels.each { sel ->
            String fnKey = k(bdaddr, "tw_s${sel}_fn")
            String fn = (settings[fnKey] ?: "none").toString()
            m.selectors["${sel}"] = [
                twist: buildTwistLevelBlock(bdaddr, sel, false, discovered, fn),
                clicks: buildTwistClicksBlock(bdaddr, sel, false, discovered)
            ]
        }
    }

    return m
}
/* ---------- Mapping builders ---------- */

private Map buildButtonOnlyClicksBlock(String bdaddr, def discovered) {
    String modeKey = k(bdaddr, "b_clicks_mode")
    String mode = (settings[modeKey] ?: "none").toString()
    def out = [mode: mode]

    if (mode == "virtual") {
        String labelKey = k(bdaddr, "b_clicks_vlabel")
        String label = (settings[labelKey] ?: defaultLabelButton(discovered, "Button")).toString()
        def child = ensureVirtualButtonChild(bdaddr, "button-clicks", label)
        out.childDni = child?.deviceNetworkId
        out.childKind = child?.typeName ?: ""
        return out
    }

    if (mode == "real") {
        out.event1   = buildRealEventActionKey(bdaddr, "b", "sc")
        out.event2   = buildRealEventActionKey(bdaddr, "b", "dc")
        out.eventH   = buildRealEventActionKey(bdaddr, "b", "hd")
        out.release  = buildRealEventActionKey(bdaddr, "b", "rel")
        return out
    }

    return out
}

private Map buildTwistLevelBlock(String bdaddr, Integer sel, boolean isMaster, def discovered, String fn) {
    String modeKey = isMaster ? k(bdaddr, "tw_m_twist_mode")
        : k(bdaddr, "tw_s${sel}_twist_mode")
    String mode = (settings[modeKey] ?: "none").toString()
    def out = [mode: mode, fn: (fn ?: "none")]

    if (mode == "virtual") {
        String labelKey = isMaster ? k(bdaddr, "tw_m_twist_vlabel")
            : k(bdaddr, "tw_s${sel}_twist_vlabel")

        String suffix
        if (isMaster) suffix = "Master ${TW_FUNCS[fn] ?: fn}"
        else if (sel == 1) suffix = "Push & Twist ${TW_FUNCS[fn] ?: fn}"
        else suffix = "${sel} o'clock ${TW_FUNCS[fn] ?: fn}"

        String defLabel = defaultLabelTwist(discovered, suffix)
        String label = (settings[labelKey] ?: defLabel).toString()

        def child = ensureVirtualTwistTargetChild(bdaddr, sel, fn, label)
        out.childDni = child?.deviceNetworkId
        return out
    }

    if (mode == "real") {
        out.targetsKey = isMaster ? k(bdaddr, "tw_m_twist_targets")
            : k(bdaddr, "tw_s${sel}_twist_targets")
        return out
    }

    return out
}

private Map buildTwistClicksBlock(String bdaddr, Integer sel, boolean isMaster, def discovered) {

    boolean usePushTwist = (settings[k(bdaddr, "tw_use_push_twist")] == true)
    String pushMode = usePushTwist ? (settings[k(bdaddr, "tw_push_mode")] ?: "sel_adv").toString() : "off"
    boolean includeSelectors = (usePushTwist && (pushMode == "sel_basic" || pushMode == "sel_adv"))

    String modeKey = isMaster ? k(bdaddr, "tw_m_clicks_mode")
        : k(bdaddr, "tw_s${sel}_clicks_mode")
    String mode = (settings[modeKey] ?: "none").toString()
    def out = [mode: mode]

    if (mode == "virtual") {
        String labelKey = isMaster ? k(bdaddr, "tw_m_clicks_vlabel")
            : k(bdaddr, "tw_s${sel}_clicks_vlabel")
        String defLabel = isMaster ? defaultLabelTwist(discovered, "Master Button")
            : defaultLabelTwist(discovered, "${sel} o'clock Button")
        String label = (settings[labelKey] ?: defLabel).toString()

        def child = ensureVirtualButtonChild(bdaddr, "sel${sel}-button", label)
        out.childDni = child?.deviceNetworkId
        out.childKind = child?.typeName ?: ""
        return out
    }

    if (mode == "real") {
        String keyPrefix = isMaster ? "tw_m" : "tw_s${sel}"
        out.event1 = buildRealEventActionKey(bdaddr, keyPrefix, "sc")
        out.event2 = buildRealEventActionKey(bdaddr, keyPrefix, "dc")

        // Hold + Release only for MASTER when Push & Twist is OFF
        if (isMaster && !includeSelectors && (settings[k(bdaddr, "tw_use_push_twist")] != true)) {
            out.eventH  = buildRealEventActionKey(bdaddr, "tw_m", "hd")
            out.release = buildRealEventActionKey(bdaddr, "tw_m", "rel")
        }
        return out
    }

    return out
}

private Map buildRealEventActionKey(String bdaddr, String keyPrefix, String which) {

    Integer n = getActionCount(bdaddr, keyPrefix, which)
    List<Map> actions = []

    (1..n).each { Integer idx ->
        String suf = "_a${idx}"

        String actionKey     = k(bdaddr, "${keyPrefix}_${which}_action${suf}")
        String targetsKey    = k(bdaddr, "${keyPrefix}_${which}_targets${suf}")

        String levelKey      = k(bdaddr, "${keyPrefix}_${which}_level${suf}")
        String webcoreUrlKey = k(bdaddr, "${keyPrefix}_${which}_webcore_url${suf}")

        String ctKey         = k(bdaddr, "${keyPrefix}_${which}_ct${suf}")
        String satKey        = k(bdaddr, "${keyPrefix}_${which}_sat${suf}")
        String volKey        = k(bdaddr, "${keyPrefix}_${which}_vol${suf}")
        String blindKey      = k(bdaddr, "${keyPrefix}_${which}_blind${suf}")

        String colorKey      = k(bdaddr, "${keyPrefix}_${which}_color${suf}")

        String action = (settings[actionKey] ?: "none").toString()
        if (!action || action == "none") return

        Integer level = safeInt(settings[levelKey], null)
        Integer ct    = safeInt(settings[ctKey], null)
        Integer sat   = safeInt(settings[satKey], null)
        Integer vol   = safeInt(settings[volKey], null)
        Integer blind = safeInt(settings[blindKey], null)

        String colorHex = (settings[colorKey] != null) ? settings[colorKey].toString().trim() : null

        actions << [
            action    : action,
            targetsKey: targetsKey,
            level     : level,
            urlKey    : webcoreUrlKey,
            ct        : ct,
            sat       : sat,
            vol       : vol,
            blind     : blind,
            colorHex  : colorHex
        ]
    }

    return [
        actions: actions
    ]
}


/* ---------------- Child device creation helpers ---------------- */

private def ensureVirtualTwistTargetChild(String bdaddr, Integer sel, String fn, String label) {
    if (!fn) fn = "brightness"
    if (fn == "brightness") return ensureVirtualDimmerChild(bdaddr, sel, label)
    if (fn == "ct") return ensureVirtualCtChild(bdaddr, sel, label)
    if (fn == "color" || fn == "sat") return ensureVirtualRgbChild(bdaddr, sel, label)
    if (fn == "vol") return ensureVirtualAudioChild(bdaddr, sel, label)
    if (fn == "blinds") return ensureVirtualBlindChild(bdaddr, sel, label)
    return ensureVirtualDimmerChild(bdaddr, sel, label)
}

private def ensureVirtualDimmerChild(String bdaddr, Integer sel, String label) {
    ensureAppUid()
    String dni = "${state.appUid}-flic-${bdaddr}-dimmer-${sel}"
    def existing = getChildDevices()?.find { it.deviceNetworkId == dni }
    if (existing) { if (label && existing.label != label) existing.setLabel(label); return existing }
    return addChildDevice("hubitat", "Virtual Dimmer", dni, [label: label ?: dni, name: label ?: dni, isComponent: true])
}

private def ensureVirtualCtChild(String bdaddr, Integer sel, String label) {
    ensureAppUid()
    String dni = "${state.appUid}-flic-${bdaddr}-ct-${sel}"
    def existing = getChildDevices()?.find { it.deviceNetworkId == dni }
    if (existing) { if (label && existing.label != label) existing.setLabel(label); return existing }
    return addChildDevice("hubitat", "Virtual Color Temperature Light", dni, [label: label ?: dni, name: label ?: dni, isComponent: true])
}

private def ensureVirtualRgbChild(String bdaddr, Integer sel, String label) {
    ensureAppUid()
    String dni = "${state.appUid}-flic-${bdaddr}-rgb-${sel}"
    def existing = getChildDevices()?.find { it.deviceNetworkId == dni }
    if (existing) { if (label && existing.label != label) existing.setLabel(label); return existing }
    return addChildDevice("hubitat", "Virtual RGB Light", dni, [label: label ?: dni, name: label ?: dni, isComponent: true])
}

private def ensureVirtualBlindChild(String bdaddr, Integer sel, String label) {
    ensureAppUid()
    String dni = "${state.appUid}-flic-${bdaddr}-blind-${sel}"
    def existing = getChildDevices()?.find { it.deviceNetworkId == dni }
    if (existing) {
        if (label && existing.label != label) existing.setLabel(label)
        return existing
    }
    return addChildDevice("hubitat", "Virtual Blind", dni, [label: label ?: dni, name: label ?: dni, isComponent: true])
}

private def ensureVirtualAudioChild(String bdaddr, Integer sel, String label) {
    ensureAppUid()
    String dni = "${state.appUid}-flic-${bdaddr}-audio-${sel}"
    def existing = getChildDevices()?.find { it.deviceNetworkId == dni }
    if (existing) {
        if (label && existing.label != label) existing.setLabel(label)
        return existing
    }
    return addChildDevice("hubitat", "Virtual audioVolume", dni, [label: label ?: dni, name: label ?: dni, isComponent: true])
}

/**
 * Create / return a Virtual Button child and initialise it as a 1-button device.
 * (If Virtual Button isn't available, fall back to Virtual Switch.)
 */
private def ensureVirtualButtonChild(String bdaddr, String suffix, String label) {
    ensureAppUid()
    String dni = "${state.appUid}-flic-${bdaddr}-${suffix}"
    def existing = getChildDevices()?.find { it.deviceNetworkId == dni }
    if (existing) {
        if (label && existing.label != label) existing.setLabel(label)
        tryInitButtonDevice(existing)
        return existing
    }
    def child = addChildDevice("hubitat", "Virtual Button", dni, [label: label ?: dni, name: label ?: dni, isComponent: true])
    tryInitButtonDevice(child)
    return child
}

private void tryInitButtonDevice(def dev) {
    if (!dev) return
    try {
        dev.sendEvent(name: "numberOfButtons", value: 1, isStateChange: true)
        dev.sendEvent(name: "supportedButtonValues", value: ["pushed", "doubleTapped", "held", "released"], isStateChange: true)
    } catch (ignored) { }
}

/* ---------------- Discovery via TCP child ---------------- */

private void doDiscovery() {
    if (!settings.flicHost || !settings.flicPort) return
    def tcp = ensureTcpChild()
    tcp.sendListButtonsTo(settings.flicHost.toString(), (settings.flicPort as Integer))

    pauseExecution(1200)
    if (!captureDiscoveryResultOnce()) {
        pauseExecution(1200)
        captureDiscoveryResultOnce()
    }
}

private boolean captureDiscoveryResultOnce() {
    def tcp = getTcpChild()
    if (!tcp) return false

    def raw = tcp.currentValue("lastNonHello")
    if (!raw) return false

    try {
        def obj = new JsonSlurper().parseText(raw)

        if (obj?.ok == true && obj?.buttons instanceof List) {

            List<Map> cleaned = []

            obj.buttons.each { btn ->
                String bd = normBdaddr(btn?.bdaddr)
                if (!bd) return

                Integer flicVersion = null
                try { flicVersion = (btn?.flicVersion as Integer) } catch (ignored) { flicVersion = null }

                String serialNumber = (btn?.serialNumber ?: "").toString().trim()

                cleaned << [
                    name        : (btn?.name ?: "Flic Device"),
                    bdaddr      : bd,
                    type        : (btn?.type ?: ""),          // usually blank
                    connected   : (btn?.connected == true),

                    // promoted fields used for type inference
                    flicVersion : flicVersion,
                    serialNumber: serialNumber
                ]
            }

            state.discovered = cleaned
            return true
        }

    } catch (e) {
        log.warn "captureDiscoveryResult parse error: ${e}"
    }

    return false
}

/* ---------------- Push SET_CONFIG ---------------- */

def reviewPage() {
    dynamicPage(
        name: "reviewPage",
        title: "",
        install: true,
        uninstall: true
    ) {
        Map result = pushConfigToStudioOnce()

        section("") {
            if (result.ok) {
                paragraph "<b>Configuration successfully pushed to your Flic Hub</b>"
            } else {
                paragraph "<b>Configuration push failed</b><br>${result.msg ?: "Unknown error"}"
            }
        }

        section("<b>Configured devices:</b>") {
            def cfg = (state.configured ?: [:])
            if (!cfg || cfg.size() == 0) {
                paragraph "None configured."
            } else {
                cfg.values().sort { (it.flicNum as Integer) }.each { c ->
                    paragraph "    • ${c.name} (${c.type})"
                }
            }
        }

        section("") {
            paragraph "Click <b>Done</b> to close this window."
        }
    }
}


/**
 * Send SET_CONFIG only once per “visit” to the review page.
 * Prevents repeated sends caused by Hubitat re-rendering the page.
 */
private Map pushConfigToStudioOnce() {
    if (state?.rt == null) state.rt = [:]

    String cfgHash = computeConfigHash()

    // If we already sent for THIS EXACT CONFIG, return cached result
    if ((state.rt.lastSetConfigResult instanceof Map) && (state.rt.lastSetConfigHash == cfgHash)) {
        return (state.rt.lastSetConfigResult as Map)
    }

    // Otherwise resend and update cache
    Map result = pushConfigToStudioInternal()
    state.rt.lastSetConfigResult = result
    state.rt.lastSetConfigHash = cfgHash
    return result
}

private String computeConfigHash() {
    // Anything that should trigger a re-push goes into this string:
    def obj = [
        host: (settings.flicHost ?: ""),
        port: (settings.flicPort ?: ""),
        configured: (state.configured ?: [:])
    ]
    return JsonOutput.toJson(obj)   // stable string; good enough as a "hash"
}


/**
 * Internal sender. Returns: [ok:<bool>, msg:<string>]
 */
private Map pushConfigToStudioInternal() {
    if (!settings.flicHost || !settings.flicPort) {
        return [ok: false, msg: "Missing Flic host/port."]
    }

    String tok = ensureAccessToken()
    if (!tok) {
        return [ok: false, msg: "Missing OAuth token. Enable OAuth for this app and reopen."]
    }

    def cfg = (state.configured ?: [:])
    if (!cfg || cfg.size() == 0) {
        return [ok: false, msg: "Nothing configured."]
    }

    ensureAutoNumbers()

    def tcp = ensureTcpChild()
    def base = hubitatBaseForLan()
    def appId = app.id as String

    def devs = [:]
    cfg.values().each { c ->
        String bd = normBdaddr(c.bdaddr)
        boolean usePushTwist = (c?.mappings?.twistUi?.usePushTwist == true)
        devs[bd] = [
            flicNum    : "${c.flicNum}",
            type       : (c.type ?: "button"),
            enabled    : true,
            pushSelect : usePushTwist
        ]
    }

    def payload = [
        cmd    : "SET_CONFIG",
        hubitat: [base: base, appId: appId, token: tok],
        devices: devs
    ]

    try {
        tcp.sendRawJsonTo(
            settings.flicHost.toString(),
            (settings.flicPort as Integer),
            JsonOutput.toJson(payload)
        )

        // Log once for supportability
        infoEvt "SET_CONFIG sent to Flic Hub (${settings.flicHost}:${settings.flicPort}) for ${devs?.size() ?: 0} device(s)."

        return [ok: true, msg: "Configuration successfully pushed to your Flic Hub."]
    } catch (e) {
        warnEvt "SET_CONFIG send failed: ${e}"
        return [ok: false, msg: "SET_CONFIG send failed: ${e}"]
    }
}


/* ---------------- Event endpoint ---------------- */

def apiEvent() {
    String bdaddr = normBdaddr(params.bdaddr)
    String type = (params.type ?: "").toString()
    String event = (params.event ?: "").toString()
    String selStr = (params.sel ?: "").toString()
    String valueStr = (params.value ?: "").toString()

    // Virtual update metadata
    String kindStr = (params.kind ?: "").toString()            // dimmer|speaker|blinds
    String dimTypeStr = (params.dimmableType ?: "").toString() // Light|Speaker|Blind

    // Virtual update fields (0..1)
    BigDecimal b01 = safeDec(params.brightness, null)
    BigDecimal ct01 = safeDec(params.colorTemperature, null)
    BigDecimal h01 = safeDec(params.hue, null)
    BigDecimal s01 = safeDec(params.saturation, null)
    BigDecimal v01 = safeDec(params.volume, null)
    BigDecimal p01 = safeDec(params.position, null)

    if (!bdaddr || !event) {
        renderOk([ok: false, err: "missing_params"])
        return
    }

    def cfg = (state.configured ?: [:])[bdaddr]
    if (!cfg) {
        renderOk([ok: true, ignored: true, reason: "bdaddr_not_configured"])
        return
    }

    try {
        handleIncomingEvent(cfg as Map, type, event, selStr, valueStr, kindStr, dimTypeStr,
            b01, ct01, h01, s01, v01, p01)
        renderOk([ok: true])
    } catch (e) {
        warnEvt "apiEvent handler_exception: ${e}"
        renderOk([ok: false, err: "handler_exception", detail: "${e}"])
    }
}

private void handleIncomingEvent(
        Map cfg,
        String type,
        String event,
        String selStr,
        String valueStr,
        String kindStr,
        String dimTypeStr,
        BigDecimal brightness01,
        BigDecimal colorTemperature01,
        BigDecimal hue01,
        BigDecimal saturation01,
        BigDecimal volume01,
        BigDecimal position01
) {
    def mappings = cfg.mappings ?: [:]
    String cfgType = (cfg.type ?: type ?: "").toString()
    Integer sel = safeInt(selStr, 0)

    // BUTTON DEVICE PATH (includes release)
    if (cfgType == "button") {
        def clicks = mappings.clicks ?: [:]
        if (event in ["event1", "event2", "eventH", "release"]) {
            applyClicksBlock(clicks, event, false)
        }
        return
    }

    if (cfgType != "twist") return

    boolean usePushTwist = (mappings?.twistUi?.usePushTwist == true)
    boolean includeSelectors = (mappings?.twistUi?.includeSelectors == true)

    // Virtual device update (native keys 0..1)
    if (event == "vd") {
        applyVirtualUpdateBlock(mappings, sel, kindStr, dimTypeStr,
            brightness01, colorTemperature01, hue01, saturation01, volume01, position01)
        return
    }

    // Release only when Push & Twist is OFF (master only)
    if (event == "release") {
        if (usePushTwist) return
        if (sel != 0) return
        Map clicks = getTwistClicksBlock(mappings, 0)
        applyClicksBlock(clicks, "release", false)
        return
    }

    if (event in ["event1", "event2", "eventH"]) {

        // In Push & Twist mode, no master hold
        if (usePushTwist && event == "eventH") return

        // If selectors are enabled, holds are ignored entirely
        if (includeSelectors && event == "eventH") return
        if (sel > 0 && event == "eventH") return

	        // Selector-mode de-dupe:
	        // In rare cases Studio can emit both a master fallback click (sel=0)
	        // and a selector click (sel=1..11) for the same physical press if the
	        // actionMessage arrives after the fallback window. If a selector click
	        // was seen very recently, ignore the master click.
	        if (includeSelectors) {
	            Long nowMs = now()
	            String bd = normBdaddr(cfg?.bdaddr)
	            if (bd) {
	                if (state?.rt == null) state.rt = [:]
	                if (state.rt.lastSelectorClickMs == null) state.rt.lastSelectorClickMs = [:]
	                if (sel != 0) {
	                    state.rt.lastSelectorClickMs[bd] = nowMs
	                } else {
	                    def last = state.rt.lastSelectorClickMs[bd]
	                    Long lastMs = (last instanceof Number) ? (last as Number).longValue() : null
	                    if (lastMs != null && (nowMs - lastMs) <= 500L) return
	                }
	            }
	        }

        Map clicks = getTwistClicksBlock(mappings, sel)
        applyClicksBlock(clicks, event, includeSelectors)
        return
    }
}

/* ---------------- Apply virtual updates ---------------- */

private void applyVirtualUpdateBlock(
        Map mappings,
        Integer sel,
        String kindStr,
        String dimTypeStr,
        BigDecimal brightness01,
        BigDecimal colorTemperature01,
        BigDecimal hue01,
        BigDecimal saturation01,
        BigDecimal volume01,
        BigDecimal position01
) {
    Map block = resolveTwistBlockForSel(mappings, sel)
    String mode = (block?.mode ?: "none").toString()
    if (mode == "none") return

    String fn = (block?.fn ?: "none").toString()
    if (fn == "none") return

    // Normalise/clip 0..1
    BigDecimal b01 = clamp01(brightness01)
    BigDecimal ct01 = clamp01(colorTemperature01)
    BigDecimal h01 = clamp01(hue01)
    BigDecimal s01 = clamp01(saturation01)
    BigDecimal v01 = clamp01(volume01)
    BigDecimal p01 = clamp01(position01)

    // Build an apply closure that runs against a device
    def applyTo = { dev ->
        if (!dev) return

        try {
            if (fn == "brightness") {
                Integer lvl = pctFrom01(b01, 0)
                suppressDevAttr(dev, "level")
                try { dev.setLevel(lvl) } catch (ignored) {}
                return
            }

            if (fn == "ct") {
                Integer kMin = 2000
                Integer kMax = 6500
                Integer k = kMin + Math.round((kMax - kMin) * ((ct01 ?: 0G) as BigDecimal)) as Integer
                k = clampInt(k, kMin, kMax)
                suppressDevAttr(dev, "colorTemperature")
                try { dev.setColorTemperature(k) } catch (ignored) {}
                return
            }

            if (fn == "color") {
                Integer huePct = pctFrom01(h01, 0)
                Integer satPct = pctFrom01(s01, 100)
                Integer levPct = pctFrom01(b01, 100)
                suppressDevAttr(dev, "hue")
                suppressDevAttr(dev, "saturation")
                suppressDevAttr(dev, "level")
                try { dev.setColor([hue: huePct, saturation: satPct, level: levPct]) } catch (ignored) {}
                return
            }

            if (fn == "sat") {
                Integer satPct = pctFrom01(s01, pctFrom01(b01, 0))
                suppressDevAttr(dev, "saturation")
                try { dev.setColor([hue: 0, saturation: satPct, level: 100]) } catch (ignored) {}
                return
            }

            if (fn == "vol") {
                Integer volPct = pctFrom01(v01, pctFrom01(b01, 0))
                suppressDevAttr(dev, "volume")
                suppressDevAttr(dev, "level")
                try { dev.setVolume(volPct) } catch (ignored) {}
                try { dev.setLevel(volPct) } catch (ignored) {}
                return
            }

            if (fn == "blinds") {
                Integer posPct = pctFrom01(p01, pctFrom01(b01, 0))
                suppressDevAttr(dev, "position")
                try { dev.setPosition(posPct) } catch (ignored) {}
                return
            }

        } catch (ignoredAll) { }
    }

    if (mode == "virtual") {
        def dni = block?.childDni
        if (!dni) return
        def ch = getChildDevices()?.find { it.deviceNetworkId == dni }
        applyTo(ch)
        return
    }

    if (mode == "real") {
        String key = block?.targetsKey?.toString()
        def devs = key ? settings[key] : null
        if (!devs) return
        devs.each { d -> applyTo(d) }
        return
    }
}

private Map resolveTwistBlockForSel(Map mappings, Integer sel) {
    if (sel == null) sel = 0
    if (sel == 0) return (mappings?.master?.twist ?: [:])
    if (sel == 1 && mappings?.secondary?.twist) return (mappings?.secondary?.twist ?: [:])
    return ((mappings?.selectors ?: [:])["${sel}"]?.twist ?: [:])
}

/* ---------------- Click actions ---------------- */

private Map getTwistClicksBlock(Map mappings, Integer sel) {
    if (sel == 0) return (mappings?.master?.clicks ?: [:])
    return ((mappings?.selectors ?: [:])["${sel}"]?.clicks ?: [:])
}

private void applyClicksBlock(Map clicksBlock, String event, boolean ignoreHold) {
    String mode = (clicksBlock?.mode ?: "none").toString()
    if (mode == "none") return
    if (event == "eventH" && ignoreHold) return

    if (mode == "virtual") {
        def dni = clicksBlock?.childDni
        if (!dni) return
        def ch = getChildDevices()?.find { it.deviceNetworkId == dni }
        if (!ch) return

        boolean didButton = tryEmitVirtualButtonEvent(ch, event)
        if (!didButton) toggleSwitch(ch)
        return
    }

    if (mode == "real") {
        Map bundle = (event == "event1") ? (clicksBlock?.event1 ?: [:])
            : (event == "event2") ? (clicksBlock?.event2 ?: [:])
            : (event == "eventH") ? (clicksBlock?.eventH ?: [:])
            : (event == "release") ? (clicksBlock?.release ?: [:])
            : [:]

        def acts = bundle?.actions
        if (acts instanceof List) {
            acts.each { a -> applySwitchAction(a as Map) }
        } else if (bundle instanceof Map) {
            // backward compatibility (if any old configs exist)
            applySwitchAction(bundle as Map)
        }
        return
    }
}

private boolean tryEmitVirtualButtonEvent(def ch, String event) {
    if (!ch || !event) return false

    String evtName =
        (event == "event1") ? "pushed"
            : (event == "event2") ? "doubleTapped"
                : (event == "eventH") ? "held"
                    : (event == "release") ? "released"
                        : null
    if (!evtName) return false

    try {
        ch.sendEvent(name: evtName, value: 1, isStateChange: true)

        if (evtName == "pushed") {
            try { ch.push(1) } catch (ignored) {}
        } else if (evtName == "doubleTapped") {
            try { ch.doubleTap(1) } catch (ignored) {}
        } else if (evtName == "held") {
            try { ch.hold(1) } catch (ignored) {}
        } else if (evtName == "released") {
            try { ch.release(1) } catch (ignored) {}
            try { ch.released(1) } catch (ignored) {}
        }
        return true
    } catch (e) {
        return false
    }
}

private void toggleSwitch(def ch) {
    try {
        def sw = (ch.currentValue("switch") ?: "off") as String
        if (sw == "on") ch.off() else ch.on()
    } catch (ignored) {}
}

private void applySwitchAction(Map rule) {
    if (!rule) return

    String a = (rule?.action ?: "none").toString().toLowerCase()
    if (a == "none") return

    // webCoRE: per-event HTTP GET
    if (a == "webcore") {
        String url = ""
        try { url = (rule?.urlKey ? (settings[rule.urlKey] ?: "") : "").toString().trim() } catch (ignored) {}
        if (!url) return
        execWebcoreHttpGet(url)
        return
    }

    String targetsKey = (rule?.targetsKey ?: "").toString()
    def devs = targetsKey ? settings[targetsKey] : null
    if (!devs) return

    devs.each { d ->
        if (!d) return
        try {

            if (a == "on") {
                d.on()
                return
            }

            if (a == "off") {
                d.off()
                return
            }

            if (a == "toggle") {
                def sw = (d.currentValue("switch") ?: "off") as String
                if (sw == "on") d.off() else d.on()
                return
            }

            if (a == "setlevel") {
                Integer lvl = clampInt((rule?.level != null ? (rule.level as Integer) : 100), 1, 100)
                try { d.setLevel(lvl) } catch (ignored) { }
                try { d.on() } catch (ignored) { }
                return
            }

            if (a == "lock") {
                try { d.lock() } catch (ignored) { }
                return
            }

            if (a == "unlock") {
                try { d.unlock() } catch (ignored) { }
                return
            }

            if (a == "setcolortemperature") {
                Integer kMin = 2000
                Integer kMax = 6500
                Integer k = clampInt((rule?.ct != null ? (rule.ct as Integer) : 3000), kMin, kMax)
                try { d.setColorTemperature(k) } catch (ignored) { }
                return
            }

            if (a == "setsaturation") {
                Integer sat = clampInt((rule?.sat != null ? (rule.sat as Integer) : 100), 0, 100)
                try { d.setColor([hue: safeInt(d.currentValue("hue"), 0) ?: 0, saturation: sat, level: safeInt(d.currentValue("level"), 100) ?: 100]) } catch (ignored) { }
                return
            }

            if (a == "setvolume") {
                Integer vol = clampInt((rule?.vol != null ? (rule.vol as Integer) : 50), 0, 100)
                try { d.setVolume(vol) } catch (ignored) { }
                // some drivers expose level instead of volume
                try { d.setLevel(vol) } catch (ignored) { }
                return
            }

            if (a == "blindssetlevel") {
                Integer pos = clampInt((rule?.blind != null ? (rule.blind as Integer) : 50), 0, 100)
                // Most shade drivers use setPosition; some accept setLevel
                try { d.setPosition(pos) } catch (ignored) { }
                try { d.setLevel(pos) } catch (ignored) { }
                return
            }

            if (a == "setcolor") {

                String hex = (rule?.colorHex != null) ? rule.colorHex.toString().trim() : null
                if (!hex) return
                if (!hex.startsWith("#")) hex = "#${hex}"
                if (!(hex ==~ /^#[0-9a-fA-F]{6}$/)) return

                Integer r = Integer.parseInt(hex.substring(1, 3), 16)
                Integer g = Integer.parseInt(hex.substring(3, 5), 16)
                Integer b = Integer.parseInt(hex.substring(5, 7), 16)

                // RGB(0..255) -> HSV; then Hue(0..100), Sat(0..100), Level(0..100)
                double rf = r / 255.0d
                double gf = g / 255.0d
                double bf = b / 255.0d

                double cMax = Math.max(rf, Math.max(gf, bf))
                double cMin = Math.min(rf, Math.min(gf, bf))
                double delta = cMax - cMin

                double hDeg
                if (delta == 0.0d) {
                    hDeg = 0.0d
                } else if (cMax == rf) {
                    hDeg = 60.0d * (((gf - bf) / delta) % 6.0d)
                } else if (cMax == gf) {
                    hDeg = 60.0d * (((bf - rf) / delta) + 2.0d)
                } else {
                    hDeg = 60.0d * (((rf - gf) / delta) + 4.0d)
                }
                if (hDeg < 0.0d) hDeg += 360.0d

                double s01 = (cMax == 0.0d) ? 0.0d : (delta / cMax)
                double v01 = cMax

                Integer huePct = clampInt((int)Math.round((hDeg / 360.0d) * 100.0d), 0, 100)
                Integer satPct = clampInt((int)Math.round(s01 * 100.0d), 0, 100)
                Integer levPct = clampInt((int)Math.round(v01 * 100.0d), 0, 100)

                try { d.setColor([hue: huePct, saturation: satPct, level: levPct]) } catch (ignored) { }
                try { d.on() } catch (ignored) { } // turns on light when chnaging color... comment out this line if you prefer to disable this "feature"
                return

            }
        } catch (ignoredAll) { }
    }
}


/* ---------------- Response helpers ---------------- */

private void renderOk(Map payload) {
    render contentType: "application/json", data: JsonOutput.toJson(payload)
}

/* ---------------- Type detection ---------------- */

private String inferDeviceType(def discovered, def existing) {
    if (existing?.type) return existing.type.toString()

    // Prefer explicit API type if it ever appears
    String ts = (discovered?.type ?: "").toString().trim().toLowerCase()
    if (ts.contains("twist")) return "twist"
    if (ts.contains("button")) return "button"

    // Heuristic from discovery raw fields (best we have)
    Integer fv = null
    try { fv = (discovered?.flicVersion as Integer) } catch (ignored) { fv = null }
    if (fv != null) {
        if (fv >= 3) return "twist"
        if (fv <= 2) return "button"
    }

    String sn = (discovered?.serialNumber ?: "").toString().trim().toUpperCase()
    if (sn.startsWith("CA")) return "twist"
    if (sn.startsWith("BH")) return "button"

    return "button"
}

/* ---------------- Two-way Sync (HE -> Studio) ---------------- */

private void setupTwoWaySync() {
    // We only use app-level subscriptions; safe to rebuild wholesale.
    try { unsubscribe() } catch (ignored) {}

    if (state?.rt == null) state.rt = [:]
    state.rt.syncIndex = [:]         // devId -> List<Route>
    state.rt.syncSuppress = [:]      // "devId:attr" -> untilMs
    state.rt.syncLastSent = [:]      // routeKey -> [ms:<long>, hash:<string>]

    buildSyncIndex()
    subscribeSyncIndex()
}

private void buildSyncIndex() {
    Map idx = [:].withDefault { [] }

    def cfg = (state.configured ?: [:])
    cfg.values().each { c ->
        if ((c?.type ?: "") != "twist") return

        String bdaddr = normBdaddr(c?.bdaddr)
        if (!bdaddr) return

        Map mappings = (c?.mappings ?: [:])

        // Master + optional secondary + selectors
        addTwistBlockRoutes(idx, bdaddr, 0, (mappings?.master?.twist ?: [:]))
        if (mappings?.secondary?.twist) addTwistBlockRoutes(idx, bdaddr, 1, (mappings?.secondary?.twist ?: [:]))

        def sels = (mappings?.selectors ?: [:])
        sels.keySet().each { selStr ->
            Integer sel = safeInt(selStr, null)
            if (sel == null) return
            addTwistBlockRoutes(idx, bdaddr, sel, (sels[selStr]?.twist ?: [:]))
        }
    }

    state.rt.syncIndex = idx
}

private void addTwistBlockRoutes(Map idx, String bdaddr, Integer sel, Map block) {
    if (!block) return
    String mode = (block?.mode ?: "none").toString()
    String fn = (block?.fn ?: "none").toString()
    if (mode == "none" || fn == "none") return

    Map meta = fnToKindMeta(fn)
    if (!meta) return

    // Build routes against one or more source devices
    List devs = []

    if (mode == "virtual") {
        String dni = (block?.childDni ?: "").toString()
        if (!dni) return
        def ch = getChildDevices()?.find { it.deviceNetworkId == dni }
        if (ch) devs << ch
    } else if (mode == "real") {
        String key = (block?.targetsKey ?: "").toString()
        def chosen = key ? settings[key] : null
        if (chosen instanceof List) devs.addAll(chosen)
        else if (chosen) devs << chosen
    }

    devs.each { dev ->
        if (!dev) return
        String devId = deviceIdStr(dev)
        if (!devId) return

        idx[devId] << [
            bdaddr      : bdaddr,
            sel         : (sel ?: 0),
            fn          : fn,
            kind        : (meta.kind ?: ""),
            dimmableType: (meta.dimmableType ?: ""),
            // for subscription & value extraction
            attrs       : attrsForFn(fn)
        ]
    }
}

private void subscribeSyncIndex() {
    Map idx = (state?.rt?.syncIndex ?: [:])
    if (!idx || idx.size() == 0) return

    idx.each { devId, routes ->
        def dev = findDeviceById(devId)
        if (!dev) return

        // Union attrs for this device
        Set<String> attrs = new HashSet<>()
        (routes ?: []).each { r ->
            (r?.attrs ?: []).each { a -> if (a) attrs.add(a.toString()) }
        }

        attrs.each { attr ->
            try {
                subscribe(dev, attr, "syncDeviceEvent")
            } catch (e) {
                // non-fatal; some devices may not support all attrs
                // warnEvt "sync subscribe failed dev=${dev} attr=${attr}: ${e}"
            }
        }
    }
}

def syncDeviceEvent(evt) {
    if (!evt) return
    String devId = (evt?.deviceId != null) ? evt.deviceId.toString() : deviceIdStr(evt?.device)
    if (!devId) return

    String attr = (evt?.name ?: "").toString()
    if (!attr) return

    // Echo suppression: ignore events we just caused (from Studio inbound apply)
    if (isSuppressed(devId, attr)) return

    Map idx = (state?.rt?.syncIndex ?: [:])
    List routes = (idx[devId] ?: [])
    if (!routes || routes.size() == 0) return

    def dev = findDeviceById(devId)
    if (!dev) return

    routes.each { r ->
        try {
            String bdaddr = normBdaddr(r?.bdaddr)
            Integer sel = safeInt(r?.sel, 0)
            String fn = (r?.fn ?: "none").toString()
            if (!bdaddr || fn == "none") return

            Map payload = buildUpdatePayloadFromDevice(dev, fn)
            if (!payload) return

            // Dedupe: avoid spamming Studio with identical values repeatedly
            if (isDuplicateSend(bdaddr, sel, fn, payload)) return

            pushUpdateVdToStudio(bdaddr, sel, (r?.kind ?: ""), (r?.dimmableType ?: ""), payload)

        } catch (ignored) { }
    }
}

/* ---------- Value extraction + mappings ---------- */

private Map fnToKindMeta(String fn) {
    if (!fn) return null
    if (fn == "brightness" || fn == "ct" || fn == "color" || fn == "sat") {
        return [kind: "dimmer", dimmableType: "Light"]
    }
    if (fn == "vol") {
        return [kind: "speaker", dimmableType: "Speaker"]
    }
    if (fn == "blinds") {
        return [kind: "blinds", dimmableType: "Blind"]
    }
    return null
}

private List<String> attrsForFn(String fn) {
    if (!fn) return []
    if (fn == "brightness") return ["level"]
    if (fn == "ct") return ["colorTemperature"]
    if (fn == "color") return ["hue", "saturation", "level"]
    if (fn == "sat") return ["saturation", "level"]
    if (fn == "vol") return ["volume", "level"]
    if (fn == "blinds") return ["position", "level"]
    return []
}

/**
 * Return ONLY 0..1 values appropriate for the function.
 * (Studio will accept these and update its internal virtual state.)
 */
private Map buildUpdatePayloadFromDevice(def dev, String fn) {
    if (!dev || !fn) return null

    try {
        if (fn == "brightness") {
            Integer lvl = safeInt(dev.currentValue("level"), null)
            if (lvl == null) return null
            return [brightness: clamp01(((lvl as BigDecimal) / 100.0G))]
        }

        if (fn == "ct") {
            Integer ct = safeInt(dev.currentValue("colorTemperature"), null)
            if (ct == null) return null
            Integer kMin = 2000
            Integer kMax = 6500
            ct = clampInt(ct, kMin, kMax)
            BigDecimal ct01 = ((ct - kMin) as BigDecimal) / ((kMax - kMin) as BigDecimal)
            return [colorTemperature: clamp01(ct01)]
        }

        if (fn == "color") {
            Integer huePct = safeInt(dev.currentValue("hue"), null)
            Integer satPct = safeInt(dev.currentValue("saturation"), null)
            Integer lvl = safeInt(dev.currentValue("level"), null)

            Map out = [:]
            if (huePct != null) out.hue = clamp01(((huePct as BigDecimal) / 100.0G))
            if (satPct != null) out.saturation = clamp01(((satPct as BigDecimal) / 100.0G))
            if (lvl != null) out.brightness = clamp01(((lvl as BigDecimal) / 100.0G))
            return out.size() ? out : null
        }

        if (fn == "sat") {
            Integer satPct = safeInt(dev.currentValue("saturation"), null)
            if (satPct == null) return null
            return [saturation: clamp01(((satPct as BigDecimal) / 100.0G))]
        }

        if (fn == "vol") {
            Integer volPct = safeInt(dev.currentValue("volume"), null)
            if (volPct == null) {
                // some drivers expose level instead
                volPct = safeInt(dev.currentValue("level"), null)
            }
            if (volPct == null) return null
            return [volume: clamp01(((volPct as BigDecimal) / 100.0G))]
        }

        if (fn == "blinds") {
            Integer posPct = safeInt(dev.currentValue("position"), null)
            if (posPct == null) {
                posPct = safeInt(dev.currentValue("level"), null)
            }
            if (posPct == null) return null
            return [position: clamp01(((posPct as BigDecimal) / 100.0G))]
        }

    } catch (ignored) { }

    return null
}

/* ---------- Dedupe + suppression ---------- */

private boolean isSuppressed(String devId, String attr) {
    Map sup = (state?.rt?.syncSuppress ?: [:])
    String key = "${devId}:${attr}"
    def until = sup[key]
    Long untilMs = (until instanceof Number) ? (until as Number).longValue() : null
    if (!untilMs) return false
    if (now() <= untilMs) return true
    try { sup.remove(key) } catch (ignored) {}
    state.rt.syncSuppress = sup
    return false
}

private void suppressDevAttr(def dev, String attr, Integer ms = null) {
    if (!dev || !attr) return
    String devId = deviceIdStr(dev)
    if (!devId) return

    if (state?.rt == null) state.rt = [:]
    if (state.rt.syncSuppress == null) state.rt.syncSuppress = [:]

    Integer dur = (ms != null ? ms : SYNC_SUPPRESS_MS)
    state.rt.syncSuppress["${devId}:${attr}"] = (now() + dur)
}

private boolean isDuplicateSend(String bdaddr, Integer sel, String fn, Map payload) {
    if (state?.rt == null) state.rt = [:]
    if (state.rt.syncLastSent == null) state.rt.syncLastSent = [:]

    String routeKey = "${bdaddr}|${sel}|${fn}"
    String hash = payload.toString()  // stable enough for our small maps

    def last = state.rt.syncLastSent[routeKey]
    Long lastMs = null
    String lastHash = null
    if (last instanceof Map) {
        def ms0 = last.ms
        lastMs = (ms0 instanceof Number) ? (ms0 as Number).longValue() : null
        lastHash = (last.hash ?: null)
    }

    Long nowMs = now()
    if (lastMs != null && lastHash != null) {
        if (lastHash == hash && (nowMs - lastMs) <= (SYNC_DEDUP_MS as Long)) return true
    }

    state.rt.syncLastSent[routeKey] = [ms: nowMs, hash: hash]
    return false
}


/* ---------------- Multi-action counts (per bdaddr + event) ---------------- */

private Map actionCounts() {
    if (state.rt == null) state.rt = [:]
    if (!(state.rt.actionCounts instanceof Map)) state.rt.actionCounts = [:]
    return (state.rt.actionCounts as Map)
}

private Integer getActionCount(String bdaddr, String keyPrefix, String which) {
    String bd = normBdaddr(bdaddr)
    if (!bd) return 1
    Map all = actionCounts()
    String k0 = "${bd}|${keyPrefix}|${which}"
    Integer n = safeInt(all[k0], 1)
    n = clampInt(n, 1, MAX_MULTI_ACTIONS)
    all[k0] = n
    state.rt.actionCounts = all
    return n
}

private void setActionCount(String bdaddr, String keyPrefix, String which, Integer n) {
    String bd = normBdaddr(bdaddr)
    if (!bd) return
    Integer nn = clampInt(n ?: 1, 1, MAX_MULTI_ACTIONS)
    Map all = actionCounts()
    String k0 = "${bd}|${keyPrefix}|${which}"
    all[k0] = nn
    state.rt.actionCounts = all
}

/**
 * Clear ALL setting keys for a given action row index (so removing a row cleans up).
 * idx is 1-based.
 */
private void clearActionRowSettings(String bdaddr, String keyPrefix, String which, Integer idx) {
    if (!idx || idx < 1) return
    String suf = "_a${idx}"

    clearSettingKey(k(bdaddr, "${keyPrefix}_${which}_action${suf}"))
    clearSettingKey(k(bdaddr, "${keyPrefix}_${which}_targets${suf}"))

    clearSettingKey(k(bdaddr, "${keyPrefix}_${which}_level${suf}"))
    clearSettingKey(k(bdaddr, "${keyPrefix}_${which}_webcore_url${suf}"))

    clearSettingKey(k(bdaddr, "${keyPrefix}_${which}_ct${suf}"))
    clearSettingKey(k(bdaddr, "${keyPrefix}_${which}_sat${suf}"))
    clearSettingKey(k(bdaddr, "${keyPrefix}_${which}_vol${suf}"))
    clearSettingKey(k(bdaddr, "${keyPrefix}_${which}_blind${suf}"))
    clearSettingKey(k(bdaddr, "${keyPrefix}_${which}_color${suf}"))

    // prevent stale prevAct causing clears next time this index is reused
    uiPrevClearActForRow(bdaddr, keyPrefix, which, idx)
}


/* ---------- webCoRE / Local HTTP GET Helpers ---------- */

private void execWebcoreHttpGet(String url) {
    try {
        String u = (url ?: "").toString().trim()
        if (!u) return

        // If the URL points back to THIS Hubitat hub, proxy via Flic Studio
        if (isHubSelfUrl(u)) {
            Map r = execHttpGetViaStudioProxy(u, 6000) // 6s timeout
            if (!(r?.ok == true)) {
                warnEvt "webCoRE GET via Studio proxy failed: ${r?.status ?: ""} ${r?.err ?: ""}"
            }
            return
        }

        // Otherwise do it directly (LAN device or external URL)
        httpGet([uri: u, contentType: "text/plain"]) { resp ->
            // optional: inspect resp.status / resp.data
        }

    } catch (e) {
        warnEvt "webCoRE HTTP GET failed: ${e}"
    }
}

/* ---------- Hub self-detection helpers (NO java.net imports) ---------- */

private String hubLocalIp() {
    try {
        def h = location?.hubs?.find { true }
        return (h?.localIP ?: h?.localIPaddress ?: h?.localIp)?.toString()
    } catch (ignored) {
        return null
    }
}

/**
 * Extract host from URL using regex only (Hubitat-safe).
 * Supports:
 *  - http://host:port/path
 *  - https://host/path
 *  - host:port/path   (scheme auto-added)
 */
private String urlHost(String url) {
    if (!url) return null
    String u = url.toString().trim()
    if (!u) return null

    // Add scheme if missing so regex is consistent
    String ul = u.toLowerCase()
    if (!(ul.startsWith("http://") || ul.startsWith("https://"))) {
        u = "http://${u}"
    }

    def m = (u =~ /^[a-zA-Z][a-zA-Z0-9+\-.]*:\/\/([^\/:\?#]+).*/)
    if (m.matches()) {
        return m[0][1]?.toString()
    }
    return null
}

private boolean isHubSelfUrl(String url) {
    String host = urlHost(url)
    if (!host) return false

    String hubIp = hubLocalIp()
    if (!hubIp) return false

    host = host.trim().toLowerCase()
    hubIp = hubIp.trim().toLowerCase()

    return (host == hubIp || host == "localhost" || host == "127.0.0.1")
}

/**
 * Proxy a GET through Flic Studio (used when Hubitat cannot call itself),
 * returns:
 *   [ok:true/false, status:int, body:String, err:String]
 */
private Map execHttpGetViaStudioProxy(String url, Integer timeoutMs = 5000) {
    if (!settings.flicHost || !settings.flicPort) {
        return [ok:false, err:"missing_flic_host_port"]
    }

    def tcp = ensureTcpChild()
    if (!tcp) return [ok:false, err:"missing_tcp_child"]

    // Hubitat-safe nonce (no Random())
    String nonce = "${now()}-${Math.abs((now() as Long).hashCode() % 100000)}"

    // Clear any stale previous result
    try { tcp.clearHttpProxyResult() } catch (ignored) {}

    Map payload = [
        cmd    : "HTTP_PROXY",
        nonce  : nonce,
        method : "GET",
        url    : url
    ]

    try {
        tcp.sendRawJsonTo(
            settings.flicHost.toString(),
            (settings.flicPort as Integer),
            JsonOutput.toJson(payload)
        )
    } catch (e) {
        return [ok:false, err:"send_failed:${e}"]
    }

    // Wait synchronously for Studio response
    Long start = now()
    while ((now() - start) < (timeoutMs as Long)) {
        try {
            String lastNonce = (tcp.currentValue("lastHttpProxyNonce") ?: "").toString()
            String raw = (tcp.currentValue("lastHttpProxyResult") ?: "").toString()

            if (raw && lastNonce == nonce) {
                try {
                    return (new JsonSlurper().parseText(raw) as Map)
                } catch (e2) {
                    return [ok:false, err:"bad_json:${e2}", raw: raw]
                }
            }
        } catch (ignored) {}

        pauseExecution(150)
    }

    return [ok:false, err:"timeout_waiting_proxy_result"]
}

/* ---------- Studio push ---------- */

private void pushUpdateVdToStudio(String bdaddr, Integer sel, String kind, String dimmableType, Map values01) {
    if (!settings.flicHost || !settings.flicPort) return

    def tcp = ensureTcpChild()
    if (!tcp) return

    Map payload = [
        cmd         : "UPDATE_VD",
        origin      : "hubitat",
        nonce       : "${now()}-${Math.abs((now() as Long).hashCode() % 100000)}",
        bdaddr      : normBdaddr(bdaddr),
        sel         : (sel != null ? sel : 0),
        kind        : (kind ?: ""),
        dimmableType: (dimmableType ?: "")
    ]

    // merge the 0..1 fields
    values01.each { k, v -> payload[k.toString()] = v }

    try {
        tcp.sendRawJsonTo(settings.flicHost.toString(), (settings.flicPort as Integer), JsonOutput.toJson(payload))
    } catch (ignored) { }
}

/* ---------- Device lookup helpers ---------- */

private String deviceIdStr(def dev) {
    try {
        def id = dev?.id
        if (id == null) return null
        return id.toString()
    } catch (ignored) {
        return null
    }
}

private def findDeviceById(String devId) {
    if (!devId) return null

    // Child devices first
    def ch = getChildDevices()?.find { d ->
        try { d?.id?.toString() == devId } catch (ignored) { false }
    }
    if (ch) return ch

    // Selected real devices can be found by scanning configured targets.
    // (Hubitat doesn’t provide a global “getDeviceById” to apps.)
    def cfg = (state.configured ?: [:])
    def seen = new HashSet<String>()

    cfg.values().each { c ->
        def mappings = (c?.mappings ?: [:])

        // Collect all targetsKey strings from twist blocks
        List<Map> blocks = []
        blocks << (mappings?.master?.twist ?: [:])
        if (mappings?.secondary?.twist) blocks << (mappings?.secondary?.twist ?: [:])
        (mappings?.selectors ?: [:]).values().each { s ->
            blocks << (s?.twist ?: [:])
        }

        blocks.each { b ->
            String mode = (b?.mode ?: "none").toString()
            if (mode != "real") return
            String key = (b?.targetsKey ?: "").toString()
            if (!key || seen.contains(key)) return
            seen.add(key)

            def chosen = settings[key]
            if (chosen instanceof List) {
                def found = chosen.find { d -> (d?.id?.toString() == devId) }
                if (found) { ch = found; return }
            } else if (chosen) {
                if (chosen?.id?.toString() == devId) { ch = chosen; return }
            }
        }

        if (ch) return
    }

    return ch
}


/* ---------------- OAuth helpers ---------------- */

private String ensureAccessToken() {
    String tok = state.accessToken as String
    if (tok) return tok

    try {
        def created = createAccessToken()
        if (created) {
            state.accessToken = created.toString()
            return state.accessToken
        }

        try {
            def propTok = this.hasProperty("accessToken") ? this.accessToken : null
            if (propTok) {
                state.accessToken = propTok.toString()
                return state.accessToken
            }
        } catch (ignored) { }

        tok = state.accessToken as String
        if (tok) return tok

        log.warn "OAuth token still missing after createAccessToken(); check that OAuth is enabled for this App Code."
        return null

    } catch (e) {
        log.warn "createAccessToken() failed. Ensure OAuth is enabled for this App Code. Error: ${e}"
        return null
    }
}

private String baseLocalUrl() {
    def full = getFullLocalApiServerUrl()
    if (full?.startsWith("https://")) full = full.replaceFirst("^https://", "http://")
    return full
}

private String hubitatBaseForLan() {
    def u = baseLocalUrl()
    def m = (u =~ /^(https?:\/\/[^\/]+).*/)
    if (m.matches()) return m[0][1]
    return u
}

/* ---------------- TCP child management ---------------- */

private void ensureAppUid() {
    if (!state.appUid) state.appUid = UUID.randomUUID().toString()
}

private String tcpChildDni() {
    ensureAppUid()
    return "${state.appUid}-flic-tcp"
}

private def getTcpChild() {
    return getChildDevices()?.find { it.deviceNetworkId == tcpChildDni() }
}

private def ensureTcpChild() {
    def child = getTcpChild()
    if (child) return child

    return addChildDevice(
        TCP_DRIVER_NS,
        TCP_DRIVER_NAME,
        tcpChildDni(),
        [label: "Flic TCP", name: "Flic TCP", isComponent: true]
    )
}
