metadata {
    definition (name: "My Keen Home Smart Vent v2", namespace: "jscgs350", author: "Keen Home") {
        capability "Switch Level"
        capability "Switch"
        capability "Configuration"
        capability "Refresh"
        capability "Sensor"
        capability "Temperature Measurement"
        capability "Battery"

        command "getLevel"
        command "getOnOff"
        command "getPressure"
        command "getBattery"
        command "getTemperature"
        command "setZigBeeIdTile"
        command "clearObstruction"

        fingerprint endpoint: "1",
        profileId: "0104",
        inClusters: "0000,0001,0003,0004,0005,0006,0008,0020,0402,0403,0B05,FC01,FC02",
        outClusters: "0019"
    }

    // UI tile definitions
    tiles(scale: 2) {
        multiAttributeTile(name:"switch", type: "lighting", width: 6, height: 4, canChangeIcon: true, decoration: "flat"){
            tileAttribute ("device.switch", key: "PRIMARY_CONTROL") {
                attributeState "on", action: "switch.off", label: 'OPEN', icon: "st.vents.vent-open", backgroundColor: "#53a7c0"
                attributeState "off", action: "switch.on", label: "CLOSED", icon: "st.vents.vent-open", backgroundColor: "#ffffff"
                attributeState "obstructed", action: "clearObstruction", label: "OBSTRUCTION", icon: "st.vents.vent-open", backgroundColor: "#ff0000"
                attributeState "clearing", action: "", label: "CLEARING", icon: "st.vents.vent-open", backgroundColor: "#ffff33"
            }
            tileAttribute ("device.level", key: "SLIDER_CONTROL") {
                attributeState "level", action:"switch level.setLevel"
            }
        }        
        valueTile("temperature", "device.temperature", inactiveLabel: false, width: 2, height: 2) {
            state "temperature", label:'${currentValue}°',
            backgroundColors:[
                [value: 31, color: "#153591"],
                [value: 44, color: "#1e9cbb"],
                [value: 59, color: "#90d2a7"],
                [value: 74, color: "#44b621"],
                [value: 84, color: "#f1d801"],
                [value: 95, color: "#d04e00"],
                [value: 96, color: "#bc2323"]
            ]
        }
        valueTile("pressure", "device.pressure", inactiveLabel: false, width: 2, height: 2, decoration: "flat") {
            state "pressure", label: 'Barometric\nPressure\n${currentValue}Hg', backgroundColor:"#ffffff"
        }        
        valueTile("battery", "device.battery", inactiveLabel: false, width: 2, height: 2, decoration: "flat") {
            state "battery", label: 'Battery \n${currentValue}%', backgroundColor:"#ffffff"
        }
        standardTile("refresh", "device.power", inactiveLabel: false, width: 3, height: 2, decoration: "flat") {
            state "default", label:'', action:"refresh.refresh", icon:"st.secondary.refresh"
        }        
        standardTile("configure", "device.configure", inactiveLabel: false, width: 3, height: 2, decoration: "flat") {
            state "configure", label:'', action:"configuration.configure", icon:"st.secondary.configure"
        }
        valueTile("zigbeeId", "device.zigbeeId", inactiveLabel: true, decoration: "flat") {
            state "serial", label:'${currentValue}', backgroundColor:"#ffffff"
        }        
        main "switch"
        details(["switch", "temperature", "pressure", "battery", "refresh", "configure"])
    }
}

/**** PARSE METHODS ****/
def parse(String description) {
    log.debug "description: $description"

    Map map = [:]
    if (description?.startsWith('catchall:')) {
        map = parseCatchAllMessage(description)
    }
    else if (description?.startsWith('read attr -')) {
        map = parseReportAttributeMessage(description)
    }
    else if (description?.startsWith('temperature: ') || description?.startsWith('humidity: ')) {
        map = parseCustomMessage(description)
    }
    else if (description?.startsWith('on/off: ')) {
        map = parseOnOffMessage(description)
    }

    log.debug "Parse returned $map"
    return map ? createEvent(map) : null
}

private Map parseCatchAllMessage(String description) {
    log.debug "parseCatchAllMessage"

    def cluster = zigbee.parse(description)
    log.debug "cluster: ${cluster}"
    if (shouldProcessMessage(cluster)) {
        log.debug "processing message"
        switch(cluster.clusterId) {
            case 0x0001:
                return makeBatteryResult(cluster.data.last())
                break

            case 0x0402:
                // temp is last 2 data values. reverse to swap endian
                String temp = cluster.data[-2..-1].reverse().collect { cluster.hex1(it) }.join()
                def value = convertTemperatureHex(temp)
                return makeTemperatureResult(value)
                break

            case 0x0006:
                return makeOnOffResult(cluster.data[-1])
                break
        }
    }

    return [:]
}

