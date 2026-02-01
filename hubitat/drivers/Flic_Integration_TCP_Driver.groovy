import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import hubitat.helper.HexUtils

metadata {
    definition(
        name: "Flic Integration TCP Driver V1.0",
        namespace: "local.flic",
        author: "Robin Winbourne",
        description: "One-shot TCP client for Flic Hub Studio (JSON lines) using rawSocket. Supports passing host/port per call.",
        iconUrl: "https://raw.githubusercontent.com/hubitat/HubitatPublic/master/icons/plug.png",
        iconX2Url: "https://raw.githubusercontent.com/hubitat/HubitatPublic/master/icons/plug@2x.png"
    ) {
        capability "Initialize"
        capability "Refresh"

        command "sendPing"
        command "sendStatus"
        command "sendListButtons"
        command "sendRawJson", [[name:"jsonLine", type:"STRING", description:"A JSON object as a single line."]]
        command "sendPingTo", [[name:"host", type:"STRING"], [name:"port", type:"NUMBER"]]
        command "sendStatusTo", [[name:"host", type:"STRING"], [name:"port", type:"NUMBER"]]
        command "sendListButtonsTo", [[name:"host", type:"STRING"], [name:"port", type:"NUMBER"]]
        command "sendRawJsonTo", [[name:"host", type:"STRING"], [name:"port", type:"NUMBER"], [name:"jsonLine", type:"STRING"]]

        attribute "connection", "string"     // idle / connecting / connected / closing / error
        attribute "lastResponse", "string"
        attribute "lastHello", "string"
        attribute "lastNonHello", "string"
        attribute "lastSocketStatus", "string"
        attribute "lastError", "string"
        attribute "lastTarget", "string"     // host:port last used
    }

    preferences {
        input name: "host", type: "text", title: "Flic hub IP", required: false
        input name: "port", type: "number", title: "TCP port", required: false, defaultValue: 46321
        input name: "debugLogging", type: "bool", title: "Enable debug logging", defaultValue: true
        input name: "useByteInterface", type: "bool", title: "Use byteInterface (HEX RX/TX)", defaultValue: true
        input name: "sendDelayMs", type: "number", title: "Delay before sending (ms)", defaultValue: 250
        input name: "autoCloseMs", type: "number", title: "Auto-close after (ms)", defaultValue: 2500
    }
}

def installed() { initialize() }
def updated()  { initialize() }

def initialize() {
    state.pendingLine = null
    state.rxBuf = ""
    state.closeScheduled = false
    state.overrideHost = null
    state.overridePort = null
    sendEvent(name: "connection", value: "idle")
}

/* ---------------- Public commands ---------------- */

def refresh() { sendStatus() }

def sendPing()        { requestOnce([cmd: "PING"]) }
def sendStatus()      { requestOnce([cmd: "STATUS"]) }
def sendListButtons() { requestOnce([cmd: "LIST_BUTTONS"]) }

def sendPingTo(String h, Number p)        { requestOnceTo(h, p, [cmd: "PING"]) }
def sendStatusTo(String h, Number p)      { requestOnceTo(h, p, [cmd: "STATUS"]) }
def sendListButtonsTo(String h, Number p) { requestOnceTo(h, p, [cmd: "LIST_BUTTONS"]) }

def sendRawJson(String jsonLine) {
    if (!jsonLine) return
    String line = jsonLine.toString().trim()
    if (!line.startsWith("{") || !line.endsWith("}")) {
        setErr("sendRawJson expects a JSON object line")
        return
    }
    requestOnce(line)
}

def sendRawJsonTo(String h, Number p, String jsonLine) {
    if (!jsonLine) return
    String line = jsonLine.toString().trim()
    if (!line.startsWith("{") || !line.endsWith("}")) {
        setErr("sendRawJsonTo expects a JSON object line")
        return
    }
    requestOnceTo(h, p, line)
}

/* ---------------- One-shot request core ---------------- */

private void requestOnceTo(String h, Number p, Map obj) {
    requestOnceTo(h, p, JsonOutput.toJson(obj))
}

private void requestOnceTo(String h, Number p, String jsonLineNoNl) {
    state.overrideHost = h?.toString()?.trim()
    state.overridePort = (p != null ? (p as Integer) : null)
    requestOnce(jsonLineNoNl)
}

private void requestOnce(Map obj) {
    requestOnce(JsonOutput.toJson(obj))
}