private boolean shouldProcessMessage(cluster) {
    // 0x0B is default response indicating message got through
    // 0x07 is bind message
    if (cluster.profileId != 0x0104 ||
        cluster.command == 0x0B ||
        cluster.command == 0x07 ||
        (cluster.data.size() > 0 && cluster.data.first() == 0x3e)) {
        return false
    }

    return true
}

private Map parseReportAttributeMessage(String description) {
    log.debug "parseReportAttributeMessage"

    Map descMap = (description - "read attr - ").split(",").inject([:]) { map, param ->
        def nameAndValue = param.split(":")
        map += [(nameAndValue[0].trim()):nameAndValue[1].trim()]
    }
    log.debug "Desc Map: $descMap"

    if (descMap.cluster == "0006" && descMap.attrId == "0000") {
        return makeOnOffResult(Int.parseInt(descMap.value));
    }
    else if (descMap.cluster == "0008" && descMap.attrId == "0000") {
        return makeLevelResult(descMap.value)
    }
    else if (descMap.cluster == "0402" && descMap.attrId == "0000") {
        def value = convertTemperatureHex(descMap.value)
        return makeTemperatureResult(value)
    }
    else if (descMap.cluster == "0001" && descMap.attrId == "0021") {
        return makeBatteryResult(Integer.parseInt(descMap.value, 16))
    }
    else if (descMap.cluster == "0403" && descMap.attrId == "0020") {
        return makePressureResult(Integer.parseInt(descMap.value, 16))
    }
    else if (descMap.cluster == "0000" && descMap.attrId == "0006") {
        return makeSerialResult(new String(descMap.value.decodeHex()))
    }

    // shouldn't get here
    return [:]
}

private Map parseCustomMessage(String description) {
    Map resultMap = [:]
    if (description?.startsWith('temperature: ')) {
        // log.debug "${description}"
        // def value = zigbee.parseHATemperatureValue(description, "temperature: ", getTemperatureScale())
        // log.debug "split: " + description.split(": ")
        def value = Double.parseDouble(description.split(": ")[1])
        // log.debug "${value}"
        resultMap = makeTemperatureResult(convertTemperature(value))
    }
    return resultMap
}

private Map parseOnOffMessage(String description) {
    Map resultMap = [:]
    if (description?.startsWith('on/off: ')) {
        def value = Integer.parseInt(description - "on/off: ")
        resultMap = makeOnOffResult(value)
    }
    return resultMap
}

private Map makeOnOffResult(rawValue) {
    log.debug "makeOnOffResult: ${rawValue}"
    def linkText = getLinkText(device)
    def value = rawValue == 1 ? "on" : "off"
    
    return [
        name: "switch",
        value: value,
        descriptionText: "${linkText} is ${value}"
    ]
}

private Map makeLevelResult(rawValue) {
    def linkText = getLinkText(device)
    def value = Integer.parseInt(rawValue, 16)
    def rangeMax = 254

    // catch obstruction level
    if (value == 255) {
        log.debug "${linkText} is obstructed"
        // Just return here. Once the vent is power cycled
        // it will go back to the previous level before obstruction.
        // Therefore, no need to update level on the display.
        return [
            name: "switch",
            value: "obstructed",
            descriptionText: "${linkText} is obstructed. Please power cycle."
        ]
    }

    value = Math.floor(value / rangeMax * 100)

    return [
        name: "level",
        value: value,
        descriptionText: "${linkText} level is ${value}%"
    ]
}

private Map makePressureResult(rawValue) {
    log.debug 'makePressureResut'
    def linkText = getLinkText(device)

    def pressval = rawValue / 10 * 0.295300 / 1000
    def xpress = (pressval.toFloat()/1).round(2)
    def result = [
        name: 'pressure',
        descriptionText: "${linkText} pressure is ${xpress}Hg",
        value: xpress
    ]

    return result
}

private Map makeBatteryResult(rawValue) {
    // log.debug 'makeBatteryResult'
    def linkText = getLinkText(device)

    // log.debug
    [
        name: 'battery',
        value: rawValue,
        descriptionText: "${linkText} battery is at ${rawValue}%"
    ]
}

private Map makeTemperatureResult(value) {
    // log.debug 'makeTemperatureResult'
    def linkText = getLinkText(device)

    // log.debug "tempOffset: ${tempOffset}"
    if (tempOffset) {
        def offset = tempOffset as int
        // log.debug "offset: ${offset}"
        def v = value as int
        // log.debug "v: ${v}"
        value = v + offset
        // log.debug "value: ${value}"
    }

    return [
        name: 'temperature',
        value: "" + value,
        descriptionText: "${linkText} is ${value}°${temperatureScale}",
    ]
}

/**** HELPER METHODS ****/
private def convertTemperatureHex(value) {
    // log.debug "convertTemperatureHex(${value})"
    def celsius = Integer.parseInt(value, 16).shortValue() / 100
    // log.debug "celsius: ${celsius}"

    return convertTemperature(celsius)
}

private def convertTemperature(celsius) {
    // log.debug "convertTemperature()"

    if(getTemperatureScale() == "C"){
        return celsius
    } else {
        def fahrenheit = Math.round(celsiusToFahrenheit(celsius) * 100) /100
        // log.debug "converted to F: ${fahrenheit}"
        return fahrenheit
    }
}

private def makeSerialResult(serial) {
    log.debug "makeSerialResult: " + serial

    def linkText = getLinkText(device)
    sendEvent([
        name: "serial",
        value: serial,
        descriptionText: "${linkText} has serial ${serial}" ])
    return [
        name: "serial",
        value: serial,
        descriptionText: "${linkText} has serial ${serial}" ]
}

// takes a level from 0 to 100 and translates it to a ZigBee move to level with on/off command
private def makeLevelCommand(level) {
    def rangeMax = 254
    def scaledLevel = Math.round(level * rangeMax / 100)
    log.debug "scaled level for ${level}%: ${scaledLevel}"

    // convert to hex string and pad to two digits
    def hexLevel = new BigInteger(scaledLevel.toString()).toString(16).padLeft(2, '0')

    "st cmd 0x${device.deviceNetworkId} 1 8 4 {${hexLevel} 0000}"
}

/**** COMMAND METHODS ****/
def on() {
    def linkText = getLinkText(device)
    log.debug "open ${linkText}"

    // only change the state if the vent is not obstructed
    if (device.currentValue("switch") == "obstructed") {
        log.error("cannot open because ${linkText} is obstructed")
        return
    }
	
    sendEvent(makeOnOffResult(1))
    "st cmd 0x${device.deviceNetworkId} 1 6 1 {}"
}

def off() {
    def linkText = getLinkText(device)
    log.debug "close ${linkText}"

    // only change the state if the vent is not obstructed
    if (device.currentValue("switch") == "obstructed") {
        log.error("cannot close because ${linkText} is obstructed")
        return
    }

    sendEvent(makeOnOffResult(0))
    "st cmd 0x${device.deviceNetworkId} 1 6 0 {}"
}

def clearObstruction() {
    def linkText = getLinkText(device)
    log.debug "attempting to clear ${linkText} obstruction"

    sendEvent([
        name: "switch",
        value: "clearing",
        descriptionText: "${linkText} is clearing obstruction"
    ])

    // send a move command to ensure level attribute gets reset for old, buggy firmware
    // then send a reset to factory defaults
    // finally re-configure to ensure reports and binding is still properly set after the rtfd
    [
        makeLevelCommand(device.currentValue("level")), "delay 500",
        "st cmd 0x${device.deviceNetworkId} 1 0 0 {}", "delay 5000"
    ] + configure()
}

def setLevel(value) {
    log.debug "setting level: ${value}"
    def linkText = getLinkText(device)

    // only change the level if the vent is not obstructed
    def currentState = device.currentValue("switch")

    if (currentState == "obstructed") {
        log.error("cannot set level because ${linkText} is obstructed")
        return
    }

    sendEvent(name: "level", value: value)
    
    if (value > 0) {
        sendEvent(name: "switch", value: "on", descriptionText: "${linkText} is on by setting a level")
    }
    else {
        sendEvent(name: "switch", value: "off", descriptionText: "${linkText} is off by setting level to 0")
    }

    makeLevelCommand(value)
}

def getOnOff() {
    log.debug "getOnOff()"

    // disallow on/off updates while vent is obstructed
    if (device.currentValue("switch") == "obstructed") {
        log.error("cannot update open/close status because ${getLinkText(device)} is obstructed")
        return []
    }

    ["st rattr 0x${device.deviceNetworkId} 1 0x0006 0"]
}

def getPressure() {
    log.debug "getPressure()"

    // using a Keen Home specific attribute in the pressure measurement cluster
    [
        "zcl mfg-code 0x115B", "delay 200",
        "zcl global read 0x0403 0x20", "delay 200",
        "send 0x${device.deviceNetworkId} 1 1", "delay 200"
    ]
}