private void requestOnce(String jsonLineNoNl) {
    String targetHost = cleanHost(state.overrideHost ?: settings.host)
    Integer targetPort = (state.overridePort != null ? (state.overridePort as Integer) : (settings.port as Integer))

    // clear overrides after we’ve read them
    state.overrideHost = null
    state.overridePort = null

    if (!targetHost || !targetPort) {
        setErr("Missing host/port (provide in preferences or use *To(host,port) commands)")
        sendEvent(name: "connection", value: "error")
        return
    }

    sendEvent(name: "lastTarget", value: "${targetHost}:${targetPort}")

    // Close any prior socket and start fresh
    try { interfaces.rawSocket.close() } catch (ignored) {}

    state.rxBuf = ""
    state.closeScheduled = false
    state.pendingLine = (jsonLineNoNl + "\n")

    sendEvent(name: "connection", value: "connecting")
    logInfo "Connecting to ${targetHost}:${targetPort} (byteInterface=${settings.useByteInterface}) ..."

    try {
        interfaces.rawSocket.connect([byteInterface: (settings.useByteInterface == true)], targetHost, targetPort)
    } catch (e) {
        setErr("connect_failed: ${e}")
        sendEvent(name: "connection", value: "error")
        return
    }

    runInMillis((settings.sendDelayMs ?: 250) as Integer, "flushPending")
    runInMillis((settings.autoCloseMs ?: 2500) as Integer, "closeSocket")
}

/**
 * rawSocket lifecycle callback (required name)
 */
def socketStatus(String message) {
    logInfo "socketStatus: ${message}"
    sendEvent(name: "lastSocketStatus", value: (message ?: ""))

    String m = (message ?: "").toLowerCase()

    if (m.contains("connect")) {
        sendEvent(name: "connection", value: "connected")
        clearErr()
        flushPending()
        return
    }

    if (m.contains("close") || m.contains("disconnect")) {
        sendEvent(name: "connection", value: "idle")
        return
    }

    if (m.contains("error") || m.contains("fail") || m.contains("broken")) {
        sendEvent(name: "connection", value: "error")
        return
    }
}

def flushPending() {
    if (!state.pendingLine) return

    try {
        String line = state.pendingLine

        if (settings.useByteInterface == true) {
            byte[] bytes = line.getBytes("UTF-8")
            String hex = HexUtils.byteArrayToHexString(bytes)
            interfaces.rawSocket.sendMessage(hex)
            logDebug ">> (HEX) ${hex}"
            logDebug ">> (TXT) ${line.trim()}"
        } else {
            interfaces.rawSocket.sendMessage(line)
            logDebug ">> ${line.trim()}"
        }

        state.pendingLine = null
    } catch (e) {
        setErr("send_failed: ${e}")
        sendEvent(name: "connection", value: "error")
    }
}

def closeSocket() {
    try {
        sendEvent(name: "connection", value: "closing")
        interfaces.rawSocket.close()
    } catch (ignored) {}
    sendEvent(name: "connection", value: "idle")
}

/* ---------------- Receiving ---------------- */

def parse(String message) {
    if (message == null) return

    String s = message.toString()
    if (!s) return

    String text = null

    if (settings.useByteInterface == true) {
        String trimmed = s.trim()
        if (!trimmed) return

        if (trimmed ==~ /^[0-9A-Fa-f]+$/) {
            try {
                byte[] bytes = HexUtils.hexStringToByteArray(trimmed)
                text = new String(bytes, "UTF-8")
            } catch (e) {
                setErr("hex_decode_failed: ${e}")
                return
            }
        } else {
            text = trimmed
        }
    } else {
        text = s
    }

    if (!text) return

    state.rxBuf = (state.rxBuf ?: "") + text

    while (true) {
        int idx = state.rxBuf.indexOf("\n")
        if (idx < 0) break

        String line = state.rxBuf.substring(0, idx).trim()
        state.rxBuf = state.rxBuf.substring(idx + 1)

        if (!line) continue

        logDebug "<< ${line}"
        handleJsonLine(line)
    }
}

private void handleJsonLine(String line) {
    try {
        def obj = new JsonSlurper().parseText(line)
        String pretty = JsonOutput.prettyPrint(JsonOutput.toJson(obj))

        sendEvent(name: "lastResponse", value: pretty)

        if (obj instanceof Map && obj.hello) {
            sendEvent(name: "lastHello", value: pretty)
        } else {
            sendEvent(name: "lastNonHello", value: pretty)

            if (!state.closeScheduled) {
                state.closeScheduled = true
                runInMillis(350, "closeSocket")
            }
        }
    } catch (e) {
        setErr("parse_failed: ${e} (line=${line})")
    }
}

/* ---------------- Helpers ---------------- */

private String cleanHost(def h) {
    String s = (h ?: "").toString().trim()
    if (!s) return null
    s = s.replaceFirst(/^https?:\/\//, "")
    // strip any trailing slash
    s = s.replaceAll(/\/+$/, "")
    return s
}

private void setErr(String msg) {
    sendEvent(name: "lastError", value: msg)
    logWarn msg
}

private void clearErr() {
    sendEvent(name: "lastError", value: "")
}

private void logDebug(String msg) {
    if (settings.debugLogging) log.debug "${device.displayName}: ${msg}"
}

private void logInfo(String msg) {
    log.info "${device.displayName}: ${msg}"
}

private void logWarn(String msg) {
    log.warn "${device.displayName}: ${msg}"
}