def getLevel() {
    log.debug "getLevel()"

    // disallow level updates while vent is obstructed
    if (device.currentValue("switch") == "obstructed") {
        log.error("cannot update level status because ${getLinkText(device)} is obstructed")
        return []
    }

    ["st rattr 0x${device.deviceNetworkId} 1 0x0008 0x0000"]
}

def getTemperature() {
    log.debug "getTemperature()"

    ["st rattr 0x${device.deviceNetworkId} 1 0x0402 0"]
}

def getBattery() {
    log.debug "getBattery()"

    ["st rattr 0x${device.deviceNetworkId} 1 0x0001 0x0021"]
}

def setZigBeeIdTile() {
    log.debug "setZigBeeIdTile() - ${device.zigbeeId}"

    def linkText = getLinkText(device)

    sendEvent([
        name: "zigbeeId",
        value: device.zigbeeId,
        descriptionText: "${linkText} has zigbeeId ${device.zigbeeId}" ])
	return [
        name: "zigbeeId",
        value: device.zigbeeId,
        descriptionText: "${linkText} has zigbeeId ${device.zigbeeId}" ]
}

def refresh() {
	getOnOff() +
    getLevel() +
    getTemperature() +
    getPressure() +
    getBattery()
}

def configure() {
    log.debug "CONFIGURE"

    // get ZigBee ID by hidden tile because that's the only way we can do it
    setZigBeeIdTile()

//    def configCmds = [
//        // bind reporting clusters to hub
//        "zdo bind 0x${device.deviceNetworkId} 1 1 0x0006 {${device.zigbeeId}} {}", "delay 500",
//        "zdo bind 0x${device.deviceNetworkId} 1 1 0x0008 {${device.zigbeeId}} {}", "delay 500",
//        "zdo bind 0x${device.deviceNetworkId} 1 1 0x0402 {${device.zigbeeId}} {}", "delay 500",
//        "zdo bind 0x${device.deviceNetworkId} 1 1 0x0403 {${device.zigbeeId}} {}", "delay 500",
//        "zdo bind 0x${device.deviceNetworkId} 1 1 0x0001 {${device.zigbeeId}} {}", "delay 500"

    def configCmds = [
        // configure report commands
        // [cluster] [attr] [type] [min-interval] [max-interval] [min-change]

        // mike 2015/06/22: preconfigured; see tech spec
        // vent on/off state - type: boolean, change: 1
        // "zcl global send-me-a-report 6 0 0x10 5 60 {01}", "delay 200",
        // "send 0x${device.deviceNetworkId} 1 1", "delay 1500",

        // mike 2015/06/22: preconfigured; see tech spec
        // vent level - type: int8u, change: 1
        // "zcl global send-me-a-report 8 0 0x20 5 60 {01}", "delay 200",
        // "send 0x${device.deviceNetworkId} 1 1", "delay 1500",

        // Yves Racine 2015/09/10: temp and pressure reports are preconfigured, but
        //   we'd like to override their settings for our own purposes
        // temperature - type: int16s, change: 0xA = 10 = 0.1C, 0x32=50=0.5C
        "zcl global send-me-a-report 0x0402 0 0x29 300 600 {3200}", "delay 200",
        "send 0x${device.deviceNetworkId} 1 1", "delay 1500",

        // Yves Racine 2015/09/10: use new custom pressure attribute
        // pressure - type: int32u, change: 1 = 0.1Pa, 500=50 PA
        "zcl mfg-code 0x115B", "delay 200",
        "zcl global send-me-a-report 0x0403 0x20 0x22 300 600 {01F400}", "delay 200",
        "send 0x${device.deviceNetworkId} 1 1", "delay 1500",

        // mike 2015/06/2: preconfigured; see tech spec
        // battery - type: int8u, change: 1
        // "zcl global send-me-a-report 1 0x21 0x20 60 3600 {01}", "delay 200",
        // "send 0x${device.deviceNetworkId} 1 1", "delay 1500",

        // binding commands
        "zdo bind 0x${device.deviceNetworkId} 1 1 0x0006 {${device.zigbeeId}} {}", "delay 500",
        "zdo bind 0x${device.deviceNetworkId} 1 1 0x0008 {${device.zigbeeId}} {}", "delay 500",
        "zdo bind 0x${device.deviceNetworkId} 1 1 0x0402 {${device.zigbeeId}} {}", "delay 500",
        "zdo bind 0x${device.deviceNetworkId} 1 1 0x0403 {${device.zigbeeId}} {}", "delay 500",
        "zdo bind 0x${device.deviceNetworkId} 1 1 0x0001 {${device.zigbeeId}} {}", "delay 500"
    ]

    return configCmds + refresh()
}